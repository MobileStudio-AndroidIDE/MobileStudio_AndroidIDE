#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <linux/memfd.h>
#include <pthread.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "elf_parse.h"
#include "lib_resolver.h"

static int is_arm64_elf(const char *path);

#define TAG "ToolLauncher"

#define LOGI(fmt, ...) fprintf(stderr, "[%s] " fmt "\n", TAG, ##__VA_ARGS__)
#define LOGE(fmt, ...) fprintf(stderr, "[%s] ERROR " fmt "\n", TAG, ##__VA_ARGS__)

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif
#ifndef MFD_ALLOW_SEALING
#define MFD_ALLOW_SEALING 0x0002U
#endif
#ifndef MFD_EXEC
#define MFD_EXEC 0x0010U
#endif
#ifndef F_LINUX_SPECIFIC_BASE
#define F_LINUX_SPECIFIC_BASE 1024
#endif
#ifndef F_ADD_SEALS
#define F_ADD_SEALS (F_LINUX_SPECIFIC_BASE + 9)
#endif
#ifndef F_SEAL_SEAL
#define F_SEAL_SEAL 0x0001
#endif
#ifndef F_SEAL_SHRINK
#define F_SEAL_SHRINK 0x0002
#endif
#ifndef F_SEAL_GROW
#define F_SEAL_GROW 0x0004
#endif
#ifndef F_SEAL_WRITE
#define F_SEAL_WRITE 0x0008
#endif
#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif
#ifndef __NR_memfd_create
#define __NR_memfd_create 279
#endif
#ifndef __NR_execveat
#define __NR_execveat 281
#endif

extern char **environ;

static const char *toolchain_root = NULL;
static pthread_once_t toolchain_init_once = PTHREAD_ONCE_INIT;

static void init_toolchain_root(void) {
    const char *env = getenv("TOOLCHAIN_ROOT");
    if (env && *env) {
        toolchain_root = env;
        return;
    }
    const char *home = getenv("HOME");
    if (home) {
        static char path[512];
        snprintf(path, sizeof(path), "%s/toolchain", home);
        toolchain_root = path;
    } else {
        toolchain_root = "/data/user/0/com.aide.mobilestudio/files/toolchain";
    }
}

static const char *get_toolchain_root(void) {
    pthread_once(&toolchain_init_once, init_toolchain_root);
    return toolchain_root;
}

static int syscall_memfd_create(const char *name, unsigned int flags) {
    return (int)syscall(__NR_memfd_create, name, flags);
}

static int syscall_execveat(int dirfd, const char *pathname, char *const argv[], char *const envp[], int flags) {
    return (int)syscall(__NR_execveat, dirfd, pathname, argv, envp, flags);
}

static int levenshtein_distance(const char *a, const char *b) {
    int la = strlen(a), lb = strlen(b);
    if (la == 0) return lb;
    if (lb == 0) return la;
    
    int *prev = calloc(lb + 1, sizeof(int));
    int *curr = calloc(lb + 1, sizeof(int));
    if (!prev || !curr) { free(prev); free(curr); return la + lb; }
    
    for (int j = 0; j <= lb; j++) prev[j] = j;
    
    for (int i = 1; i <= la; i++) {
        curr[0] = i;
        for (int j = 1; j <= lb; j++) {
            int cost = (a[i-1] == b[j-1]) ? 0 : 1;
            int del = prev[j] + 1;
            int ins = curr[j-1] + 1;
            int sub = prev[j-1] + cost;
            curr[j] = (del < ins) ? (del < sub ? del : sub) : (ins < sub ? ins : sub);
        }
        int *tmp = prev; prev = curr; curr = tmp;
    }
    
    int result = prev[lb];
    free(prev); free(curr);
    return result;
}

static void suggest_corrections(const char *input) {
    const char *builtins[] = {
        "clear", "cls", "exit", "quit", "help", "pwd", "cd", "ls", "ll", "la",
        "cp", "mv", "rm", "mkdir", "rmdir", "touch", "cat", "less", "more",
        "grep", "find", "which", "whereis", "type", "alias", "unalias",
        "export", "unset", "env", "set", "history", "jobs", "fg", "bg",
        "kill", "ps", "top", "df", "du", "free", "uptime", "date", "whoami",
        "chmod", "chown", "ln", "tar", "gzip", "gunzip", "zip", "unzip",
        "ssh", "scp", "rsync", "curl", "wget", "git", "clang", "clang++",
        "java", "javac", "ninja", "cmake", "make", "python", "python3",
        "pip", "npm", "node", "adb", "logcat", "dmesg", "strace", "ltrace"
    };
    const int n_builtins = sizeof(builtins) / sizeof(builtins[0]);
    
    int best_dist = 100;
    const char *best = NULL;
    
    for (int i = 0; i < n_builtins; i++) {
        int dist = levenshtein_distance(input, builtins[i]);
        if (dist < best_dist && dist <= 2) {
            best_dist = dist;
            best = builtins[i];
        }
    }
    
    if (best) {
        fprintf(stderr, "\033[91mCommand \"%s\" not found. Did you mean \"%s\"?\033[0m\n", input, best);
    } else {
        fprintf(stderr, "\033[91mCommand \"%s\" not found\033[0m\n", input);
    }
}

static int copy_to_memfd(const char *path) {
    int src = open(path, O_RDONLY | O_CLOEXEC);
    if (src < 0) {
        LOGE("open failed: %s (%s)", path, strerror(errno));
        return -1;
    }

    int mfd = syscall_memfd_create("tool_launcher", MFD_CLOEXEC | MFD_EXEC | MFD_ALLOW_SEALING);
    if (mfd < 0 && errno == EINVAL) {
        mfd = syscall_memfd_create("tool_launcher", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    }
    if (mfd < 0) {
        mfd = syscall_memfd_create("tool_launcher", MFD_CLOEXEC);
    }
    if (mfd < 0) {
        LOGE("memfd_create failed: %s", strerror(errno));
        close(src);
        return -1;
    }

    LOGI("memfd: created fd=%d for %s", mfd, path);

    char buf[65536];
    ssize_t n;
    while ((n = read(src, buf, sizeof(buf))) > 0) {
        char *p = buf;
        ssize_t left = n;
        while (left > 0) {
            ssize_t w = write(mfd, p, (size_t)left);
            if (w <= 0) {
                LOGE("write to memfd failed: %s", strerror(errno));
                close(src);
                close(mfd);
                return -1;
            }
            p += w;
            left -= w;
        }
    }
    close(src);

    fcntl(mfd, F_ADD_SEALS, F_SEAL_WRITE | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL);
    return mfd;
}

static int find_tool_binary(const char *tool_name, char *out_path, size_t path_sz) {
    const char *search_dirs[] = {
        "%s/runtime-tools/bin",
        "%s/llvm/bin",
        "%s/ndk/toolchains/llvm/prebuilt/linux-arm64/bin",
        "%s/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin",
        "/data/user/0/com.aide.mobilestudio/files/toolchain/llvm/bin",
        "/data/user/0/com.aide.mobilestudio/files/toolchain/runtime-tools/bin",
        "/data/user/0/com.aide.mobilestudio/files/tools/bin",
        "/data/user/0/com.aide.mobilestudio/files/tools",
        "/system/bin",
        "/system/xbin",
        NULL
    };

    const char *root = get_toolchain_root();
    char path_buf[512];

    // First pass: exact match in priority directories
    for (int i = 0; search_dirs[i]; i++) {
        snprintf(path_buf, sizeof(path_buf), search_dirs[i], root);
        DIR *dir = opendir(path_buf);
        if (dir) {
            struct dirent *entry;
            while ((entry = readdir(dir))) {
                if (strcmp(entry->d_name, tool_name) == 0) {
                    snprintf(out_path, path_sz, "%s/%s", path_buf, tool_name);
                    if (access(out_path, X_OK) == 0 && is_arm64_elf(out_path)) {
                        closedir(dir);
                        LOGI("Found tool: %s", out_path);
                        return 0;
                    }
                }
            }
            closedir(dir);
        }
    }

    // Second pass: try formatted paths
    const char *formatted_dirs[] = {
        "%s/runtime-tools/bin/%s",
        "%s/llvm/bin/%s",
        "%s/ndk/toolchains/llvm/prebuilt/linux-arm64/bin/%s",
        "%s/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/%s",
        "/data/user/0/com.aide.mobilestudio/files/toolchain/llvm/bin/%s",
        "/data/user/0/com.aide.mobilestudio/files/toolchain/runtime-tools/bin/%s",
        "/data/user/0/com.aide.mobilestudio/files/tools/%s/bin/%s",
        "/system/bin/%s",
        "/system/xbin/%s",
        NULL
    };

    for (int i = 0; formatted_dirs[i]; i++) {
        snprintf(out_path, path_sz, formatted_dirs[i], root, tool_name);
        if (access(out_path, X_OK) == 0 && is_arm64_elf(out_path)) {
            LOGI("Found tool: %s", out_path);
            return 0;
        }
    }

    // Third pass: search PATH
    const char *path_env = getenv("PATH");
    if (path_env) {
        char *path_dup = strdup(path_env);
        if (path_dup) {
            char *save = NULL;
            char *dir = strtok_r(path_dup, ":", &save);
            while (dir) {
                snprintf(out_path, path_sz, "%s/%s", dir, tool_name);
                if (access(out_path, X_OK) == 0 && is_arm64_elf(out_path)) {
                    free(path_dup);
                    LOGI("Found in PATH: %s", out_path);
                    return 0;
                }
                dir = strtok_r(NULL, ":", &save);
            }
            free(path_dup);
        }
    }

    return -ENOENT;
}

static int is_arm64_elf(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    
    unsigned char hdr[64];
    ssize_t n = read(fd, hdr, sizeof(hdr));
    close(fd);
    
    if (n < 64) return 0;
    
    // ELF magic
    if (hdr[0] != 0x7f || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F') return 0;
    // 64-bit
    if (hdr[4] != 2) return 0;
    // Little endian
    if (hdr[5] != 1) return 0;
    // ARM64 (EM_AARCH64 = 183 = 0xB7)
    uint16_t machine = hdr[18] | (hdr[19] << 8);
    if (machine != 0xB7) return 0;
    
    return 1;
}

static int resolve_dependencies(const char *binary_path, char *ld_lib_path, size_t path_sz, char *out_err, size_t err_sz) {
    char **needed = NULL;
    size_t needed_count = 0;

    int ret = elf_parse_needed(binary_path, &needed, &needed_count);
    if (ret < 0) {
        if (out_err) snprintf(out_err, err_sz, "Failed to parse ELF dependencies: %s", strerror(-ret));
        return ret;
    }
    if (needed_count == 0) {
        elf_free_needed(needed, needed_count);
        return 0;
    }

    ld_lib_path[0] = '\0';
    size_t pos = 0;

    const char *ld_env = getenv("LD_LIBRARY_PATH");
    if (ld_env && *ld_env) {
        pos += snprintf(ld_lib_path + pos, path_sz - pos, "%s:", ld_env);
    }

    for (size_t i = 0; i < needed_count; i++) {
        char resolved[512];
        char build_id[41] = {0};

        elf_get_build_id(needed[i], build_id, sizeof(build_id));

        char resolved_path[512];
        char lib_err[512];
        int r = lib_resolve_with_build_id(needed[i], build_id[0] ? build_id : NULL, resolved_path, sizeof(resolved_path), lib_err, sizeof(lib_err));
        if (r < 0) {
            if (out_err) {
                snprintf(out_err, err_sz, "Failed to resolve %s: %s", needed[i], lib_err);
            }
            elf_free_needed(needed, needed_count);
            return -ENOENT;
        }

        char *dir = dirname(resolved_path);
        if (dir && *dir) {
            int found = 0;
            char *p = ld_lib_path;
            while (*p) {
                char *next = strchr(p, ':');
                if (!next) next = p + strlen(p);
                if (next - p == (int)strlen(dir) && strncmp(p, dir, next - p) == 0) {
                    found = 1;
                    break;
                }
                if (*next == ':') next++;
                p = next;
            }
            if (!found) {
                if (pos + strlen(dir) + 2 < path_sz) {
                    pos += snprintf(ld_lib_path + pos, path_sz - pos, "%s:", dir);
                }
            }
        }
    }

    if (pos > 0 && ld_lib_path[pos - 1] == ':') {
        ld_lib_path[pos - 1] = '\0';
    }

    elf_free_needed(needed, needed_count);
    return 0;
}

static char **build_env(const char *ld_lib_path, char *const envp[]) {
    int count = 0;
    while (envp && envp[count]) count++;

    char **new_env = calloc(count + 4, sizeof(char *));
    if (!new_env) return NULL;

    int idx = 0;
    for (int i = 0; i < count; i++) {
        if (strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0) continue;
        if (strncmp(envp[i], "LD_PRELOAD=", 11) == 0) continue;
        new_env[idx++] = envp[i];
    }

    if (ld_lib_path && *ld_lib_path) {
        size_t len = strlen("LD_LIBRARY_PATH=") + strlen(ld_lib_path) + 1;
        char *entry = malloc(len);
        if (entry) {
            snprintf(entry, len, "LD_LIBRARY_PATH=%s", ld_lib_path);
            new_env[idx++] = entry;
        }
    }

    const char *self_path = NULL;
    Dl_info info;
    if (dladdr((void *)build_env, &info) && info.dli_fname) {
        self_path = info.dli_fname;
    }
    if (self_path) {
        size_t len = strlen("LD_PRELOAD=") + strlen(self_path) + 1;
        char *entry = malloc(len);
        if (entry) {
            snprintf(entry, len, "LD_PRELOAD=%s", self_path);
            new_env[idx++] = entry;
        }
    }

    new_env[idx] = NULL;
    return new_env;
}

static void free_env(char **env) {
    if (!env) return;
    for (int i = 0; env[i]; i++) {
        if (strncmp(env[i], "LD_LIBRARY_PATH=", 16) == 0 || strncmp(env[i], "LD_PRELOAD=", 11) == 0) {
            free(env[i]);
        }
    }
    free(env);
}

int launcher_main(int argc, char **argv, char **envp) {
    if (argc < 1) return 127;

    const char *tool_name = strrchr(argv[0], '/');
    tool_name = tool_name ? tool_name + 1 : argv[0];

    LOGI("Launching %s", tool_name);

    char binary_path[512];
    if (find_tool_binary(tool_name, binary_path, sizeof(binary_path)) < 0) {
        LOGE("Tool not found: %s", tool_name);
        suggest_corrections(tool_name);
        return 127;
    }

    LOGI("Found binary: %s", binary_path);

    lib_resolver_init(getenv("LIB_CACHE_DIR"));

    char ld_lib_path[4096];
    char err[1024];
    if (resolve_dependencies(binary_path, ld_lib_path, sizeof(ld_lib_path), err, sizeof(err)) < 0) {
        LOGE("Dependency resolution failed: %s", err);
        lib_resolver_cleanup();
        return 127;
    }

    LOGI("LD_LIBRARY_PATH: %s", ld_lib_path);

    char **new_env = build_env(ld_lib_path, (char *const *)envp);
    if (!new_env) {
        LOGE("Failed to build environment");
        lib_resolver_cleanup();
        return 127;
    }

    int mfd = copy_to_memfd(binary_path);
    if (mfd < 0) {
        free_env(new_env);
        lib_resolver_cleanup();
        return 126;
    }

    int ret = syscall_execveat(mfd, "", argv, (char *const *)new_env, AT_EMPTY_PATH);
    int saved_errno = errno;

    if (ret < 0 && (saved_errno == ENOSYS || saved_errno == EINVAL || saved_errno == EPERM)) {
        char fd_path[64];
        snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", mfd);
        LOGI("execveat failed (%s), trying /proc/self/fd", strerror(saved_errno));
        ret = execve(fd_path, argv, (char *const *)new_env);
        saved_errno = errno;
    }

    free_env(new_env);
    lib_resolver_cleanup();
    close(mfd);

    if (ret < 0) {
        LOGE("Execution failed for %s: %s", binary_path, strerror(saved_errno));
        return 126;
    }

    return ret;
}

int main(int argc, char **argv, char **envp) {
    return launcher_main(argc, argv, envp);
}