#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <pty.h>

#define TAG "NativePty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define PTY_MAGIC 0x5054594C /* 'PTYL' */
#define READ_POLL_MS 10

extern char **environ;

/*
 * Per-instance PTY session.
 *
 * Every NativePty Kotlin object owns exactly one PtySession, addressed by
 * an opaque handle.  Nothing is global anymore, so several terminal tabs
 * can run their own shell at the same time.
 *
 * The exit status is reaped exactly once and cached, otherwise the second
 * waitpid() (the one that asks for the exit code) fails with ECHILD and the
 * caller would see -1 instead of the real status.
 */
typedef struct {
    int magic;
    int master_fd;
    pid_t child_pid;
    int exited;      /* 1 once the child has been reaped                */
    int exit_known;  /* 1 if exit_status holds a valid wait status      */
    int exit_status; /* raw status returned by waitpid()                */
    int closing;     /* 1 after nativeClose() started                   */
    pthread_mutex_t lock;
} PtySession;

static PtySession *session_of(jlong handle) {
    PtySession *s = (PtySession *)(intptr_t)handle;
    if (s == NULL || s->magic != PTY_MAGIC) return NULL;
    return s;
}

/*
 * Reap the child if it terminated.  Caller must hold s->lock.
 * Returns 1 when the child is gone, 0 when it is still running.
 */
static int pty_reap_locked(PtySession *s) {
    if (s->exited) return 1;
    if (s->child_pid <= 0) {
        s->exited = 1;
        return 1;
    }

    int status = 0;
    pid_t r = waitpid(s->child_pid, &status, WNOHANG);
    if (r == 0) return 0;               /* still running */
    if (r == s->child_pid) {
        s->exit_status = status;
        s->exit_known = 1;
        s->exited = 1;
        return 1;
    }
    if (errno == ECHILD) {
        /* Someone else already reaped it; status is unknown. */
        s->exited = 1;
        s->exit_known = 0;
        return 1;
    }
    return 0; /* EINTR / EAGAIN: try again later */
}

/* Decode the cached status.  Caller must hold s->lock. */
static int pty_exit_code_locked(PtySession *s) {
    if (!s->exited) return -1;
    if (!s->exit_known) return -1;
    if (WIFEXITED(s->exit_status)) return WEXITSTATUS(s->exit_status);
    if (WIFSIGNALED(s->exit_status)) return 128 + WTERMSIG(s->exit_status);
    return -1;
}

/* Signal that killed the child, 0 when it exited normally. */
static int pty_exit_signal_locked(PtySession *s) {
    if (!s->exited || !s->exit_known) return 0;
    if (WIFSIGNALED(s->exit_status)) return WTERMSIG(s->exit_status);
    return 0;
}

/* Duplicate the environment array into a plain NULL-terminated vector. */
static char **dup_env(JNIEnv *env, jobjectArray envArray) {
    if (!envArray) return NULL;
    int len = (*env)->GetArrayLength(env, envArray);
    if (len <= 0) return NULL;

    char **out = (char **)calloc((size_t)len + 1, sizeof(char *));
    if (!out) return NULL;

    for (int i = 0; i < len; i++) {
        jstring jstr = (jstring)(*env)->GetObjectArrayElement(env, envArray, i);
        if (!jstr) continue;
        const char *pair = (*env)->GetStringUTFChars(env, jstr, NULL);
        if (pair) {
            out[i] = strdup(pair);
            (*env)->ReleaseStringUTFChars(env, jstr, pair);
        }
        (*env)->DeleteLocalRef(env, jstr);
    }
    return out;
}

static void free_env(char **e) {
    if (!e) return;
    for (int i = 0; e[i]; i++) free(e[i]);
    free(e);
}

static void pty_destroy_locked(PtySession *s) {
    s->magic = 0;
    pthread_mutex_unlock(&s->lock);
    pthread_mutex_destroy(&s->lock);
    free(s);
}

/**
 * Start a shell with a real PTY.
 *
 * Returns a handle (>= 1) on success, 0 on error.
 * Each call allocates its own session, so multiple tabs are independent.
 */
JNIEXPORT jlong JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeStartShell(
        JNIEnv *env, jobject thiz,
        jstring shellPath, jstring cwd,
        jobjectArray envArray) {

    const char *shell = (*env)->GetStringUTFChars(env, shellPath, NULL);
    if (!shell) return 0;
    const char *workDir = (*env)->GetStringUTFChars(env, cwd, NULL);

    char **child_env = dup_env(env, envArray);

    PtySession *s = (PtySession *)calloc(1, sizeof(PtySession));
    if (!s) {
        LOGE("out of memory for PtySession");
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        if (workDir) (*env)->ReleaseStringUTFChars(env, cwd, workDir);
        free_env(child_env);
        return 0;
    }
    s->magic = PTY_MAGIC;
    s->master_fd = -1;
    s->child_pid = -1;
    pthread_mutex_init(&s->lock, NULL);

    int master = -1, slave = -1;
    if (openpty(&master, &slave, NULL, NULL, NULL) < 0) {
        LOGE("openpty failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        if (workDir) (*env)->ReleaseStringUTFChars(env, cwd, workDir);
        free_env(child_env);
        pty_destroy_locked(s);
        return 0;
    }

    /* Non-blocking master so the reader thread can poll cheaply. */
    fcntl(master, F_SETFL, O_NONBLOCK);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    /*
     * Wide initial size: the view resizes the PTY on layout, but input sent
     * before that (paste, long commands) wraps at 80 columns and mksh's line
     * editor garbles typed-ahead lines that wrap, leaving them unsubmitted.
     */
    ws.ws_col = 200;
    ws.ws_row = 40;
    ioctl(slave, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(master);
        close(slave);
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        if (workDir) (*env)->ReleaseStringUTFChars(env, cwd, workDir);
        free_env(child_env);
        pty_destroy_locked(s);
        return 0;
    }

    if (pid == 0) {
        /* === CHILD PROCESS === */
        close(master);

        /* New session + controlling terminal (required for job control) */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        if (workDir) chdir(workDir);

        if (child_env) environ = child_env;

        execl(shell, shell, (char *)NULL);
        _exit(127); /* exec failed */
    }

    /* === PARENT PROCESS === */
    close(slave);
    s->master_fd = master;
    s->child_pid = pid;

    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    if (workDir) (*env)->ReleaseStringUTFChars(env, cwd, workDir);
    free_env(child_env);

    LOGI("Shell started: pid=%d, master_fd=%d", pid, master);
    return (jlong)(intptr_t)s;
}

/**
 * Read from the PTY master.
 *
 * Polls for READ_POLL_MS while holding the session lock, so a concurrent
 * write/close can never observe a half-updated session.  Returns
 *   > 0  bytes read
 *     0  no data yet (timeout / EAGAIN)
 *    -1  EOF, PTY closed or child gone
 */
JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeRead(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray buffer, jint offset, jint len) {

    PtySession *s = session_of(handle);
    if (!s) return -1;

    pthread_mutex_lock(&s->lock);

    if (s->closing || s->master_fd < 0) {
        pthread_mutex_unlock(&s->lock);
        return -1;
    }

    struct pollfd pfd;
    pfd.fd = s->master_fd;
    pfd.events = POLLIN;
    pfd.revents = 0;

    int pr = poll(&pfd, 1, READ_POLL_MS);
    if (pr == 0) {
        pthread_mutex_unlock(&s->lock);
        return 0;
    }
    if (pr < 0) {
        int e = errno;
        pthread_mutex_unlock(&s->lock);
        return (e == EINTR) ? 0 : -1;
    }

    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        pthread_mutex_unlock(&s->lock);
        return -1;
    }

    ssize_t n = read(s->master_fd, buf + offset, len);
    int err = errno;
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    pthread_mutex_unlock(&s->lock);

    if (n > 0) return (jint)n;
    if (n == 0) return -1; /* EOF */
    if (err == EAGAIN || err == EWOULDBLOCK || err == EINTR) return 0;
    if (err == EIO) return -1; /* slave side closed: shell exited */
    return -1;
}

/**
 * Write input to the PTY master.
 */
JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeWrite(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray data, jint offset, jint len) {

    PtySession *s = session_of(handle);
    if (!s) return -1;

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return -1;

    pthread_mutex_lock(&s->lock);

    if (s->closing || s->master_fd < 0) {
        pthread_mutex_unlock(&s->lock);
        (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
        return -1;
    }

    ssize_t written = 0;
    int stalls = 0;
    while (written < len) {
        ssize_t n = write(s->master_fd, buf + offset + written, len - written);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                /*
                 * PTY buffer full: wait for the shell to drain it instead of
                 * silently dropping the remainder of the input.  Bounded to
                 * ~5s total (10 x 500ms) so a stopped reader cannot block
                 * the caller forever.
                 */
                if (++stalls > 10) break;
                struct pollfd wpfd;
                wpfd.fd = s->master_fd;
                wpfd.events = POLLOUT;
                wpfd.revents = 0;
                int wp = poll(&wpfd, 1, 500);
                if (wp < 0 && errno != EINTR) break;
                if (s->closing || s->master_fd < 0) break;
                continue;
            }
            break;
        }
        written += n;
    }

    pthread_mutex_unlock(&s->lock);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return (jint)written;
}

/**
 * Resize the PTY (called when terminal view size changes).
 */
JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeResize(
        JNIEnv *env, jobject thiz, jlong handle,
        jint cols, jint rows) {

    PtySession *s = session_of(handle);
    if (!s) return JNI_FALSE;

    pthread_mutex_lock(&s->lock);
    if (s->closing || s->master_fd < 0) {
        pthread_mutex_unlock(&s->lock);
        return JNI_FALSE;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;

    int r = ioctl(s->master_fd, TIOCSWINSZ, &ws);
    if (r < 0) LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    pthread_mutex_unlock(&s->lock);
    return (r == 0) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Check if the child process is still alive.
 * Reaps it once and caches the wait status for nativeGetExitCode().
 */
JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeIsAlive(
        JNIEnv *env, jobject thiz, jlong handle) {

    PtySession *s = session_of(handle);
    if (!s) return JNI_FALSE;

    pthread_mutex_lock(&s->lock);
    int gone = pty_reap_locked(s);
    pthread_mutex_unlock(&s->lock);
    return gone ? JNI_FALSE : JNI_TRUE;
}

/**
 * Get the child exit code (after it has exited).
 *   >= 0  real exit status (128+signal when killed by a signal)
 *     -1  still running, or status could not be determined
 */
JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeGetExitCode(
        JNIEnv *env, jobject thiz, jlong handle) {

    PtySession *s = session_of(handle);
    if (!s) return -1;

    pthread_mutex_lock(&s->lock);
    pty_reap_locked(s);
    int code = pty_exit_code_locked(s);
    pthread_mutex_unlock(&s->lock);
    return code;
}

/**
 * Signal that terminated the child, 0 when it exited normally.
 */
JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeGetExitSignal(
        JNIEnv *env, jobject thiz, jlong handle) {

    PtySession *s = session_of(handle);
    if (!s) return 0;

    pthread_mutex_lock(&s->lock);
    pty_reap_locked(s);
    int sig = pty_exit_signal_locked(s);
    pthread_mutex_unlock(&s->lock);
    return sig;
}

/**
 * Close the PTY and kill the child process, then free the session.
 * Kotlin serialises calls per NativePty instance, so no other native
 * method can still be using this session.
 */
JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeClose(
        JNIEnv *env, jobject thiz, jlong handle) {

    PtySession *s = session_of(handle);
    if (!s) return;

    pthread_mutex_lock(&s->lock);
    if (s->closing) {
        pthread_mutex_unlock(&s->lock);
        return;
    }
    s->closing = 1;

    if (s->child_pid > 0 && !s->exited) {
        kill(s->child_pid, SIGHUP);
        usleep(50000);
        if (!pty_reap_locked(s)) {
            kill(s->child_pid, SIGKILL);
            for (int i = 0; i < 50 && !pty_reap_locked(s); i++) usleep(10000);
        }
    }

    if (s->master_fd >= 0) {
        close(s->master_fd);
        s->master_fd = -1;
    }

    pty_destroy_locked(s);
    LOGI("PTY closed");
}

/**
 * Send a signal to the child process.
 */
JNIEXPORT jboolean JNICALL
Java_com_aide_mobilestudio_terminal_NativePty_nativeSendSignal(
        JNIEnv *env, jobject thiz, jlong handle, jint signal) {

    PtySession *s = session_of(handle);
    if (!s) return JNI_FALSE;

    pthread_mutex_lock(&s->lock);
    jboolean ok = JNI_FALSE;
    if (!s->closing && s->child_pid > 0 && !s->exited) {
        ok = (kill(s->child_pid, signal) == 0) ? JNI_TRUE : JNI_FALSE;
    }
    pthread_mutex_unlock(&s->lock);
    return ok;
}
