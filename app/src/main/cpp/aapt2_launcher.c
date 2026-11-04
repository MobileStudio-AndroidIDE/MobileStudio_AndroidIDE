/*
 * aapt2_launcher.c
 *
 * Runs bundled AAPT2 through memfd so Android never has to exec an app-private
 * /data/app or /data/data file directly.
 *
 * Expected invocation:
 *   /system/bin/linker64 libaapt2_launcher.so <real-aapt2-path> m
 */

#define _GNU_SOURCE

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define TAG "Aapt2Launcher"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern char **environ;

static int aapt2_launcher_main(int argc, char **argv);

__attribute__((noreturn, used))
void aapt2_launcher_start_c(uintptr_t *stack) {
    int argc = (int)stack[0];
    char **argv = (char **)(stack + 1);
    environ = argv + argc + 1;
    int code = aapt2_launcher_main(argc, argv);
    _exit(code);
}

__attribute__((naked, noreturn, visibility("default"), used))
void aapt2_launcher_start(void) {
    __asm__ volatile(
        "mov x0, sp\n"
        "b aapt2_launcher_start_c\n"
    );
}

#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef MFD_EXEC
#define MFD_EXEC 0x0010U
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS 1033
#define F_SEAL_SEAL   0x0001
#define F_SEAL_SHRINK 0x0002
#define F_SEAL_GROW   0x0004
#define F_SEAL_WRITE  0x0008
#endif

static int sys_memfd_create_compat(const char *name, unsigned int flags) {
#if defined(__NR_memfd_create)
    return (int)syscall(__NR_memfd_create, name, flags);
#else
    return (int)syscall(279, name, flags);
#endif
}

static int create_executable_memfd(const char *name) {
    int fd = sys_memfd_create_compat(name, MFD_ALLOW_SEALING | MFD_EXEC);
    if (fd >= 0) return fd;

    if (errno == EINVAL) {
        fd = sys_memfd_create_compat(name, MFD_ALLOW_SEALING);
    }
    return fd;
}

static int copy_to_memfd(const char *path) {
    int src = open(path, O_RDONLY | O_CLOEXEC);
    if (src < 0) {
        LOGE("open failed: %s: %s", path, strerror(errno));
        return -1;
    }

    int mfd = create_executable_memfd("mobilestudio_aapt2");
    if (mfd < 0) {
        LOGE("memfd_create failed: %s", strerror(errno));
        close(src);
        return -1;
    }

    struct stat st;
    if (fstat(src, &st) == 0 && st.st_size > 0) {
        (void)ftruncate(mfd, st.st_size);
    }

    char buffer[65536];
    while (1) {
        ssize_t read_count = read(src, buffer, sizeof(buffer));
        if (read_count == 0) break;
        if (read_count < 0) {
            LOGE("read failed: %s", strerror(errno));
            close(src);
            close(mfd);
            return -1;
        }

        char *cursor = buffer;
        ssize_t remaining = read_count;
        while (remaining > 0) {
            ssize_t written = write(mfd, cursor, (size_t)remaining);
            if (written <= 0) {
                LOGE("write failed: %s", strerror(errno));
                close(src);
                close(mfd);
                return -1;
            }
            cursor += written;
            remaining -= written;
        }
    }

    close(src);
    (void)fcntl(mfd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE | F_SEAL_SEAL);
    (void)lseek(mfd, 0, SEEK_SET);
    return mfd;
}

static uint64_t fnv1a64_update(uint64_t hash, const void *data, size_t len) {
    const unsigned char *bytes = (const unsigned char *)data;
    for (size_t i = 0; i < len; i++) {
        hash ^= (uint64_t)bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

static const char *basename_ptr(const char *path) {
    if (!path) return "tool";
    const char *slash = strrchr(path, '/');
    const char *base = slash ? slash + 1 : path;
    return base[0] ? base : "tool";
}

static void sanitize_name(const char *input, char *output, size_t output_size) {
    if (!output || output_size == 0) return;
    size_t written = 0;
    const char *name = basename_ptr(input);
    for (size_t i = 0; name[i] && written + 1 < output_size; i++) {
        char c = name[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
            output[written++] = c;
        } else {
            output[written++] = '_';
        }
    }
    if (written == 0 && output_size > 1) output[written++] = 't';
    output[written] = '\0';
}

static int copy_regular_file(const char *source, const char *dest, mode_t mode) {
    int src = open(source, O_RDONLY | O_CLOEXEC);
    if (src < 0) return -1;

    int out = open(dest, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (out < 0) {
        close(src);
        return -1;
    }

    char buffer[65536];
    int ok = 1;
    while (1) {
        ssize_t read_count = read(src, buffer, sizeof(buffer));
        if (read_count == 0) break;
        if (read_count < 0) {
            ok = 0;
            break;
        }
        char *cursor = buffer;
        ssize_t remaining = read_count;
        while (remaining > 0) {
            ssize_t written = write(out, cursor, (size_t)remaining);
            if (written <= 0) {
                ok = 0;
                break;
            }
            cursor += written;
            remaining -= written;
        }
        if (!ok) break;
    }

    if (fsync(out) != 0) {
        ok = 0;
    }
    close(out);
    close(src);
    if (!ok) {
        unlink(dest);
        return -1;
    }
    chmod(dest, mode);
    return 0;
}

static int ensure_exec_cache_copy(const char *tool_path, char *cached_path, size_t cached_path_size) {
    const char *cache_dir = getenv("MOBILESTUDIO_NATIVE_EXEC_DIR");
    if (!cache_dir || !cache_dir[0]) {
        cache_dir = "/data/user/0/com.aide.mobilestudio/code_cache/native_exec";
    }

    struct stat src_st;
    if (stat(tool_path, &src_st) != 0) {
        fprintf(stderr, "native_launcher: stat failed: %s: %s\n", tool_path, strerror(errno));
        return -1;
    }

    mkdir(cache_dir, 0700);

    uint64_t hash = 1469598103934665603ULL;
    hash = fnv1a64_update(hash, tool_path, strlen(tool_path));
    hash = fnv1a64_update(hash, &src_st.st_size, sizeof(src_st.st_size));
    hash = fnv1a64_update(hash, &src_st.st_mtime, sizeof(src_st.st_mtime));

    char safe_name[96];
    sanitize_name(tool_path, safe_name, sizeof(safe_name));
    snprintf(cached_path, cached_path_size, "%s/%s_%llx",
             cache_dir, safe_name, (unsigned long long)hash);

    struct stat dst_st;
    if (stat(cached_path, &dst_st) == 0 &&
        dst_st.st_size == src_st.st_size &&
        (dst_st.st_mode & S_IXUSR)) {
        return 0;
    }

    char temp_path[1024];
    snprintf(temp_path, sizeof(temp_path), "%s.tmp.%ld", cached_path, (long)getpid());
    unlink(temp_path);
    if (copy_regular_file(tool_path, temp_path, 0700) != 0) {
        fprintf(stderr, "native_launcher: cache copy failed: %s -> %s: %s\n",
                tool_path, temp_path, strerror(errno));
        return -1;
    }
    if (rename(temp_path, cached_path) != 0) {
        unlink(temp_path);
        fprintf(stderr, "native_launcher: cache rename failed: %s\n", strerror(errno));
        return -1;
    }
    chmod(cached_path, 0700);
    return 0;
}

static int exec_cached_copy(const char *tool_path, char **child_argv) {
    char cached_path[1024];
    if (ensure_exec_cache_copy(tool_path, cached_path, sizeof(cached_path)) != 0) {
        return 127;
    }
    execve(cached_path, child_argv, environ);
    int saved_errno = errno;
    fprintf(stderr, "native_launcher: exec cached copy failed: %s: errno=%d %s\n",
            cached_path, saved_errno, strerror(saved_errno));
    return 127;
}

static int has_suffix(const char *value, const char *suffix) {
    if (!value || !suffix) return 0;
    size_t value_len = strlen(value);
    size_t suffix_len = strlen(suffix);
    if (suffix_len > value_len) return 0;
    return strcmp(value + value_len - suffix_len, suffix) == 0;
}

static int is_launcher_arg(const char *arg) {
    return has_suffix(arg, "/linker64") ||
            has_suffix(arg, "/linker") ||
            has_suffix(arg, "/libaapt2_launcher.so");
}

static int aapt2_launcher_main(int argc, char **argv) {
    const char *env_aapt2_path = getenv("MOBILESTUDIO_AAPT2_EXEC");
    const char *aapt2_path = NULL;
    int first_aapt2_arg = 0;

    for (int i = 0; i < argc; i++) {
        if (!argv[i] || !argv[i][0]) continue;
        if (is_launcher_arg(argv[i])) {
            if (first_aapt2_arg == i) first_aapt2_arg = i + 1;
            continue;
        }
        if (argv[i][0] == '/') {
            aapt2_path = argv[i];
            first_aapt2_arg = i + 1;
            break;
        }
    }

    if ((!aapt2_path || !aapt2_path[0]) && env_aapt2_path && env_aapt2_path[0]) {
        aapt2_path = env_aapt2_path;
    }

    if (!aapt2_path || !aapt2_path[0]) {
        fprintf(stderr, "aapt2_launcher: missing aapt2 path (argc=%d)\n", argc);
        return 127;
    }

    int mfd = copy_to_memfd(aapt2_path);
    if (mfd < 0) {
        fprintf(stderr, "aapt2_launcher: failed to copy aapt2 to memfd: %s\n", aapt2_path);
        return 127;
    }

    int forwarded_argc = argc - first_aapt2_arg;
    int child_argc = forwarded_argc > 0 ? forwarded_argc + 1 : 2;
    char **child_argv = (char **)calloc((size_t)child_argc + 1, sizeof(char *));
    if (!child_argv) {
        close(mfd);
        return 127;
    }

    child_argv[0] = (char *)aapt2_path;
    if (forwarded_argc > 0) {
        for (int i = first_aapt2_arg; i < argc; i++) {
            child_argv[i - first_aapt2_arg + 1] = argv[i];
        }
    } else {
        child_argv[1] = "m";
    }
    child_argv[child_argc] = NULL;

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", mfd);
    execve(fd_path, child_argv, environ);

    int saved_errno = errno;
    LOGE("exec memfd failed: errno=%d %s", saved_errno, strerror(saved_errno));
    close(mfd);

    int cached_result = exec_cached_copy(aapt2_path, child_argv);
    if (cached_result != 0) {
        fprintf(stderr, "native_launcher: memfd fallback failed after errno=%d %s\n",
                saved_errno, strerror(saved_errno));
    }
    free(child_argv);
    return cached_result;
}

int main(int argc, char **argv) {
    return aapt2_launcher_main(argc, argv);
}
