/*
 * gradle_exec.c
 * ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�
 * libgradle_exec.so ??noexec/SELinux ?�약 ?�이 java ?�로?�스�??�행?�는 JNI ?�이브러�?
 *
 * ?�행 ?�략 (?�선?�위 ??:
 *   1. 직접 execve()          ??Termux java / 권한???�린 기기
 *   2. memfd_create + fexecve ??noexec ?�티???�전 ?�회 (Android 3.17+ 커널)
 *   3. codeCacheDir 복사      ??????방법 모두 ?�패 ??최후 ?�단
 *
 * JNI ?�터?�이??
 *   nativeStart(javaBin, codeCacheDir, args[], envp[], cwd) ??handle (jlong)
 *   nativeReadLine(handle) ??String | null (EOF)
 *   nativeWait(handle)     ??exit code
 *   nativeKill(handle)
 *   nativeFree(handle)
 *
 * 빌드:
 *   CMakeLists.txt ??add_library(gradle_exec SHARED gradle_exec.c) 추�?.
 * ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�
 */

#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <dlfcn.h>
#include <dirent.h>
#include <poll.h>
#include <malloc.h>
#include <locale.h>

/* M_BIONIC_SET_HEAP_TAGGING_LEVEL is in bionic's malloc.h but may not be
 * in older NDK headers.  Defined in <malloc.h> on API 28+. */
#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL 10000
#endif

/* ?�?� 로그 매크�??�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
#define TAG   "GradleExec_C"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/* ?�?� ?�들 구조�??�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
typedef struct {
    pid_t  pid;           /* ?�식 ?�로?�스 PID                           */
    int    pipe_read;     /* stdout+stderr ?�이???�기 ??               */
    int    java_memfd;    /* memfd ?�략 ?�용 ???�일 ?�스?�립??(-1=미사?? */
    char   line_buf[8192];/* readline 버퍼                               */
    int    buf_pos;       /* 버퍼 ???�재 ?�기 ?�치                      */
    int    buf_len;       /* 버퍼???�효??바이????                    */
    int    eof;           /* ?�이??EOF ?�달 ?��?                        */
} ExecHandle;

/* ?�?� ?�방 ?�언 ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
static int  load_file_to_memfd(const char *path);
static int  try_direct_execve (const char *java_bin,
                               char *const argv[], char *const envp[],
                               const char *cwd, pid_t *out_pid, int *out_pipe);
static int  try_memfd_fexecve (const char *java_bin,
                               char *const argv[], char *const envp[],
                               const char *cwd, pid_t *out_pid, int *out_pipe,
                               int *out_memfd);
static int  try_codecache_exec(const char *java_bin,
                               const char *code_cache_dir,
                               char *const argv[], char **envp,
                               const char *cwd, pid_t *out_pid, int *out_pipe);
static int  try_jli_launch(char *const argv[], char *const envp[],
                           const char *cwd, pid_t *out_pid, int *out_pipe);
static int  do_fork_exec(int exec_fd, const char *exec_path,
                         char *const argv[], char *const envp[],
                         const char *cwd, pid_t *out_pid, int *out_pipe);
static char *readline_from_pipe(ExecHandle *h);
static ExecHandle *handle_from_jlong(jlong j);
static int should_use_java_launcher(const char *java_bin);
static int should_try_jli_launch(const char *java_bin);

static char g_last_error[512] = "none";
static char g_error_trace[1024] = "";

static void set_last_errorf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_last_error, sizeof(g_last_error), fmt, ap);
    va_end(ap);
}

static void reset_error_trace(void) {
    g_last_error[0] = '\0';
    g_error_trace[0] = '\0';
}

static void append_error_trace(const char *label) {
    if (!label || !*label || !g_last_error[0]) return;
    size_t used = strlen(g_error_trace);
    if (used > 0 && used < sizeof(g_error_trace) - 3) {
        strncat(g_error_trace, " | ", sizeof(g_error_trace) - strlen(g_error_trace) - 1);
    }
    strncat(g_error_trace, label, sizeof(g_error_trace) - strlen(g_error_trace) - 1);
    strncat(g_error_trace, ": ", sizeof(g_error_trace) - strlen(g_error_trace) - 1);
    strncat(g_error_trace, g_last_error, sizeof(g_error_trace) - strlen(g_error_trace) - 1);
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   memfd_create ?�퍼 (bionic ???�는 경우 syscall 직접 ?�출)
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
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
    /* aarch64 ?� ??�� __NR_memfd_create 존재 (279) */
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

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   ?�일??memfd ??복사?�고 sealed fd 반환 (-1 = ?�패)
   memfd ??커널 ?�브?�트?�서 ?�떤 ?�티?�의 noexec ?�약??받�? ?�음.
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
static int load_file_to_memfd(const char *path) {
    int src = open(path, O_RDONLY | O_CLOEXEC);
    if (src < 0) {
        LOGE("load_file_to_memfd: open(%s) failed: %s", path, strerror(errno));
        set_last_errorf("load_file_to_memfd open failed: %s (errno=%d)", strerror(errno), errno);
        return -1;
    }

    /* memfd ?�성 */
    int mfd = create_executable_memfd("java_exec", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (mfd < 0) {
        LOGE("memfd_create failed: %s (errno=%d)", strerror(errno), errno);
        set_last_errorf("memfd_create failed: %s (errno=%d)", strerror(errno), errno);
        close(src);
        return -1;
    }

    /* ?�일 ?�기 ?�인 */
    struct stat st;
    if (fstat(src, &st) == 0 && st.st_size > 0) {
        /* 미리 ?�기 ?�당 (fallocate ?�으�?무시) */
        ftruncate(mfd, st.st_size);
    }

    /* ?�일 복사 */
    char buf[65536];
    ssize_t n;
    off_t total = 0;
    lseek(mfd, 0, SEEK_SET);
    while ((n = read(src, buf, sizeof(buf))) > 0) {
        ssize_t w = write(mfd, buf, (size_t)n);
        if (w != n) {
            LOGE("memfd write error: %s", strerror(errno));
            set_last_errorf("memfd write failed: %s (errno=%d)", strerror(errno), errno);
            close(src);
            close(mfd);
            return -1;
        }
        total += w;
    }
    close(src);
    LOGD("load_file_to_memfd: %s ??memfd(%d) %lld bytes", path, mfd, (long long)total);

    /* ?�링: 커널??execute 권한?????�게 ?�용 */
    fcntl(mfd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE | F_SEAL_SEAL);

    /* ?�행 ?�치 처음?�로 복�? */
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   fork + (execve ?�는 fexecve) 공통 ?�수
   exec_fd >= 0 ?�면 fexecve(exec_fd), ?�니�?execve(exec_path) ?�용.
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
static int do_fork_exec(int exec_fd, const char *exec_path,
                        char *const argv[], char *const envp[],
                        const char *cwd, pid_t *out_pid, int *out_pipe) {
    /* stdout/stderr ?�이??*/
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC) < 0) {
        LOGE("pipe2 failed: %s", strerror(errno));
        set_last_errorf("pipe2 failed: %s (errno=%d)", strerror(errno), errno);
        return -1;
    }

    /* exec ?�공 ?��? ?�기???�이??
     * exec ?�공 ??CLOEXEC �??�동 ?�힘 ??부모�? EOF ?�신 ???�공 ?�정.
     * exec ?�패 ???�식??errno �??�서 보냄 ??부모�? ?�음 ???�패 ?�정. */
    int syncfd[2];
    if (pipe2(syncfd, O_CLOEXEC) < 0) {
        LOGE("pipe2(sync) failed: %s", strerror(errno));
        set_last_errorf("pipe2(sync) failed: %s (errno=%d)", strerror(errno), errno);
        close(pipefd[0]); close(pipefd[1]);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        set_last_errorf("fork failed: %s (errno=%d)", strerror(errno), errno);
        close(pipefd[0]); close(pipefd[1]);
        close(syncfd[0]); close(syncfd[1]);
        return -1;
    }

    if (pid == 0) {
        /* ?�?� ?�식 ?�로?�스 ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
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

        /* exec ?�패: errno �?sync pipe ???�고 종료 */
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

    /* ?�?� 부�??�로?�스 ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
    close(syncfd[1]); /* ?�기 ???�기 */
    close(pipefd[1]);

    /* sync pipe ?�서 ?�기:
     *   EOF (n==0) ??exec ?�공 (CLOEXEC �??�동 ?�힘)
     *   n==4       ??exec ?�패, errno ?�신 */
    int child_errno = 0;
    ssize_t n = read(syncfd[0], &child_errno, sizeof(child_errno));
    close(syncfd[0]);

    if (n != 0) {
        /* exec ?�패 ???�식 ?�거 ???�패 반환 */
        int status;
        waitpid(pid, &status, 0);
        LOGE("do_fork_exec: exec failed errno=%d (%s), path=%s",
             child_errno, strerror(child_errno),
             exec_fd >= 0 ? "(memfd)" : exec_path);
        set_last_errorf("exec failed: strategy=%s errno=%d (%s) path=%s",
                        exec_fd >= 0 ? "memfd" : "direct",
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

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   ?�략 1: 직접 execve ??Termux java ?�는 권한???�린 경로
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
static int try_direct_execve(const char *java_bin,
                             char *const argv[], char *const envp[],
                             const char *cwd, pid_t *out_pid, int *out_pipe) {
    /* ?�행 가???��? 빠른 ?�인 (access() ??DAC �??�인, SELinux ?�외) */
    if (access(java_bin, X_OK) != 0) {
        LOGW("try_direct_execve: no X_OK on %s: %s", java_bin, strerror(errno));
        set_last_errorf("direct exec no X_OK: %s (errno=%d)", strerror(errno), errno);
        return -1;
    }
    LOGI("try_direct_execve: execve(%s)", java_bin);
    return do_fork_exec(-1, java_bin, argv, envp, cwd, out_pid, out_pipe);
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   ?�략 2: memfd_create + fexecve
   java 바이?�리�?커널 메모�?memfd)??복사 ??fexecve.
   memfd ???�떤 ?�티?�의 noexec ?�약??받�? ?�음.
   SELinux: untrusted_app ??memfd(tmpfs) execute ???�용??
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
static int try_memfd_fexecve(const char *java_bin,
                             char *const argv[], char *const envp[],
                             const char *cwd, pid_t *out_pid, int *out_pipe,
                             int *out_memfd) {
    LOGI("try_memfd_fexecve: loading %s into memfd", java_bin);
    int mfd = load_file_to_memfd(java_bin);
    if (mfd < 0) return -1;

    int ret = do_fork_exec(mfd, NULL, argv, envp, cwd, out_pid, out_pipe);
    if (ret == 0) {
        *out_memfd = mfd; /* 부모에???�들 ?�멸 ???�아????*/
    } else {
        close(mfd);
        *out_memfd = -1;
    }
    return ret;
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   ?�략 3: codeCacheDir ??java 복사 ??execve
   code_cache ??Android SELinux ?�서 execute ?�용 (ART JIT 캐시 ?�도).
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
static int try_codecache_exec(const char *java_bin,
                              const char *code_cache_dir,
                              char *const argv[], char **envp,
                              const char *cwd, pid_t *out_pid, int *out_pipe) {
    if (!code_cache_dir || !code_cache_dir[0]) return -1;

    /* codeCacheDir/jdk_exec/ ?�렉?�리 ?�성 */
    char exec_dir[1024];
    snprintf(exec_dir, sizeof(exec_dir), "%s/jdk_exec", code_cache_dir);
    mkdir(exec_dir, 0755);

    /* java 복사 */
    char dest[1024];
    snprintf(dest, sizeof(dest), "%s/java", exec_dir);

    /* ?��? ?�고 최신?�면 ?�사??*/
    struct stat src_st, dst_st;
    int need_copy = 1;
    if (stat(dest, &dst_st) == 0 && stat(java_bin, &src_st) == 0) {
        if (dst_st.st_mtime >= src_st.st_mtime && dst_st.st_size == src_st.st_size)
            need_copy = 0;
    }

    if (need_copy) {
        LOGI("try_codecache_exec: copying %s ??%s", java_bin, dest);
        int src_fd = open(java_bin, O_RDONLY);
        if (src_fd < 0) { LOGE("open src: %s", strerror(errno)); return -1; }
        int dst_fd = open(dest, O_WRONLY | O_CREAT | O_TRUNC, 0755);
        if (dst_fd < 0) {
            LOGE("open dst(%s): %s", dest, strerror(errno));
            set_last_errorf("codecache open dst failed: %s (errno=%d)", strerror(errno), errno);
            close(src_fd);
            return -1;
        }
        char buf[65536]; ssize_t n;
        while ((n = read(src_fd, buf, sizeof(buf))) > 0) write(dst_fd, buf, (size_t)n);
        close(src_fd);
        close(dst_fd);
        chmod(dest, 0755);
    }

    /* argv[0] �???경로�?교체 */
    /* argv ??변경하지 ?�고, ?�??exec_path �?바꿔???�행 */
    LOGI("try_codecache_exec: execve(%s)", dest);
    return do_fork_exec(-1, dest, argv, (char *const *)envp, cwd, out_pid, out_pipe);
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   ?�이?�에????�??�기 (개행 ?�함, null 종료)
   EOF �?NULL 반환.
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
typedef int (*JLI_Launch_t)(
        int argc,
        char **argv,
        int jargc,
        const char **jargv,
        int appclassc,
        const char **appclassv,
        const char *fullversion,
        const char *dotversion,
        const char *pname,
        const char *lname,
        jboolean javaargs,
        jboolean cpwildcard,
        jboolean javaw,
        jint ergo);

static const char *env_value(char *const envp[], const char *key) {
    if (!envp || !key) return NULL;
    size_t key_len = strlen(key);
    for (int i = 0; envp[i]; i++) {
        if (strncmp(envp[i], key, key_len) == 0 && envp[i][key_len] == '=') {
            return envp[i] + key_len + 1;
        }
    }
    return NULL;
}

static void apply_child_env(char *const envp[]) {
    if (!envp) return;
    for (int i = 0; envp[i]; i++) {
        putenv(strdup(envp[i]));
    }
}

static void prepend_jdk_ld_paths(const char *java_home) {
    if (!java_home || !*java_home) return;

    char required[3072];
    int required_len = snprintf(required, sizeof(required),
                                "%s/lib/server:%s/lib:%s/lib/jli",
                                java_home, java_home, java_home);
    if (required_len <= 0 || required_len >= (int)sizeof(required)) return;

    const char *old_path = getenv("LD_LIBRARY_PATH");
    size_t new_len = strlen(required) + (old_path && *old_path ? strlen(old_path) + 2 : 1);
    char *new_path = (char *)malloc(new_len);
    if (!new_path) return;

    if (old_path && *old_path) {
        snprintf(new_path, new_len, "%s:%s", required, old_path);
    } else {
        snprintf(new_path, new_len, "%s", required);
    }
    setenv("LD_LIBRARY_PATH", new_path, 1);
    free(new_path);
}

static void dlopen_optional(const char *path) {
    if (!path || !*path || access(path, R_OK) != 0) {
        LOGW("dlopen_optional: skipped (path=%s, accessible=%d)", path ? path : "null", path ? access(path, R_OK) : -1);
        return;
    }
    void *handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
    if (handle) {
        LOGI("preloaded: %s", path);
    } else {
        LOGW("preload failed: %s: %s", path, dlerror());
    }
}

static void preload_ld_preload_entries(char *const envp[]) {
    const char *preload = env_value(envp, "LD_PRELOAD");
    if (!preload || !*preload) return;

    char *copy = strdup(preload);
    if (!copy) return;

    char *saveptr = NULL;
    char *entry = strtok_r(copy, ":", &saveptr);
    while (entry) {
        if (*entry) dlopen_optional(entry);
        entry = strtok_r(NULL, ":", &saveptr);
    }
    free(copy);
}

/* Termux leftovers must never be preloaded: a corrupted libandroid-selinux.so
    (or a stray libpcre2 entry) makes EVERY child exec fail with
    "CANNOT LINK EXECUTABLE /system/bin/sh: library libpcre2-8.so not found".
    Mirrors the wrapper script's skip list (JreGradleInstaller wrapper v13);
    the files stay in lib/ and lib/server/ for DT_NEEDED resolution through
    LD_LIBRARY_PATH — they are just never put into LD_PRELOAD/dlopen'd here.

    libjsig.so is skipped for a different reason: JDK21's libjsig prints the
    signal-chaining deprecation warning to STDOUT (puts), which lands in
    `jlink --version` stdout and makes AGP's JavaVersion.toVersion fail with
    "Could not determine Java version from ... 21.0.4-internal" (JDK17's
    libjsig writes it to stderr — stdout stays clean, JDK17 builds pass).
    Nothing DT_NEEDs libjsig and HotSpot runs fine without it (measured). */
static int is_jdk_preload_skipped(const char *name) {
    if (strncmp(name, "libandroid-selinux", 18) == 0) return 1;
    if (strncmp(name, "libandroid-shmem", 16) == 0) return 1;
    if (strncmp(name, "libandroid-spawn", 16) == 0) return 1;
    if (strncmp(name, "libselinux", 10) == 0) return 1;
    if (strncmp(name, "libpcre2", 8) == 0) return 1;
    if (strncmp(name, "libjsig", 7) == 0) return 1;
    return 0;
}

static void preload_jdk_libs(const char *java_home) {
    /* Load thread_stub first to provide missing TLS symbols for JDK 21 */
    void *stub = dlopen("libthread_stub.so", RTLD_LAZY | RTLD_GLOBAL);
    if (stub) LOGI("preloaded thread_stub for JDK 21 TLS fix");
    else LOGW("thread_stub not found (OK if JDK 17): %s", dlerror());

    if (!java_home || !*java_home) {
        LOGW("preload_jdk_libs: java_home is null or empty");
        return;
    }

    LOGI("preload_jdk_libs: scanning JAVA_HOME=%s", java_home);

    /* ────────────────────────────────────────────────────────────────
     * STEP 1: Copy every lib/*.so into lib/server/ for linker namespace.
     *
     * Android linker namespace isolation: when JVM loads libjvm.so from
     * lib/server/, it creates child namespace clns-N whose search path
     * only includes lib/server/.  Libraries in lib/ (like libnet.so)
     * are invisible to that namespace, causing:
     *   dlopen failed: library "libnet.so" not found: needed by libnio.so
     *
     * IMPORTANT: Symlinks DON'T work here!  Bionic linker resolves the
     * symlink target path and checks if it's inside the namespace's
     * allowed directories.  lib/server/libnet.so -> ../libnet.so
     * resolves to lib/libnet.so which is OUTSIDE lib/server/ → rejected.
     *
     * Fix: hard-copy lib/X.so into lib/server/X.so so the child
     * namespace finds a real file inside its search path.
     * ──────────────────────────────────────────────────────────────── */
    {
        char lib_dir[1024];
        char server_dir[1024];
        snprintf(lib_dir, sizeof(lib_dir), "%s/lib", java_home);
        snprintf(server_dir, sizeof(server_dir), "%s/lib/server", java_home);

        mkdir(server_dir, 0755);

        DIR *dir = opendir(lib_dir);
        if (dir) {
            int copied = 0;
            struct dirent *ent;
            while ((ent = readdir(dir)) != NULL) {
                if (ent->d_type != DT_REG && ent->d_type != DT_UNKNOWN) continue;
                const char *name = ent->d_name;
                size_t nlen = strlen(name);
                if (nlen < 4 || strcmp(name + nlen - 3, ".so") != 0) continue;

                char server_path[1024];
                snprintf(server_path, sizeof(server_path), "%s/%s", server_dir, name);

                /* Remove broken symlinks from previous attempts */
                struct stat st;
                if (lstat(server_path, &st) == 0 && S_ISLNK(st.st_mode)) {
                    unlink(server_path);
                }

                /* Skip if real file already exists with same size */
                char lib_path[1024];
                snprintf(lib_path, sizeof(lib_path), "%s/%s", lib_dir, name);
                struct stat lst;
                if (stat(server_path, &st) == 0 && stat(lib_path, &lst) == 0
                    && st.st_size == lst.st_size) {
                    continue;
                }

                /* Hard copy: lib/X.so → lib/server/X.so */
                int src_fd = open(lib_path, O_RDONLY);
                int dst_fd = open(server_path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
                if (src_fd >= 0 && dst_fd >= 0) {
                    char buf[65536];
                    ssize_t n;
                    while ((n = read(src_fd, buf, sizeof(buf))) > 0)
                        write(dst_fd, buf, (size_t)n);
                    copied++;
                }
                if (src_fd >= 0) close(src_fd);
                if (dst_fd >= 0) close(dst_fd);
            }
            if (copied > 0)
                LOGI("preload_jdk_libs: copied %d .so files into lib/server/ for child namespace", copied);
        }
    }

    /* Build LD_PRELOAD list from all .so files in lib/ and lib/server/.
       This is MORE aggressive than just RTLD_GLOBAL dlopen — it also
       ensures the linker resolves inter-library deps at namespace create time.
       Android linker namespace isolation (clns-N) causes libio.so to not
       find libnet.so even when both are in lib/.  By listing every .so
       in LD_PRELOAD, the linker is forced to load them before any
       namespace child can request them. */
    /* Load libjvm.so and libjava.so FIRST to fix two issues:
     * 1. Alphabetical ordering: libattach.so/libextnet.so/libinstrument.so
     *    are loaded before libjava.so in readdir order. They have
     *    DT_NEEDED: libjava.so but can't find it in the linker namespace.
     *    Loading libjava.so first puts it in the global symbol table.
     * 2. Dual DSO: lib/libjava.so and lib/server/libjava.so are different
     *    files (different inodes) → loaded as separate DSOs with separate
     *    static fastEncoding. By loading lib/libjava.so first, the JVM's
     *    System.loadLibrary("java") finds it already loaded and reuses it. */
    {
        char jvm_path[1024], java_path[1024];
        snprintf(jvm_path, sizeof(jvm_path), "%s/lib/server/libjvm.so", java_home);
        snprintf(java_path, sizeof(java_path), "%s/lib/libjava.so", java_home);
        /* libjvm.so must come before libjava.so (libjava.so DT_NEEDED: libjvm.so) */
        dlopen_optional(jvm_path);
        dlopen_optional(java_path);
    }

    static const char *subdirs[] = { "lib", "lib/server", NULL };
    int preloaded_count = 0;

    /* First pass: collect all .so paths into a colon-separated string */
    char preload_buf[8192];
    preload_buf[0] = '\0';
    size_t preload_len = 0;

    for (int d = 0; subdirs[d]; d++) {
        char dirpath[1024];
        snprintf(dirpath, sizeof(dirpath), "%s/%s", java_home, subdirs[d]);
        DIR *dir = opendir(dirpath);
        if (!dir) {
            LOGW("preload_jdk_libs: cannot open dir %s: %s", dirpath, strerror(errno));
            continue;
        }
        struct dirent *ent;
        while ((ent = readdir(dir)) != NULL) {
            if (ent->d_type != DT_REG && ent->d_type != DT_UNKNOWN) continue;
            const char *name = ent->d_name;
            size_t nlen = strlen(name);
            if (nlen < 4 || strcmp(name + nlen - 3, ".so") != 0) continue;
            if (is_jdk_preload_skipped(name)) continue;
            char fullpath[1024];
            snprintf(fullpath, sizeof(fullpath), "%s/%s", dirpath, name);
            dlopen_optional(fullpath);
            preloaded_count++;

            /* Also append to LD_PRELOAD string */
            size_t fplen = strlen(fullpath);
            if (preload_len > 0 && preload_len + 1 + fplen < sizeof(preload_buf) - 1) {
                preload_buf[preload_len++] = ':';
            }
            if (preload_len + fplen < sizeof(preload_buf) - 1) {
                memcpy(preload_buf + preload_len, fullpath, fplen);
                preload_len += fplen;
            }
        }
        closedir(dir);
    }

    /* Set LD_PRELOAD so the linker can find JDK .so files across namespaces */
    if (preload_len > 0) {
        preload_buf[preload_len] = '\0';
        /* Merge with any existing LD_PRELOAD */
        const char *old_preload = getenv("LD_PRELOAD");
        if (old_preload && *old_preload) {
            size_t old_len = strlen(old_preload);
            size_t total = old_len + 1 + preload_len + 1;
            char *merged = (char *)malloc(total);
            if (merged) {
                snprintf(merged, total, "%s:%s", old_preload, preload_buf);
                setenv("LD_PRELOAD", merged, 1);
                free(merged);
            }
        } else {
            setenv("LD_PRELOAD", preload_buf, 1);
        }
        LOGI("preload_jdk_libs: set LD_PRELOAD with %d entries", preloaded_count);
    } else {
        LOGW("preload_jdk_libs: no .so files found in %s/lib/server", java_home);
    }
}

static void *open_jli_for_launch(const char *java_home) {
    char path[1024];
    if (java_home && *java_home) {
        snprintf(path, sizeof(path), "%s/lib/libjli.so", java_home);
        void *handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        if (handle) return handle;
        LOGW("dlopen(%s) failed: %s", path, dlerror());

        snprintf(path, sizeof(path), "%s/lib/jli/libjli.so", java_home);
        handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        if (handle) return handle;
        LOGW("dlopen(%s) failed: %s", path, dlerror());
    }
    void *handle = dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!handle) LOGW("dlopen(libjli.so) failed: %s", dlerror());
    return handle;
}

static int count_argv(char *const argv[]) {
    int count = 0;
    while (argv && argv[count]) count++;
    return count;
}

static int try_jli_launch(char *const argv[], char *const envp[],
                          const char *cwd, pid_t *out_pid, int *out_pipe) {
    int pipefd[2];
    if (pipe2(pipefd, O_CLOEXEC) < 0) {
        set_last_errorf("jli pipe2 failed: %s (errno=%d)", strerror(errno), errno);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        set_last_errorf("jli fork failed: %s (errno=%d)", strerror(errno), errno);
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    if (pid == 0) {
        close(pipefd[0]);
        if (dup2(pipefd[1], STDOUT_FILENO) < 0) _exit(126);
        if (dup2(pipefd[1], STDERR_FILENO) < 0) _exit(126);
        close(pipefd[1]);

        /* Disable bionic heap tagging BEFORE any dlopen/allocation.
         * Android's MTE/TBI tagged pointers cause SIGABRT when the JDK
         * truncates the tag byte.  mallopt must be called before ANY
         * malloc/dlopen in this child process. */
        mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0);

        const char *java_home = env_value(envp, "JAVA_HOME");
        const char *jli_debug = env_value(envp, "MOBILESTUDIO_JLI_DEBUG");
        apply_child_env(envp);
        prepend_jdk_ld_paths(java_home);
        if (jli_debug && strcmp(jli_debug, "1") == 0) {
            putenv(strdup("_JAVA_LAUNCHER_DEBUG=1"));
        }
        if (cwd && cwd[0]) {
            /* Ensure working directory exists (mkdir -p) so that JDK's
             * GetJavaProperties() -> getcwd() doesn't fail, which would
             * prevent InitializeEncoding() and cause:
             * "InternalError: platform encoding not initialized" */
            struct stat st;
            if (stat(cwd, &st) != 0) {
                char tmp[1024];
                snprintf(tmp, sizeof(tmp), "%s", cwd);
                for (char *p = tmp + 1; *p; p++) {
                    if (*p == '/') {
                        *p = '\0';
                        mkdir(tmp, 0755);
                        *p = '/';
                    }
                }
                mkdir(tmp, 0755);
                LOGI("created working directory: %s", cwd);
            }
            if (chdir(cwd) != 0) {
                LOGW("chdir(%s) failed: %s", cwd, strerror(errno));
            } else {
                char verify[1024];
                if (getcwd(verify, sizeof(verify)) == NULL) {
                    LOGW("getcwd() failed after chdir to %s: %s", cwd, strerror(errno));
                } else {
                    LOGI("chdir(%s) OK, getcwd=%s", cwd, verify);
                }
            }
        }

        int argc = count_argv(argv);
        if (jli_debug && strcmp(jli_debug, "1") == 0) {
            fprintf(stderr, "JLI_LAUNCH_START: JAVA_HOME=%s cwd=%s argc=%d argv0=%s\n",
                    java_home ? java_home : "(unset)",
                    cwd ? cwd : "(unset)",
                    argc,
                    (argc > 0 && argv[0]) ? argv[0] : "(null)");
            fprintf(stderr, "JLI_LD_LIBRARY_PATH=%s\n",
                    getenv("LD_LIBRARY_PATH") ? getenv("LD_LIBRARY_PATH") : "(unset)");
            for (int i = 0; i < argc; i++) {
                fprintf(stderr, "JLI_ARG[%d]=%s\n", i, argv[i] ? argv[i] : "(null)");
            }
            fflush(stderr);
        }

        preload_ld_preload_entries(envp);
        preload_jdk_libs(java_home);

        /* Initialize locale so JDK charset / platform encoding works.
         * glibc's libc.so can't be dlopen'd (needs ld-android.so), so the
         * JDK's native libraries resolve setlocale against bionic's libc.
         * Calling bionic's setlocale() here ensures the platform encoding is
         * initialized before JLI_Launch. Without this,
         * InetAddress.getAllByName() throws InternalError:
         * "platform encoding not initialized". */
        setlocale(LC_ALL, "C.UTF-8");
        setlocale(LC_CTYPE, "C.UTF-8");
        LOGI("setlocale(LC_ALL, C.UTF-8) called");

        /* Verify libnet.so was loaded - always log this for debugging */
        {
            char libnet_path[1024];
            snprintf(libnet_path, sizeof(libnet_path), "%s/lib/libnet.so", java_home);
            void *probe = dlopen(libnet_path, RTLD_LAZY);
            if (probe) {
                LOGI("libnet.so verified: dlopen(%s) OK", libnet_path);
                dlclose(probe);
            } else {
                LOGE("libnet.so MISSING: dlopen(%s) failed: %s", libnet_path, dlerror());
            }
        }

        void *jli = open_jli_for_launch(java_home);
        if (!jli) {
            fprintf(stderr, "JLI_LAUNCH_FAILED: libjli.so not found (JAVA_HOME=%s)\n",
                    java_home ? java_home : "(unset)");
            _exit(127);
        }

        JLI_Launch_t launch = (JLI_Launch_t)dlsym(jli, "JLI_Launch");
        if (!launch) {
            fprintf(stderr, "JLI_LAUNCH_FAILED: JLI_Launch missing: %s\n", dlerror());
            _exit(127);
        }

        /*
         * Rebind exec-family PLT slots of every loaded library to
         * libexec_redirect's interposers.  This client starts via
         * fork + JLI_Launch (no execve), so LD_PRELOAD was never
         * applied to this process, and the manual RTLD_GLOBAL dlopen
         * of libexec_redirect.so loses libjava.so's execve@LIBC
         * resolution to DT_NEEDED libc.  Without this, the Gradle
         * daemon spawn execve("/data/.../toolchain/jdk/bin/java")
         * bypasses the redirect and dies with EACCES (SELinux).
         */
        typedef int (*exec_rebind_fn)(void);
        exec_rebind_fn exec_rebind =
                (exec_rebind_fn)dlsym(RTLD_DEFAULT, "exec_redirect_rebind_exec_slots");
        if (exec_rebind) {
            int n = exec_rebind();
            LOGI("exec_redirect rebind: %d exec-family slots patched", n);
        } else {
            LOGW("exec_redirect_rebind_exec_slots not found: %s", dlerror());
        }

        /* Version: read from java binary path (e.g. jdk17-android vs jdk21-android).
         * Falls back to the rust-version property if path does not contain a hint. */
        const char *ver = "17.0"; /* safe default */
        if (java_home && strstr(java_home, "jdk21")) ver = "21.0";
        else if (java_home && strstr(java_home, "jdk17")) ver = "17.0";
        char ver_buf[32];
        snprintf(ver_buf, sizeof(ver_buf), "%s", ver);
        int code = launch(
                argc, argv,
                0, NULL,
                0, NULL,
                ver_buf, ver_buf,
                "java", "java",
                (jboolean)1,
                (jboolean)1,
                (jboolean)0,
                (jint)0);
        if (jli_debug && strcmp(jli_debug, "1") == 0) {
            fprintf(stderr, "JLI_LAUNCH_EXIT: code=%d\n", code);
            fflush(stderr);
        }
        _exit(code);
    }

    close(pipefd[1]);
    *out_pid = pid;
    *out_pipe = pipefd[0];
    LOGI("child pid=%d started with in-process JLI_Launch", pid);
    return 0;
}

static char *readline_from_pipe(ExecHandle *h) {
    if (h->eof && h->buf_pos >= h->buf_len) return NULL;

    int out_pos = 0;
    static char line[8192];

    while (1) {
        /* 버퍼?�서 개행 찾기 */
        while (h->buf_pos < h->buf_len) {
            char c = h->line_buf[h->buf_pos++];
            if (out_pos < (int)sizeof(line) - 1) line[out_pos++] = c;
            if (c == '\n') {
                line[out_pos] = '\0';
                return line;
            }
        }

        if (h->eof) {
            if (out_pos > 0) { line[out_pos] = '\0'; return line; }
            return NULL;
        }

        /* 버퍼 리필 */
        h->buf_pos = 0;
        ssize_t n = read(h->pipe_read, h->line_buf, sizeof(h->line_buf));
        if (n <= 0) {
            h->eof = 1;
            h->buf_len = 0;
            if (out_pos > 0) { line[out_pos] = '\0'; return line; }
            return NULL;
        }
        h->buf_len = (int)n;
    }
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   jlong ??ExecHandle* 변???�퍼
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
static ExecHandle *handle_from_jlong(jlong j) {
    return (ExecHandle *)(uintptr_t)(uint64_t)j;
}
static jlong jlong_from_handle(ExecHandle *h) {
    return (jlong)(uint64_t)(uintptr_t)h;
}

static int should_use_java_launcher(const char *java_bin) {
    return 0;
}

static const char *base_name_ptr(const char *path) {
    if (!path) return "";
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static int should_try_jli_launch(const char *java_bin) {
    const char *name = base_name_ptr(java_bin);
    return strcmp(name, "java") == 0 || strcmp(name, "java.real") == 0;
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   JNI: Java 문자??배열 ??C 문자??배열 변??
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
static char **jarray_to_c(JNIEnv *env, jobjectArray jarr, int *out_len) {
    int len = (int)(*env)->GetArrayLength(env, jarr);
    char **arr = (char **)calloc(len + 1, sizeof(char *));
    for (int i = 0; i < len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jarr, i);
        const char *s = (*env)->GetStringUTFChars(env, js, NULL);
        arr[i] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, js, s);
        (*env)->DeleteLocalRef(env, js);
    }
    arr[len] = NULL;
    *out_len = len;
    return arr;
}

static void free_c_array(char **arr, int len) {
    for (int i = 0; i < len; i++) free(arr[i]);
    free(arr);
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   JNI ?�수: nativeStart
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
JNIEXPORT jlong JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeStart(
        JNIEnv *env, jclass clazz,
        jstring java_bin_js,
        jstring code_cache_dir_js,
        jstring native_lib_dir_js,
        jobjectArray args_jarr,
        jobjectArray envp_jarr,
        jstring cwd_js) {

    const char *java_bin        = (*env)->GetStringUTFChars(env, java_bin_js, NULL);
    const char *code_cache_dir  = (*env)->GetStringUTFChars(env, code_cache_dir_js, NULL);
    const char *native_lib_dir  = (*env)->GetStringUTFChars(env, native_lib_dir_js, NULL);
    const char *cwd             = (*env)->GetStringUTFChars(env, cwd_js, NULL);

    int argc, envc;
    char **argv = jarray_to_c(env, args_jarr, &argc);
    char **envp = jarray_to_c(env, envp_jarr, &envc);

    LOGI("nativeStart: java=%s, cwd=%s, argc=%d, nlib=%s", java_bin, cwd, argc, native_lib_dir);
    reset_error_trace();

    /* Full launch record: exactly what the child will exec (daemon fork audit). */
    {
        char trace[4096];
        size_t off = 0;
        trace[0] = '\0';
        for (int i = 0; i < argc && off < sizeof(trace) - 8; i++) {
            int n = snprintf(trace + off, sizeof(trace) - off, "%s%s",
                             i ? " " : "", argv[i]);
            if (n < 0 || (size_t)n >= sizeof(trace) - off) break;
            off += (size_t)n;
        }
        LOGI("nativeStart argv(%d):%s", argc, trace);
    }
    for (int i = 0; i < envc; i++) {
        if (strncmp(envp[i], "JAVA_HOME=", 10) == 0 ||
            strncmp(envp[i], "PATH=", 5) == 0 ||
            strncmp(envp[i], "LD_PRELOAD=", 11) == 0 ||
            strncmp(envp[i], "MOBILESTUDIO_NATIVE_LIB_DIR=", 28) == 0 ||
            strncmp(envp[i], "MOBILESTUDIO_JAVA_LAUNCHER=", 27) == 0) {
            LOGI("nativeStart env: %s", envp[i]);
        }
    }

    /* ?�들 ?�당 */
    ExecHandle *h = (ExecHandle *)calloc(1, sizeof(ExecHandle));
    h->pipe_read  = -1;
    h->java_memfd = -1;
    h->eof        = 0;
    h->buf_pos    = 0;
    h->buf_len    = 0;

    int ret = -1;

    /* ?�?� ?�략 0: nativeLibraryDir/libjava_launcher.so ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�
     * APK ?�치 ???�스?�이 /data/app/.../lib/arm64/ ??exec 가?�하�?배치.
     *
     * libjava_launcher.so(=java 바이?�리)가 ?�행????링커??
     *   libjli.so ??libz.so.1
     *   libjvm.so ??libandroid-shmem.so, libandroid-spawn.so
     * �?찾아???�다. ???�일?��? nativeLibraryDir ??libz_v1.so,
     * libshmem.so, libspawn.so �??�함?�어 ?�다.
     *
     * execve ?????�로?�스??LD_LIBRARY_PATH �?envp ?�서 ?�으므�?
     * nativeLibraryDir ??LD_LIBRARY_PATH ???�함?�어 ?�어???�다.    */
    char launcher_path[512];
    snprintf(launcher_path, sizeof(launcher_path),
             "%s/libjava_launcher.so", native_lib_dir);
    if (should_use_java_launcher(java_bin) && access(launcher_path, X_OK) == 0) {
        LOGI("Strategy 0: nativeLibraryDir launcher (%s)", launcher_path);
        char *orig_argv0 = argv[0];
        argv[0] = launcher_path;
        ret = try_direct_execve(launcher_path, (char *const *)argv, (char *const *)envp,
                                cwd, &h->pid, &h->pipe_read);
        argv[0] = orig_argv0;
    }

    /* ?�?� ?�략 1: 직접 execve ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
    if (ret != 0 && should_try_jli_launch(java_bin)) {
        LOGI("Strategy 1: fork + JLI_Launch");
        ret = try_jli_launch((char *const *)argv, (char *const *)envp,
                             cwd, &h->pid, &h->pipe_read);
        if (ret != 0) append_error_trace("jli");
    } else if (ret != 0) {
        LOGI("Strategy 1 skipped: not a Java launcher (%s)", java_bin);
    }

    if (ret != 0) {
        LOGI("Strategy 2: direct execve");
        ret = try_direct_execve(java_bin, (char *const *)argv, (char *const *)envp,
                                cwd, &h->pid, &h->pipe_read);
        if (ret != 0) append_error_trace("direct");
    }

    /* ?�?� ?�략 2: memfd + fexecve ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
    if (ret != 0) {
        LOGI("direct failed, trying Strategy 3: memfd + fexecve");
        ret = try_memfd_fexecve(java_bin, (char *const *)argv, (char *const *)envp,
                                cwd, &h->pid, &h->pipe_read, &h->java_memfd);
        if (ret != 0) append_error_trace("memfd");
    }

    /* ?�?� ?�략 3: codeCacheDir 복사 ??execve ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?� */
    if (ret != 0) {
        LOGI("memfd failed, trying Strategy 4: codeCacheDir copy");
        ret = try_codecache_exec(java_bin, code_cache_dir,
                                 (char *const *)argv, envp,
                                 cwd, &h->pid, &h->pipe_read);
        if (ret != 0) append_error_trace("codecache");
    }

    /* ?�리 */
    (*env)->ReleaseStringUTFChars(env, java_bin_js, java_bin);
    (*env)->ReleaseStringUTFChars(env, code_cache_dir_js, code_cache_dir);
    (*env)->ReleaseStringUTFChars(env, native_lib_dir_js, native_lib_dir);
    (*env)->ReleaseStringUTFChars(env, cwd_js, cwd);
    free_c_array(argv, argc);
    free_c_array(envp, envc);

    if (ret != 0) {
        LOGE("All exec strategies failed");
        if (g_error_trace[0]) {
            snprintf(g_last_error, sizeof(g_last_error), "%s", g_error_trace);
        }
        free(h);
        return 0L;
    }

    return jlong_from_handle(h);
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   JNI ?�수: nativeReadLine ???�이?�에????�??�기, EOF �?null
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeReadLine(
        JNIEnv *env, jclass clazz, jlong handle) {
    ExecHandle *h = handle_from_jlong(handle);
    if (!h || h->pipe_read < 0) return NULL;

    char *line = readline_from_pipe(h);
    if (!line) return NULL;

    /* ?�행 개행 ?�거 */
    int len = (int)strlen(line);
    while (len > 0 && (line[len-1] == '\n' || line[len-1] == '\r')) line[--len] = '\0';

    return (*env)->NewStringUTF(env, line);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeReadLineTimeout(
        JNIEnv *env, jclass clazz, jlong handle, jint timeout_ms) {
    (void)clazz;
    ExecHandle *h = handle_from_jlong(handle);
    if (!h || h->pipe_read < 0) return NULL;

    /* 버퍼가 비어?�고 EOF가 ?�니�? poll()�?timeout ?�안 ?��?*/
    if (!h->eof && h->buf_pos >= h->buf_len && timeout_ms > 0) {
        struct pollfd pfd;
        memset(&pfd, 0, sizeof(pfd));
        pfd.fd = h->pipe_read;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, (int)timeout_ms);
        if (pr == 0) {
            return (*env)->NewStringUTF(env, "");
        }
        if (pr < 0) {
            if (errno == EINTR) return (*env)->NewStringUTF(env, "");
            /* ?�러???�기 쪽에??처리?�도�?fallthrough */
        }
    }

    char *line = readline_from_pipe(h);
    if (!line) return NULL;

    int len = (int)strlen(line);
    while (len > 0 && (line[len-1] == '\n' || line[len-1] == '\r')) line[--len] = '\0';
    return (*env)->NewStringUTF(env, line);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeReadChunkTimeout(
        JNIEnv *env, jclass clazz, jlong handle, jint timeout_ms) {
    (void)clazz;
    ExecHandle *h = handle_from_jlong(handle);
    if (!h || h->pipe_read < 0) return NULL;

    struct pollfd pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.fd = h->pipe_read;
    pfd.events = POLLIN;

    int pr = poll(&pfd, 1, timeout_ms > 0 ? (int)timeout_ms : 0);
    if (pr == 0) {
        return (*env)->NewStringUTF(env, "");
    }
    if (pr < 0) {
        if (errno == EINTR) return (*env)->NewStringUTF(env, "");
        /* fall through to read() and let it decide */
    }

    char buf[4096];
    ssize_t n = read(h->pipe_read, buf, sizeof(buf) - 1);
    if (n == 0) return NULL; /* EOF */
    if (n < 0) {
        if (errno == EINTR) return (*env)->NewStringUTF(env, "");
        return NULL;
    }
    buf[n] = '\0';
    return (*env)->NewStringUTF(env, buf);
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   JNI ?�수: nativeWait ???�로?�스 종료 ?��? 종료 코드 반환
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeWait(
        JNIEnv *env, jclass clazz, jlong handle) {
    ExecHandle *h = handle_from_jlong(handle);
    if (!h || h->pid <= 0) return -1;

    int status = 0;
    pid_t result;
    do {
        result = waitpid(h->pid, &status, 0);
    } while (result < 0 && errno == EINTR);

    if (result < 0) {
        LOGE("waitpid(%d) failed: %s", h->pid, strerror(errno));
        return -1;
    }

    h->pid = 0; /* ?��? ?�거??*/

    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        LOGI("process exited: code=%d", code);
        return code;
    }
    if (WIFSIGNALED(status)) {
        int sig = WTERMSIG(status);
        LOGI("process killed by signal %d", sig);
        return 128 + sig;
    }
    return -1;
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   JNI ?�수: nativeKill ???�로?�스 강제 종료
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeKill(
        JNIEnv *env, jclass clazz, jlong handle) {
    ExecHandle *h = handle_from_jlong(handle);
    if (!h || h->pid <= 0) return;
    LOGI("killing pid=%d", h->pid);
    kill(h->pid, SIGKILL);
/* 좀�?방�? ??비차???�거 */
    int status;
    waitpid(h->pid, &status, WNOHANG);
    h->pid = 0;
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   JNI ?�수: nativeFree ???�들 ?�제 (?�이???�기 + memfd ?�기 + free)
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeFree(
        JNIEnv *env, jclass clazz, jlong handle) {
    ExecHandle *h = handle_from_jlong(handle);
    if (!h) return;

    if (h->pid > 0) {
        kill(h->pid, SIGKILL);
        int st;
        waitpid(h->pid, &st, WNOHANG);
    }
    if (h->pipe_read >= 0) { close(h->pipe_read); h->pipe_read = -1; }
    if (h->java_memfd >= 0) { close(h->java_memfd); h->java_memfd = -1; }
    free(h);
    LOGD("handle freed");
}

/* ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━
   JNI ?�수: nativeCheckExecStrategy
   ?�떤 exec ?�략???�용 가?�한지 문자?�로 반환 (?�버�??�도)
   ?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━?�━ */
JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeCheckExecStrategy(
        JNIEnv *env, jclass clazz, jstring java_bin_js) {
    const char *java_bin = (*env)->GetStringUTFChars(env, java_bin_js, NULL);

    char result[256];
    int can_direct = (access(java_bin, X_OK) == 0) ? 1 : 0;
    int can_memfd  = (create_executable_memfd("test", MFD_CLOEXEC) >= 0) ? 1 : 0;

    snprintf(result, sizeof(result),
             "direct_execve=%s memfd_fexecve=%s",
             can_direct ? "YES" : "NO",
             can_memfd  ? "YES" : "NO");

    (*env)->ReleaseStringUTFChars(env, java_bin_js, java_bin);
    return (*env)->NewStringUTF(env, result);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_NativeExec_nativeGetLastError(
        JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, g_last_error);
}
