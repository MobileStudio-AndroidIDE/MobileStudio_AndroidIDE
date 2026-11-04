/*
 * fork_loader.c
 *
 * Executes ARM64 ELF binaries on Android bypassing noexec/SELinux restrictions.
 * Packaged as libfork_loader.so in APK native lib dir (apk_data_file = exec allowed).
 *
 * Usage:
 *   glibc mode:   fork_loader <elf> <ld-linux> <target_argv...>
 *     run_argv = [ld.so, elf] + target_argv
 *     ld.so loads elf, elf gets argv = [elf] + target_argv
 *
 *   bionic mode:  fork_loader <elf> <linker64> <elf> <target_argv...>
 *     target_argv = &argv[3] becomes program argv
 *     linker64 loads elf, elf gets argv = [elf] + target_argv
 *
 * This mimics the JRE launcher pattern from gradle_exec.c.
 */

#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <dlfcn.h>
#include <poll.h>

#define TAG   "ForkLoader_C"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC      0x0001U
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef MFD_EXEC
#define MFD_EXEC 0x0010U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_SEAL_SHRINK 0x0002
#define F_SEAL_GROW   0x0004
#define F_SEAL_WRITE  0x0008
#define F_SEAL_SEAL   0x0001
#endif

static int sys_memfd_create(const char *name, unsigned int flags) {
#if defined(__NR_memfd_create)
    return (int)syscall(__NR_memfd_create, name, flags);
#else
    return (int)syscall(279, name, flags);
#endif
}

static int create_executable_memfd(const char *name, unsigned int flags) {
    int fd = sys_memfd_create(name, flags | MFD_EXEC);
    if (fd >= 0) return fd;
    if (errno == EINVAL) {
        fd = sys_memfd_create(name, flags);
    }
    return fd;
}

static int load_file_to_memfd(const char *path) {
    int src = open(path, O_RDONLY | O_CLOEXEC);
    if (src < 0) {
        LOGE("load_file_to_memfd: open(%s) failed: %s", path, strerror(errno));
        return -1;
    }

    int mfd = create_executable_memfd("fork_loader_elf", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (mfd < 0) {
        LOGE("memfd_create failed: %s (errno=%d)", strerror(errno), errno);
        close(src);
        return -1;
    }

    struct stat st;
    if (fstat(src, &st) == 0 && st.st_size > 0) {
        ftruncate(mfd, st.st_size);
    }

    char buf[65536];
    ssize_t n;
    lseek(mfd, 0, SEEK_SET);
    while ((n = read(src, buf, sizeof(buf))) > 0) {
        ssize_t w = write(mfd, buf, (size_t)n);
        if (w != n) {
            LOGE("memfd write error: %s", strerror(errno));
            close(src);
            close(mfd);
            return -1;
        }
    }
    close(src);
    LOGD("load_file_to_memfd: %s -> memfd(%d) %lld bytes", path, mfd, (long long)st.st_size);

    fcntl(mfd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE | F_SEAL_SEAL);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static int do_fork_exec(int exec_fd, const char *exec_path,
                        char *const argv[], char *const envp[],
                        const char *cwd, pid_t *out_pid, int *out_pipe) {
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC) < 0) {
        LOGE("pipe2 failed: %s", strerror(errno));
        return -1;
    }

    int syncfd[2];
    if (pipe2(syncfd, O_CLOEXEC) < 0) {
        LOGE("pipe2(sync) failed: %s", strerror(errno));
        close(pipefd[0]); close(pipefd[1]);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(pipefd[0]); close(pipefd[1]);
        close(syncfd[0]); close(syncfd[1]);
        return -1;
    }

    if (pid == 0) {
        close(syncfd[0]);
        close(pipefd[0]);
        if (dup2(pipefd[1], STDOUT_FILENO) < 0) _exit(126);
        if (dup2(pipefd[1], STDERR_FILENO) < 0) _exit(126);
        close(pipefd[1]);

        if (cwd && cwd[0]) chdir(cwd);

        if (exec_fd >= 0) {
            char fd_path[64];
            snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", exec_fd);
            execve(fd_path, argv, envp);
        } else {
            execve(exec_path, argv, envp);
        }

        int err = errno;
        write(syncfd[1], &err, sizeof(err));
        close(syncfd[1]);

        char msg[512];
        snprintf(msg, sizeof(msg),
                 "EXEC_FAILED: %s (errno=%d: %s)\n",
                 exec_fd >= 0 ? "(memfd)" : exec_path,
                 err, strerror(err));
        write(STDERR_FILENO, msg, strlen(msg));
        _exit(127);
    }

    close(syncfd[1]);
    close(pipefd[1]);

    int child_errno = 0;
    ssize_t n = read(syncfd[0], &child_errno, sizeof(child_errno));
    close(syncfd[0]);

    if (n != 0) {
        int status;
        waitpid(pid, &status, 0);
        LOGE("do_fork_exec: exec failed errno=%d (%s), path=%s",
             child_errno, strerror(child_errno),
             exec_fd >= 0 ? "(memfd)" : exec_path);
        close(pipefd[0]);
        return -1;
    }

    *out_pid  = pid;
    *out_pipe = pipefd[0];
    LOGI("child pid=%d started (exec_fd=%d, path=%s)", pid, exec_fd,
         exec_path ? exec_path : "(memfd)");
    return 0;
}

/* JNI: nativeRunForkLoader */
JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_ForkLoader_nativeRunForkLoader(
        JNIEnv *env, jclass clazz,
        jstring forkLoaderPath, jstring box64Path, jstring interpPath,
        jobjectArray targetArgv, jobjectArray envp, jstring cwd) {

    const char *fork_loader = (*env)->GetStringUTFChars(env, forkLoaderPath, NULL);
    const char *box64 = (*env)->GetStringUTFChars(env, box64Path, NULL);
    const char *interp = (*env)->GetStringUTFChars(env, interpPath, NULL);
    const char *cwd_str = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    int argc, envc;
    char **targv = NULL;
    char **eenv = NULL;

    if (targetArgv) {
        int len = (*env)->GetArrayLength(env, targetArgv);
        targv = (char **)calloc(len + 1, sizeof(char *));
        for (int i = 0; i < len; i++) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, targetArgv, i);
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            targv[i] = strdup(s);
            (*env)->ReleaseStringUTFChars(env, js, s);
            (*env)->DeleteLocalRef(env, js);
        }
        targv[len] = NULL;
        argc = len;
    }

    if (envp) {
        envc = (*env)->GetArrayLength(env, envp);
        eenv = (char **)calloc(envc + 1, sizeof(char *));
        for (int i = 0; i < envc; i++) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, envp, i);
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            eenv[i] = strdup(s);
            (*env)->ReleaseStringUTFChars(env, js, s);
            (*env)->DeleteLocalRef(env, js);
        }
        eenv[envc] = NULL;
    }

    /* Exec the loader directly. libfork_loader.so is a JNI shared object
     * (e_entry=0, no PT_INTERP) — exec'ing it as a program faults with
     * SIGILL, so the old "fork_loader <elf> <interp> ..." outer exec is gone.
     *   bionic (interp = linker64): execve(linker64, [linker64] + target)
     *       → program argv = target (callers put the ELF path first)
     *   glibc  (interp = ld.so):    execve(ld.so, [ld.so, box64] + target)
     *       → ld.so loads box64, box64 argv = [box64] + target
     * glibc ld.so lives in app data (exec denied by SELinux for
     * untrusted_app) → copy it into an executable memfd first, the same
     * strategy gradle_exec.c uses for the JDK launcher.
     */
    int bionic = (strstr(interp, "linker64") != NULL);
    int base = bionic ? 1 : 2;
    char **xargv = (char **)calloc((size_t)argc + base + 1, sizeof(char *));
    int n = 0;
    xargv[n++] = strdup(interp);
    if (!bionic) xargv[n++] = strdup(box64);
    for (int i = 0; i < argc; i++) {
        xargv[n++] = strdup(targv[i]);
    }
    xargv[n] = NULL;

    pid_t pid = 0;
    int pipefd = -1;
    int ret = -1;

    int mfd = bionic ? -1 : load_file_to_memfd(interp);
    if (mfd >= 0) {
        ret = do_fork_exec(mfd, NULL, xargv, eenv, cwd_str, &pid, &pipefd);
    } else {
        ret = do_fork_exec(-1, interp, xargv, eenv, cwd_str, &pid, &pipefd);
    }

    if (ret != 0) {
        for (int i = 0; i < n; i++) free(xargv[i]);
        free(xargv);
        if (targv) {
            for (int i = 0; i < argc; i++) free(targv[i]);
            free(targv);
        }
        if (eenv) {
            for (int i = 0; i < envc; i++) free(eenv[i]);
            free(eenv);
        }
        (*env)->ReleaseStringUTFChars(env, forkLoaderPath, fork_loader);
        (*env)->ReleaseStringUTFChars(env, box64Path, box64);
        (*env)->ReleaseStringUTFChars(env, interpPath, interp);
        if (cwd) (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);
        return -1;
    }

    char buf[4096];
    while (1) {
        ssize_t n = read(pipefd, buf, sizeof(buf) - 1);
        if (n <= 0) break;
        buf[n] = '\0';
        __android_log_write(ANDROID_LOG_INFO, TAG, buf);
    }
    close(pipefd);

    int status = 0;
    waitpid(pid, &status, 0);

    for (int i = 0; i < n; i++) free(xargv[i]);
    free(xargv);
    if (targv) {
        for (int i = 0; i < argc; i++) free(targv[i]);
        free(targv);
    }
    if (eenv) {
        for (int i = 0; i < envc; i++) free(eenv[i]);
        free(eenv);
    }

    (*env)->ReleaseStringUTFChars(env, forkLoaderPath, fork_loader);
    (*env)->ReleaseStringUTFChars(env, box64Path, box64);
    (*env)->ReleaseStringUTFChars(env, interpPath, interp);
    if (cwd) (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* JNI: nativeSelfTest */
JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_ForkLoader_nativeSelfTest(
        JNIEnv *env, jclass clazz, jstring forkLoaderPath) {

    const char *fork_loader = (*env)->GetStringUTFChars(env, forkLoaderPath, NULL);
    (void)fork_loader; /* kept for API compatibility; the .so itself is not exec'd */

    /* Validate the real launch route: execve(linker64, [linker64, echo, ...]),
     * the same way app-data bionic ELFs are started. exec'ing
     * libfork_loader.so as a program faults with SIGILL (e_entry=0). */
    char *argv[] = { (char*)"/system/bin/linker64", (char*)"/system/bin/echo", (char*)"FORK_LOADER_OK", NULL };
    char *eenv[] = { NULL };

    pid_t pid = 0;
    int pipefd = -1;
    int ret = do_fork_exec(-1, "/system/bin/linker64", argv, eenv, NULL, &pid, &pipefd);

    char output[1024] = {0};
    if (ret == 0 && pipefd >= 0) {
        char buf[512];
        while (1) {
            ssize_t n = read(pipefd, buf, sizeof(buf) - 1);
            if (n <= 0) break;
            buf[n] = '\0';
            strncat(output, buf, sizeof(output) - strlen(output) - 1);
        }
        close(pipefd);
    }

    int status = 0;
    if (pid > 0) waitpid(pid, &status, 0);

    (*env)->ReleaseStringUTFChars(env, forkLoaderPath, fork_loader);

    char result[1024];
    if (ret == 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0 && strstr(output, "FORK_LOADER_OK")) {
        snprintf(result, sizeof(result), "OK: fork_loader executed echo successfully");
    } else {
        snprintf(result, sizeof(result), "FAIL: exit=%d out=%s", WEXITSTATUS(status), output);
    }

    return (*env)->NewStringUTF(env, result);
}