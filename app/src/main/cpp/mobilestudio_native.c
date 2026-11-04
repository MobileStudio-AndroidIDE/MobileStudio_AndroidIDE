/**
 * mobilestudio_native.c  (v4 — 권한 문제 완전 해결)
 *
 * 변경 사항:
 *   1. apply_exec_recursive:
 *      - 심볼릭 링크 stat 대신 lstat 사용 (dangling symlink 방어)
 *      - 디렉토리도 rwx 부여
 *
 *   2. memfd_create_compat:
 *      - /dev/shm fallback 제거 → /proc/self/fd 기반 경로만 사용
 *      - MFD_CLOEXEC 제거 (Java 로 fd 반환 후 exec 까지 살아있어야 함)
 *
 *   3. mkdir_p 헬퍼 추가 (부모 디렉토리 재귀 생성)
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <dirent.h>
#include <android/log.h>
#include <dlfcn.h>
#include <malloc.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL 10000
#endif
#include <pthread.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <limits.h>
#include <utime.h>
#include <sys/statvfs.h>
#include <math.h>

/* LlamaAndroid JNI: stubs removed — real impl in llama_jni.cpp (libllama_android.so) */

/* ms_fexecve, ms_mkdir_p, ms_memfd_open etc. shared utilities */
#include "ms_utils.h"

#define TAG "MobileStudioNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/* =========================================================
 * 유틸리티
 * ========================================================= */

static jstring make_jstring(JNIEnv *env, const char *s) {
    return (*env)->NewStringUTF(env, s ? s : "");
}

static const char *jstr(JNIEnv *env, jstring js) {
    if (!js) return NULL;
    return (*env)->GetStringUTFChars(env, js, NULL);
}

static void release_jstr(JNIEnv *env, jstring js, const char *cs) {
    if (js && cs) (*env)->ReleaseStringUTFChars(env, js, cs);
}

/* mkdir_p → ms_utils.h 의 ms_ms_mkdir_p() 사용 */

/* =========================================================
 * noexec 감지
 * ========================================================= */

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_isNoexec(
        JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = jstr(env, jpath);
    if (!path) return -1;

    FILE *f = fopen("/proc/mounts", "r");
    if (!f) { release_jstr(env, jpath, path); return -1; }

    char line[1024];
    int result = 0;
    size_t best_match = 0;

    while (fgets(line, sizeof(line), f)) {
        char device[256], mountpoint[256], fstype[64], options[512];
        if (sscanf(line, "%255s %255s %63s %511s", device, mountpoint, fstype, options) < 4)
            continue;
        size_t mplen = strlen(mountpoint);
        if (strncmp(path, mountpoint, mplen) == 0 &&
            (path[mplen] == '/' || path[mplen] == '\0') &&
            mplen >= best_match) {
            best_match = mplen;
            result = (strstr(options, "noexec") != NULL) ? 1 : 0;
        }
    }
    fclose(f);
    release_jstr(env, jpath, path);
    return result;
}

/* =========================================================
 * chmod / 권한 조작
 * ========================================================= */

/**
 * 파일/디렉토리에 재귀적으로 권한 부여.
 *   - 디렉토리: rwxr-xr-x (0755)
 *   - 일반 파일: rwxr-xr-x (0755)
 *   - 심볼릭 링크: lstat 로 확인하므로 skip (lchmod 없음)
 */
static int apply_exec_recursive(const char *dir_path) {
    int count = 0;
    struct stat st;

    /* lstat: 심링크 자체를 stat (dangling symlink 방어) */
    if (lstat(dir_path, &st) != 0) return 0;

    if (S_ISLNK(st.st_mode)) return 0;   /* 심링크는 건너뜀 */

    if (S_ISREG(st.st_mode)) {
        if (chmod(dir_path, 0755) == 0) count++;
        else LOGW("chmod failed: %s (%s)", dir_path, strerror(errno));
        return count;
    }

    if (S_ISDIR(st.st_mode)) {
        chmod(dir_path, 0755);   /* 디렉토리도 rwx */
        DIR *d = opendir(dir_path);
        if (!d) return 0;
        struct dirent *ent;
        while ((ent = readdir(d)) != NULL) {
            if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) continue;
            char child[PATH_MAX];
            snprintf(child, sizeof(child), "%s/%s", dir_path, ent->d_name);
            count += apply_exec_recursive(child);
        }
        closedir(d);
    }
    return count;
}

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_applyExecPermissions(
        JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = jstr(env, jpath);
    if (!path) return 0;
    int n = apply_exec_recursive(path);
    LOGI("applyExecPermissions: %s → %d files", path, n);
    release_jstr(env, jpath, path);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_chmodNative(
        JNIEnv *env, jclass clazz, jstring jpath, jint mode) {
    (void)clazz;
    const char *path = jstr(env, jpath);
    if (!path) return -1;
    int ret = chmod(path, (mode_t)mode);
    if (ret != 0) LOGW("chmod(%s, %o) failed: %s", path, mode, strerror(errno));
    release_jstr(env, jpath, path);
    return ret;
}

/* =========================================================
 * 심링크 생성
 * ========================================================= */

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_symlinkNative(
        JNIEnv *env, jclass clazz, jstring jtarget, jstring jlink) {
    (void)clazz;
    const char *target = jstr(env, jtarget);
    const char *link   = jstr(env, jlink);
    if (!target || !link) {
        release_jstr(env, jtarget, target);
        release_jstr(env, jlink,   link);
        return -1;
    }
    unlink(link);
    int ret = symlink(target, link);
    if (ret != 0) LOGW("symlink(%s → %s) failed: %s", link, target, strerror(errno));
    release_jstr(env, jtarget, target);
    release_jstr(env, jlink,   link);
    return ret;
}

/* =========================================================
 * memfd_create 기반 exec-in-memory
 * ========================================================= */

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static int memfd_create_compat(const char *name) {
#if defined(__NR_memfd_create)
    /* MFD_CLOEXEC 제거: Java 로 fd 반환 후 /proc/<pid>/fd/<n> 경로로 사용해야 하므로
       exec 후에도 fd 가 살아있어야 함 */
    int fd = (int)syscall(__NR_memfd_create, name, 0);
    if (fd >= 0) return fd;
    LOGW("memfd_create syscall failed: %s", strerror(errno));
#endif
    /* Fallback: /proc/self/fd 기반 경로 사용 불가 → 실패 반환 */
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_openExecMemFd(
        JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = jstr(env, jpath);
    if (!path) return -1;

    int src = open(path, O_RDONLY);
    if (src < 0) {
        LOGE("openExecMemFd: open(%s) failed: %s", path, strerror(errno));
        release_jstr(env, jpath, path);
        return -1;
    }

    struct stat st;
    fstat(src, &st);
    off_t size = st.st_size;

    int mfd = memfd_create_compat("ms_exec");
    if (mfd < 0) {
        close(src);
        release_jstr(env, jpath, path);
        return -1;
    }

    ftruncate(mfd, size);
    char buf[65536];
    ssize_t n;
    while ((n = read(src, buf, sizeof(buf))) > 0) {
        write(mfd, buf, (size_t)n);
    }
    close(src);
    lseek(mfd, 0, SEEK_SET);
    LOGI("openExecMemFd: %s → memfd=%d (size=%lld)", path, mfd, (long long)size);
    release_jstr(env, jpath, path);
    return mfd;
}

JNIEXPORT jstring JNICALL
Java_com_example_mobilestudio_NativeBridge_getProcFdPath(
        JNIEnv *env, jclass clazz, jint fd) {
    (void)clazz;
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    return make_jstring(env, path);
}

/* =========================================================
 * codeCacheDir 에 바이너리 복사 (noexec 우회)
 * ========================================================= */

static int copy_file_native(const char *src, const char *dst) {
    int in  = open(src, O_RDONLY);
    if (in < 0) { LOGE("copy: open src %s: %s", src, strerror(errno)); return -1; }

    struct stat st;
    fstat(in, &st);

    /* 부모 디렉터리 재귀 생성 */
    char tmp[PATH_MAX];
    strncpy(tmp, dst, sizeof(tmp) - 1);
    char *slash = strrchr(tmp, '/');
    if (slash) { *slash = '\0'; ms_mkdir_p(tmp); }

    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (out < 0) {
        LOGE("copy: open dst %s: %s", dst, strerror(errno));
        close(in);
        return -1;
    }

    char buf[65536];
    ssize_t n;
    while ((n = read(in, buf, sizeof(buf))) > 0) {
        write(out, buf, (size_t)n);
    }
    close(in);
    close(out);
    chmod(dst, 0755);

    struct utimbuf ut = { st.st_atime, st.st_mtime };
    utime(dst, &ut);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_copyBinaryToExecDir(
        JNIEnv *env, jclass clazz, jstring jsrc, jstring jdst) {
    (void)clazz;
    const char *src = jstr(env, jsrc);
    const char *dst = jstr(env, jdst);
    if (!src || !dst) {
        release_jstr(env, jsrc, src);
        release_jstr(env, jdst, dst);
        return -1;
    }
    int ret = copy_file_native(src, dst);
    LOGI("copyBinaryToExecDir: %s → %s (%d)", src, dst, ret);
    release_jstr(env, jsrc, src);
    release_jstr(env, jdst, dst);
    return ret;
}

/* =========================================================
 * .so 재귀 복사 (JVM 라이브러리용)
 * ========================================================= */

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_copySharedLibsNative(
        JNIEnv *env, jclass clazz, jstring jsrcDir, jstring jdstDir) {
    (void)clazz;
    const char *src_dir = jstr(env, jsrcDir);
    const char *dst_dir = jstr(env, jdstDir);
    if (!src_dir || !dst_dir) {
        release_jstr(env, jsrcDir, src_dir);
        release_jstr(env, jdstDir, dst_dir);
        return 0;
    }

    int count = 0;
    typedef struct { char src[PATH_MAX]; char dst[PATH_MAX]; } DirPair;
    DirPair stack[64];
    int top = 0;
    strncpy(stack[top].src, src_dir, PATH_MAX - 1);
    strncpy(stack[top].dst, dst_dir, PATH_MAX - 1);
    top++;

    while (top > 0) {
        DirPair cur = stack[--top];
        DIR *d = opendir(cur.src);
        if (!d) continue;

        struct dirent *ent;
        while ((ent = readdir(d)) != NULL) {
            if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) continue;

            char csrc[PATH_MAX], cdst[PATH_MAX];
            snprintf(csrc, sizeof(csrc), "%s/%s", cur.src, ent->d_name);
            snprintf(cdst, sizeof(cdst), "%s/%s", cur.dst, ent->d_name);

            struct stat st;
            if (lstat(csrc, &st) != 0) continue;

            if (S_ISDIR(st.st_mode)) {
                ms_mkdir_p(cdst);
                if (top < 63) {
                    strncpy(stack[top].src, csrc, PATH_MAX - 1);
                    strncpy(stack[top].dst, cdst, PATH_MAX - 1);
                    top++;
                }
            } else if (S_ISREG(st.st_mode)) {
                const char *ext = strrchr(ent->d_name, '.');
                if (ext && (strcmp(ext, ".so") == 0 || strcmp(ext, ".jsa") == 0)) {
                    if (copy_file_native(csrc, cdst) == 0) count++;
                }
            } else if (S_ISLNK(st.st_mode)) {
                char target[PATH_MAX];
                ssize_t len = readlink(csrc, target, sizeof(target) - 1);
                if (len > 0) {
                    target[len] = '\0';
                    unlink(cdst);
                    symlink(target, cdst);
                    count++;
                }
            }
        }
        closedir(d);
    }

    LOGI("copySharedLibsNative: %s → %s (%d items)", src_dir, dst_dir, count);
    release_jstr(env, jsrcDir, src_dir);
    release_jstr(env, jdstDir, dst_dir);
    return count;
}

/* =========================================================
 * 파일 mtime 비교
 * ========================================================= */

JNIEXPORT jlong JNICALL
Java_com_example_mobilestudio_NativeBridge_getFileMtime(
        JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = jstr(env, jpath);
    if (!path) return -1L;
    struct stat st;
    long mtime = (stat(path, &st) == 0) ? (long)st.st_mtime * 1000L : -1L;
    release_jstr(env, jpath, path);
    return (jlong)mtime;
}

/* =========================================================
 * spawnProcess (레거시 — 단일 전역 세션, 하위 호환용)
 * ========================================================= */

typedef struct {
    char **argv;
    char **envp;
    char  *cwd;
    int    pipefd[2];
    pid_t  pid;
} ExecState;

static ExecState g_exec = { NULL, NULL, NULL, {-1,-1}, -1 };
static pthread_mutex_t g_exec_mutex = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_spawnProcess(
        JNIEnv *env, jclass clazz,
        jobjectArray jargv, jobjectArray jenvp, jstring jcwd) {
    (void)clazz;

    pthread_mutex_lock(&g_exec_mutex);
    if (g_exec.pid > 0) {
        LOGW("spawnProcess: already running (pid=%d)", g_exec.pid);
        pthread_mutex_unlock(&g_exec_mutex);
        return -1;
    }

    int argc = (*env)->GetArrayLength(env, jargv);
    int envc = (*env)->GetArrayLength(env, jenvp);
    const char *cwd = jstr(env, jcwd);

    char **argv = calloc((size_t)(argc + 1), sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jargv, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
    }
    argv[argc] = NULL;

    char **envp_arr = calloc((size_t)(envc + 1), sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jenvp, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        envp_arr[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
    }
    envp_arr[envc] = NULL;

    if (pipe(g_exec.pipefd) != 0) {
        LOGE("spawnProcess pipe() failed: %s", strerror(errno));
        for (int i = 0; i < argc; i++) free(argv[i]); free(argv);
        for (int i = 0; i < envc; i++) free(envp_arr[i]); free(envp_arr);
        release_jstr(env, jcwd, cwd);
        pthread_mutex_unlock(&g_exec_mutex);
        return -1;
    }

    pid_t pid = fork();
    if (pid == 0) {
        close(g_exec.pipefd[0]);
        dup2(g_exec.pipefd[1], STDOUT_FILENO);
        dup2(g_exec.pipefd[1], STDERR_FILENO);
        close(g_exec.pipefd[1]);
        signal(SIGPIPE, SIG_IGN);
        if (cwd && cwd[0]) { if (chdir(cwd) != 0) chdir("/"); }
        execve(argv[0], argv, envp_arr);
        char errmsg[256];
        snprintf(errmsg, sizeof(errmsg), "[NATIVE] execve(%s) failed: %s\n", argv[0], strerror(errno));
        write(STDOUT_FILENO, errmsg, strlen(errmsg));
        _exit(127);
    }

    close(g_exec.pipefd[1]);
    g_exec.pipefd[1] = -1;
    g_exec.pid  = pid;
    g_exec.argv = argv;
    g_exec.envp = envp_arr;
    g_exec.cwd  = cwd ? strdup(cwd) : NULL;

    LOGI("spawnProcess: pid=%d", pid);
    release_jstr(env, jcwd, cwd);
    pthread_mutex_unlock(&g_exec_mutex);
    return (jint)pid;
}

JNIEXPORT jstring JNICALL
Java_com_example_mobilestudio_NativeBridge_readProcessOutput(
        JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_exec_mutex);
    int fd = g_exec.pipefd[0];
    pthread_mutex_unlock(&g_exec_mutex);
    if (fd < 0) return make_jstring(env, "");
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    if (n <= 0) return NULL;
    buf[n] = '\0';
    return make_jstring(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_waitProcess(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_exec_mutex);
    pid_t pid = g_exec.pid;
    pthread_mutex_unlock(&g_exec_mutex);
    if (pid <= 0) return -1;
    int status;
    waitpid(pid, &status, 0);
    pthread_mutex_lock(&g_exec_mutex);
    if (g_exec.pipefd[0] >= 0) { close(g_exec.pipefd[0]); g_exec.pipefd[0] = -1; }
    for (int i = 0; g_exec.argv && g_exec.argv[i]; i++) free(g_exec.argv[i]);
    free(g_exec.argv); g_exec.argv = NULL;
    for (int i = 0; g_exec.envp && g_exec.envp[i]; i++) free(g_exec.envp[i]);
    free(g_exec.envp); g_exec.envp = NULL;
    free(g_exec.cwd); g_exec.cwd = NULL;
    g_exec.pid = -1;
    pthread_mutex_unlock(&g_exec_mutex);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

JNIEXPORT void JNICALL
Java_com_example_mobilestudio_NativeBridge_killProcess(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_exec_mutex);
    if (g_exec.pid > 0) kill(g_exec.pid, SIGKILL);
    pthread_mutex_unlock(&g_exec_mutex);
}

/* =========================================================
 * ABI / 플랫폼 정보
 * ========================================================= */

JNIEXPORT jstring JNICALL
Java_com_example_mobilestudio_NativeBridge_getNativeAbi(
        JNIEnv *env, jclass clazz) {
    (void)clazz;
#if defined(__aarch64__)
    return make_jstring(env, "arm64-v8a");
#elif defined(__arm__)
    return make_jstring(env, "armeabi-v7a");
#elif defined(__x86_64__)
    return make_jstring(env, "x86_64");
#elif defined(__i386__)
    return make_jstring(env, "x86");
#else
    return make_jstring(env, "unknown");
#endif
}

JNIEXPORT jint JNICALL
Java_com_example_mobilestudio_NativeBridge_getAndroidApiLevel(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jint)__ANDROID_API__;
}

/* =========================================================
 * 디스크 여유 공간
 * ========================================================= */

JNIEXPORT jlong JNICALL
Java_com_example_mobilestudio_NativeBridge_getFreeDiskBytes(
        JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    const char *path = jstr(env, jpath);
    if (!path) return -1L;
    struct statvfs sv;
    jlong free_bytes = -1L;
    if (statvfs(path, &sv) == 0)
        free_bytes = (jlong)sv.f_bfree * (jlong)sv.f_bsize;
    release_jstr(env, jpath, path);
    return free_bytes;
}

/* =========================================================
 * JNI_OnLoad
 * ========================================================= */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;

    /* Disable bionic heap tagging (MTE/TBI) immediately when the first
     * native library loads.  Android tagged pointers cause SIGABRT when
     * native code (llama.cpp, gradle_exec, etc.) truncates the tag byte.
     * This must run before ANY malloc in the main process. */
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0);

    LOGI("MobileStudio Native Library loaded (API %d, %s)",
         __ANDROID_API__,
         #if defined(__aarch64__)
                 "arm64-v8a"
#elif defined(__arm__)
            "armeabi-v7a"
#elif defined(__x86_64__)
            "x86_64"
#else
            "unknown"
#endif
    );
    return JNI_VERSION_1_6;
}
