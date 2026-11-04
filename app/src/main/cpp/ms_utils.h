#pragma once
/**
 * ms_utils.h
 *
 * mobilestudio_native.c shared utility helpers.
 */

#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <limits.h>
#include <android/log.h>

#define MS_TAG "MobileStudioNative"
#define MS_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  MS_TAG, __VA_ARGS__)
#define MS_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  MS_TAG, __VA_ARGS__)
#define MS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MS_TAG, __VA_ARGS__)

/* ── mkdir -p ────────────────────────────────────────────── */
static inline void ms_mkdir_p(const char *path) {
    char tmp[PATH_MAX];
    strncpy(tmp, path, sizeof(tmp) - 1);
    tmp[sizeof(tmp)-1] = '\0';
    size_t len = strlen(tmp);
    if (len > 0 && tmp[len-1] == '/') tmp[len-1] = '\0';
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = '\0'; mkdir(tmp, 0755); *p = '/'; }
    }
    mkdir(tmp, 0755);
}

/* ── fexecve 대체 (/proc/self/fd/<n> 경로로 execve) ─────── */
/* fexecve 는 Android NDK API 28 미만 / ISO C99 모드에서 미선언.
   /proc/self/fd/<n> 은 child 에서 "self" = child 자신이므로
   fork 직후 exec 전에 사용하면 항상 유효함.                  */
static inline int ms_fexecve(int fd, char *const argv[], char *const envp[]) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    execve(path, argv, envp);
    return -1;
}

/* ── memfd_create (noexec 우회용 in-memory fd) ───────────── */
/* MFD_CLOEXEC 없이 생성: Java 로 fd 반환 후 exec 까지 살아있어야 함 */
static inline int ms_memfd_open(const char *name) {
#if defined(__NR_memfd_create)
    int fd = (int)syscall(__NR_memfd_create, name, 0);
    if (fd >= 0) return fd;
    MS_LOGW("ms_memfd_open: memfd_create failed (%s)", strerror(errno));
#endif
    return -1;
}

/* ── 바이너리를 memfd 에 로드 (noexec 우회 실행 준비) ───── */
static inline int ms_load_to_memfd(const char *path) {
    int src = open(path, O_RDONLY);
    if (src < 0) return -1;

    struct stat st;
    fstat(src, &st);

    int mfd = ms_memfd_open("ms_exec");
    if (mfd < 0) { close(src); return -1; }

    ftruncate(mfd, st.st_size);
    char buf[65536];
    ssize_t n;
    while ((n = read(src, buf, sizeof(buf))) > 0)
        write(mfd, buf, (size_t)n);
    close(src);
    lseek(mfd, 0, SEEK_SET);
    return mfd;
}

/* ── SessionHandle (legacy process session support) ─────────── */
typedef struct {
    pid_t pid;
    int   stdout_fd;
    int   stdin_fd;
} MsSessionHandle;

/* ── envp 에 특정 KEY= 존재 여부 확인 ───────────────────── */
static inline int ms_envp_has_key(char **envp, const char *key) {
    if (!envp) return 0;
    size_t klen = strlen(key);
    for (int i = 0; envp[i]; i++) {
        if (strncmp(envp[i], key, klen) == 0 && envp[i][klen] == '=') return 1;
    }
    return 0;
}
