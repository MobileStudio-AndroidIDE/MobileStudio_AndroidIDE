/*
 * mobilestudio_jli.c
 * Shared JLI_Launch bootstrap implementation.
 * Extracted from gradle_exec.c for reuse in NativeExec and ExecRedirect.
 */

#define _GNU_SOURCE

#include "mobilestudio_jli.h"

#include <jni.h>
#include <android/log.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <dlfcn.h>
#include <dirent.h>
#include <malloc.h>
#include <locale.h>

#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif
#ifndef M_HEAP_TAGGING_LEVEL_TBI
#define M_HEAP_TAGGING_LEVEL_TBI 2
#endif
/* bionic's actual mallopt option for heap tagging (malloc.h, API 31+). */
#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-105)
#endif

#define TAG   "MS_JLI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

typedef int (*JLI_Launch_t)(
        int argc, char **argv,
        int jargc, const char **jargv,
        int appclassc, const char **appclassv,
        const char *fullversion, const char *dotversion,
        const char *pname, const char *lname,
        int javaargs, int cpwildcard, int javaw, int ergo);

/* ---- internal helpers (static) ---- */

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

static void dlopen_optional(const char *path) {
    if (!path || !*path || access(path, R_OK) != 0) {
        LOGW("dlopen_optional: skipped (path=%s)", path ? path : "null");
        return;
    }
    void *handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
    if (handle) LOGI("preloaded: %s", path);
    else LOGW("preload failed: %s: %s", path, dlerror());
}

static void open_jli_for_launch_impl(const char *java_home, void **out_handle) {
    *out_handle = NULL;
    char path[1024];
    if (java_home && *java_home) {
        snprintf(path, sizeof(path), "%s/lib/libjli.so", java_home);
        *out_handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        if (*out_handle) return;
        LOGW("dlopen(%s) failed: %s", path, dlerror());
        snprintf(path, sizeof(path), "%s/lib/jli/libjli.so", java_home);
        *out_handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
        if (*out_handle) return;
        LOGW("dlopen(%s) failed: %s", path, dlerror());
    }
    *out_handle = dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL);
    if (!*out_handle) LOGW("dlopen(libjli.so) failed: %s", dlerror());
}

/* ---- public API implementations ---- */

int mobilestudio_jli_disable_heap_tagging(void) {
    /* Disable bionic heap tagging (TBI). With tagging on, stored pointers with
     * tag bits get truncated by the kernel -> "Pointer tag ... was truncated"
     * -> abort. This killed the Gradle daemon fork. mallopt(-105, 0) = NONE. */
    int r1 = mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE);
    /* Old fallback value (harmless no-op if unsupported) */
    mallopt(10000, 0);
    return r1;
}

int mobilestudio_jli_resolve_java_home(const char *java_bin, char *const envp[],
                                        char *out_buf, size_t out_buf_size) {
    if (!java_bin || !java_bin[0] || !out_buf || out_buf_size == 0) return -1;
    const char *java_home = env_value((char *const *)envp, "JAVA_HOME");
    if (java_home && *java_home) {
        strncpy(out_buf, java_home, out_buf_size - 1);
        out_buf[out_buf_size - 1] = '\0';
        return 0;
    }
    char path[1024];
    strncpy(path, java_bin, sizeof(path) - 1);
    path[sizeof(path) - 1] = '\0';
    char *slash = strrchr(path, '/');
    if (!slash) return -1;
    *slash = '\0';
    slash = strrchr(path, '/');
    if (!slash) return -1;
    *slash = '\0';
    strncpy(out_buf, path, out_buf_size - 1);
    out_buf[out_buf_size - 1] = '\0';
    return 0;
}

void mobilestudio_jli_apply_child_env(char *const envp[]) {
    if (!envp) return;
    for (int i = 0; envp[i]; i++) putenv(strdup(envp[i]));
}

void mobilestudio_jli_prepend_ld_paths(const char *java_home) {
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
    if (old_path && *old_path) snprintf(new_path, new_len, "%s:%s", required, old_path);
    else snprintf(new_path, new_len, "%s", required);
    setenv("LD_LIBRARY_PATH", new_path, 1);
    free(new_path);
}

void *mobilestudio_jli_open_libjli(const char *java_home) {
    void *handle = NULL;
    open_jli_for_launch_impl(java_home, &handle);
    return handle;
}

/* Mirrors gradle_exec.c is_jdk_preload_skipped() / the wrapper script's skip
   list (JreGradleInstaller wrapper v13): Termux leftovers whose deps are not
   in the JDK break the whole exec ("CANNOT LINK EXECUTABLE"), and libjsig.so
   prints its signal-chaining deprecation warning to STDOUT on JDK21 (puts),
   which pollutes `jlink --version` stdout and makes AGP's
   JavaVersion.toVersion fail with "Could not determine Java version from ...
   21.0.4-internal" (JDK17's libjsig writes it to stderr — stdout stays
   clean). Nothing DT_NEEDs libjsig and HotSpot runs fine without it. */
static int is_jdk_preload_skipped(const char *name) {
    if (strncmp(name, "libandroid-selinux", 18) == 0) return 1;
    if (strncmp(name, "libandroid-shmem", 16) == 0) return 1;
    if (strncmp(name, "libandroid-spawn", 16) == 0) return 1;
    if (strncmp(name, "libselinux", 10) == 0) return 1;
    if (strncmp(name, "libpcre2", 8) == 0) return 1;
    if (strncmp(name, "libjsig", 7) == 0) return 1;
    return 0;
}

int mobilestudio_jli_preload_libs(const char *java_home) {
    if (!java_home || !*java_home) {
        LOGW("mobilestudio_jli_preload_libs: java_home is null or empty");
        return -1;
    }
    LOGI("mobilestudio_jli_preload_libs: scanning JAVA_HOME=%s", java_home);

    /* Copy .so files from lib/ into lib/server/ for child namespace isolation */
    {
        char lib_dir[1024], server_dir[1024];
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
                struct stat st;
                if (lstat(server_path, &st) == 0 && S_ISLNK(st.st_mode)) unlink(server_path);
                char lib_path[1024];
                snprintf(lib_path, sizeof(lib_path), "%s/%s", lib_dir, name);
                struct stat lst;
                if (stat(server_path, &st) == 0 && stat(lib_path, &lst) == 0 && st.st_size == lst.st_size) continue;
                int src_fd = open(lib_path, O_RDONLY);
                int dst_fd = open(server_path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
                if (src_fd >= 0 && dst_fd >= 0) {
                    char buf[65536];
                    ssize_t n;
                    while ((n = read(src_fd, buf, sizeof(buf))) > 0) write(dst_fd, buf, (size_t)n);
                    copied++;
                }
                if (src_fd >= 0) close(src_fd);
                if (dst_fd >= 0) close(dst_fd);
            }
            closedir(dir);
            if (copied > 0) LOGI("mobilestudio_jli_preload_libs: copied %d .so files into lib/server/", copied);
        }
    }

    /* Load libjvm.so and libjava.so FIRST to fix alphabetical ordering:
     * libattach.so/libextnet.so/libinstrument.so are loaded before libjava.so
     * in readdir order, causing "library libjava.so not found" in linker namespace. */
    {
        char jvm_path[1024], java_path[1024];
        snprintf(jvm_path, sizeof(jvm_path), "%s/lib/server/libjvm.so", java_home);
        snprintf(java_path, sizeof(java_path), "%s/lib/libjava.so", java_home);
        dlopen_optional(jvm_path);
        dlopen_optional(java_path);
    }

    static const char *subdirs[] = { "lib", "lib/server", NULL };
    int preloaded_count = 0;
    char preload_buf[8192];
    preload_buf[0] = '\0';
    size_t preload_len = 0;

    for (int d = 0; subdirs[d]; d++) {
        char dirpath[1024];
        snprintf(dirpath, sizeof(dirpath), "%s/%s", java_home, subdirs[d]);
        DIR *dir = opendir(dirpath);
        if (!dir) {
            LOGW("mobilestudio_jli_preload_libs: cannot open dir %s: %s", dirpath, strerror(errno));
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
            size_t fplen = strlen(fullpath);
            if (preload_len > 0 && preload_len + 1 + fplen < sizeof(preload_buf) - 1)
                preload_buf[preload_len++] = ':';
            if (preload_len + fplen < sizeof(preload_buf) - 1) {
                memcpy(preload_buf + preload_len, fullpath, fplen);
                preload_len += fplen;
            }
        }
        closedir(dir);
    }

    if (preload_len > 0) {
        preload_buf[preload_len] = '\0';
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
        LOGI("mobilestudio_jli_preload_libs: set LD_PRELOAD with %d entries", preloaded_count);
    } else {
        LOGW("mobilestudio_jli_preload_libs: no .so files found in %s/lib or %s/lib/server", java_home, java_home);
        return -1;
    }
    return 0;
}

/* ---- core JLI_Launch (in-process, no execve) ---- */

static int jli_launch_core(const char *java_home,
                            char *const argv[],
                            char *const envp[],
                            const char *cwd) {
    LOGI("jli_launch_core: java_home=%s", java_home ? java_home : "(null)");

    if (cwd && chdir(cwd) != 0) {
        LOGW("jli_launch_core: chdir(%s) failed: %s", cwd, strerror(errno));
    }

    void *jli_handle = NULL;
    open_jli_for_launch_impl(java_home, &jli_handle);
    if (!jli_handle) {
        LOGE("jli_launch_core: failed to open libjli.so");
        return -1;
    }

    JLI_Launch_t jli_launch_fn = (JLI_Launch_t)dlsym(jli_handle, "JLI_Launch");
    if (!jli_launch_fn) {
        LOGE("jli_launch_core: dlsym(JLI_Launch) failed: %s", dlerror());
        return -1;
    }

    if (envp) mobilestudio_jli_apply_child_env((char *const *)envp);
    mobilestudio_jli_prepend_ld_paths(java_home);

    /* Initialize locale so JDK charset / platform encoding works.
     * glibc's libc.so can't be dlopen'd (needs ld-android.so), so the
     * JDK's native libraries resolve setlocale against bionic's libc.
     * Calling bionic's setlocale() here ensures the platform encoding is
     * initialized before JLI_Launch. */
    setlocale(LC_ALL, "C.UTF-8");
    setlocale(LC_CTYPE, "C.UTF-8");
    LOGI("setlocale(LC_ALL, C.UTF-8) called");

    /* Build argc/argv from the original argv */
    int argc = 0;
    if (argv) { while (argv[argc]) argc++; }

    /* JLI_Launch expects mutable argv */
    char **mutable_argv = (char **)malloc(sizeof(char *) * (argc + 1));
    if (!mutable_argv) {
        LOGE("jli_launch_core: malloc failed");
        return -1;
    }
    for (int i = 0; i < argc; i++) mutable_argv[i] = strdup(argv[i]);
    mutable_argv[argc] = NULL;

    LOGI("jli_launch_core: calling JLI_Launch with %d args", argc);

    int result = jli_launch_fn(
        argc, mutable_argv,
        0, NULL,
        0, NULL,
        NULL, NULL,
        "java", "OpenJDK Runtime Environment",
        0, 0, 0, 0);

    LOGI("jli_launch_core: JLI_Launch returned %d", result);
    return result;
}

/* ---- public API ---- */

int mobilestudio_jli_launch_in_process(const char *java_bin,
                                        char *const argv[],
                                        char *const envp[],
                                        const char *cwd) {
    char java_home[1024];
    if (mobilestudio_jli_resolve_java_home(java_bin, (char *const *)envp,
                                            java_home, sizeof(java_home)) != 0) {
        LOGE("mobilestudio_jli_launch_in_process: failed to resolve JAVA_HOME");
        return -1;
    }
    return mobilestudio_jli_launch_in_process_with_home(java_home, argv, envp, cwd);
}

int mobilestudio_jli_launch_in_process_with_home(const char *java_home,
                                                  char *const argv[],
                                                  char *const envp[],
                                                  const char *cwd) {
    return jli_launch_core(java_home, argv, envp, cwd);
}
