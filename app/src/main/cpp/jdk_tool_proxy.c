/*
 * Executable .so wrapper for JDK tools that cannot be execve'd directly from
 * the app-private toolchain path. It is launched as:
 *
 *   /system/bin/linker64 libjdk_tool_proxy.so /path/to/bin/jlink ...
 *
 * and re-enters the bundled JDK through libjli.so in-process.
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <malloc.h>
#include <locale.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL 10000
#endif
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TAG "JdkToolProxy"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef uint8_t jboolean;
typedef int32_t jint;

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

extern char **environ;

static int jdk_tool_proxy_main(int argc, char **argv);

__attribute__((noreturn, used))
void jdk_tool_proxy_start_c(uintptr_t *stack) {
    int argc = (int)stack[0];
    char **argv = (char **)(stack + 1);
    environ = argv + argc + 1;
    int code = jdk_tool_proxy_main(argc, argv);
    _exit(code);
}

__attribute__((naked, noreturn, visibility("default"), used))
void jdk_tool_proxy_start(void) {
    __asm__ volatile(
        "mov x0, sp\n"
        "b jdk_tool_proxy_start_c\n"
    );
}

static int has_suffix(const char *value, const char *suffix) {
    if (!value || !suffix) return 0;
    size_t value_len = strlen(value);
    size_t suffix_len = strlen(suffix);
    if (suffix_len > value_len) return 0;
    return strcmp(value + value_len - suffix_len, suffix) == 0;
}

static const char *basename_ptr(const char *path) {
    if (!path) return "";
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static int is_launcher_arg(const char *arg) {
    return has_suffix(arg, "/linker64") ||
            has_suffix(arg, "/linker") ||
            has_suffix(arg, "/libjdk_tool_proxy.so");
}

static const char *tool_module(const char *tool_name) {
    if (strcmp(tool_name, "javac") == 0) return "jdk.compiler/com.sun.tools.javac.Main";
    if (strcmp(tool_name, "jlink") == 0) return "jdk.jlink/jdk.tools.jlink.internal.Main";
    if (strcmp(tool_name, "jmod") == 0) return "jdk.jlink/jdk.tools.jmod.Main";
    if (strcmp(tool_name, "jdeps") == 0) return "jdk.jdeps/com.sun.tools.jdeps.Main";
    return NULL;
}

static const char *tool_main_class(const char *tool_name) {
    return NULL;
}

static int is_native_build_tool_name(const char *tool_name) {
    if (!tool_name) return 0;
    size_t len = strlen(tool_name);
    return strcmp(tool_name, "cmake") == 0 ||
            strcmp(tool_name, "ninja") == 0 ||
            strcmp(tool_name, "ctest") == 0 ||
            strcmp(tool_name, "cpack") == 0 ||
            strcmp(tool_name, "clang") == 0 ||
            strcmp(tool_name, "clang++") == 0 ||
            strcmp(tool_name, "ld.lld") == 0 ||
            strcmp(tool_name, "lld") == 0 ||
            strcmp(tool_name, "llvm-ar") == 0 ||
            strcmp(tool_name, "llvm-ranlib") == 0 ||
            strcmp(tool_name, "llvm-strip") == 0 ||
            strcmp(tool_name, "llvm-objcopy") == 0 ||
            strcmp(tool_name, "llvm-readelf") == 0 ||
            strcmp(tool_name, "llvm-nm") == 0 ||
            strcmp(tool_name, "llvm-objdump") == 0 ||
            strcmp(tool_name, "llvm-addr2line") == 0 ||
            (len >= 6 && strcmp(tool_name + len - 6, "-clang") == 0) ||
            (len >= 8 && strcmp(tool_name + len - 8, "-clang++") == 0);
}

static char *join_path3(const char *left, const char *middle, const char *right) {
    size_t len = strlen(left) + strlen(middle) + strlen(right) + 3;
    char *out = (char *)calloc(len, 1);
    if (!out) return NULL;
    snprintf(out, len, "%s/%s/%s", left, middle, right);
    return out;
}

static int launch_native_build_tool(const char *tool_path, const char *tool_name, char **tool_args, int tool_argc) {
    const char *real_cmake_dir = getenv("MOBILESTUDIO_REAL_CMAKE_DIR");
    char *real_tool_alloc = NULL;
    const char *real_tool = tool_path;
    if (real_cmake_dir && real_cmake_dir[0]) {
        real_tool_alloc = join_path3(real_cmake_dir, "bin", tool_name);
        if (real_tool_alloc) real_tool = real_tool_alloc;
    }

    const char *shim_script = getenv("MOBILESTUDIO_NATIVE_TOOL_SHELL");
    if (shim_script && shim_script[0]) {
        int argc = 3 + tool_argc;
        char **exec_argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
        if (!exec_argv) {
            free(real_tool_alloc);
            return 127;
        }
        int index = 0;
        exec_argv[index++] = (char *)"/system/bin/sh";
        exec_argv[index++] = (char *)shim_script;
        exec_argv[index++] = (char *)real_tool;
        for (int i = 0; i < tool_argc; i++) {
            exec_argv[index++] = tool_args[i];
        }
        exec_argv[index] = NULL;
        unsetenv("LD_PRELOAD");
        execv("/system/bin/sh", exec_argv);
        fprintf(stderr, "native_tool_proxy: exec shell shim failed\n");
        free(exec_argv);
        free(real_tool_alloc);
        return 127;
    }

    const char *apk_path = getenv("MOBILESTUDIO_APK_PATH");
    if (!apk_path || !apk_path[0]) {
        fprintf(stderr, "native_tool_proxy: MOBILESTUDIO_APK_PATH is not set\n");
        free(real_tool_alloc);
        return 127;
    }
    setenv("CLASSPATH", apk_path, 1);

    const char *app_process = access("/system/bin/app_process64", X_OK) == 0
            ? "/system/bin/app_process64"
            : "/system/bin/app_process";
    const char *native_lib_dir = getenv("MOBILESTUDIO_NATIVE_LIB_DIR");
    char clean_ld_library_path[1024];
    if (native_lib_dir && native_lib_dir[0]) {
        snprintf(clean_ld_library_path, sizeof(clean_ld_library_path),
                 "%s:/system/lib64:/system/lib", native_lib_dir);
    } else {
        snprintf(clean_ld_library_path, sizeof(clean_ld_library_path),
                 "/system/lib64:/system/lib");
    }
    unsetenv("LD_PRELOAD");
    setenv("LD_LIBRARY_PATH", clean_ld_library_path, 1);
    setenv("ANDROID_ROOT", "/system", 1);
    setenv("ANDROID_DATA", "/data", 1);

    int argc = 6 + tool_argc;
    char **exec_argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    if (!exec_argv) {
        free(real_tool_alloc);
        return 127;
    }

    int index = 0;
    exec_argv[index++] = (char *)app_process;
    exec_argv[index++] = (char *)"/system/bin";
    exec_argv[index++] = (char *)"--application";
    exec_argv[index++] = (char *)"--nice-name=mobilestudio_native_tool_proxy";
    exec_argv[index++] = (char *)"com.aide.mobilestudio.NativeBuildToolShim";
    exec_argv[index++] = (char *)real_tool;
    for (int i = 0; i < tool_argc; i++) {
        exec_argv[index++] = tool_args[i];
    }
    exec_argv[index] = NULL;

    execv(app_process, exec_argv);
    fprintf(stderr, "native_tool_proxy: exec app_process failed\n");
    free(exec_argv);
    free(real_tool_alloc);
    return 127;
}

static void dlopen_optional(const char *path) {
    if (!path || !path[0]) return;
    void *handle = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
    if (handle) {
        LOGI("preloaded: %s", path);
    } else {
        LOGI("preload failed: %s: %s", path, dlerror());
    }
}

static void preload_jdk_deps(void) {
    const char *java_home = getenv("JAVA_HOME");
    if (!java_home || !java_home[0]) return;

    /* Dynamically scan lib/ and lib/server/ and preload ALL .so files
       with RTLD_GLOBAL. Same rationale as gradle_exec.c: prevents Android
       linker namespace isolation from blocking cross-directory .so resolution
       (e.g. libjvm.so in lib/server/ can't find libnet.so in lib/). */
    /* Load libjvm.so and libjava.so FIRST to fix alphabetical ordering issue */
    {
        char jvm_path[1024], java_path[1024];
        snprintf(jvm_path, sizeof(jvm_path), "%s/lib/server/libjvm.so", java_home);
        snprintf(java_path, sizeof(java_path), "%s/lib/libjava.so", java_home);
        void *stub = dlopen("libthread_stub.so", RTLD_LAZY | RTLD_GLOBAL);
        (void)stub;
        void *h1 = dlopen(jvm_path, RTLD_LAZY | RTLD_GLOBAL);
        if (h1) LOGI("preload: %s", jvm_path); else LOGE("preload failed: %s: %s", jvm_path, dlerror());
        void *h2 = dlopen(java_path, RTLD_LAZY | RTLD_GLOBAL);
        if (h2) LOGI("preload: %s", java_path); else LOGE("preload failed: %s: %s", java_path, dlerror());
    }

    static const char *subdirs[] = { "lib", "lib/server", NULL };
    for (int d = 0; subdirs[d]; d++) {
        char dirpath[1024];
        snprintf(dirpath, sizeof(dirpath), "%s/%s", java_home, subdirs[d]);
        DIR *dir = opendir(dirpath);
        if (!dir) continue;
        struct dirent *ent;
        while ((ent = readdir(dir)) != NULL) {
            if (ent->d_type != DT_REG && ent->d_type != DT_UNKNOWN) continue;
            const char *name = ent->d_name;
            size_t nlen = strlen(name);
            if (nlen < 4 || strcmp(name + nlen - 3, ".so") != 0) continue;
            char fullpath[1024];
            snprintf(fullpath, sizeof(fullpath), "%s/%s", dirpath, name);
            dlopen_optional(fullpath);
        }
        closedir(dir);
    }
}

static void *open_jli(void) {
    /* Load thread_stub first for JDK 21 TLS fix */
    dlopen("libthread_stub.so", RTLD_LAZY | RTLD_GLOBAL);

    preload_jdk_deps();
    const char *java_home = getenv("JAVA_HOME");
    char java_home_jli[512];
    char java_home_lib[512];
    if (java_home && java_home[0]) {
        snprintf(java_home_jli, sizeof(java_home_jli), "%s/lib/jli/libjli.so", java_home);
        snprintf(java_home_lib, sizeof(java_home_lib), "%s/lib/libjli.so", java_home);
    } else {
        java_home_jli[0] = '\0';
        java_home_lib[0] = '\0';
    }

    const char *candidates[] = {
            "libjli.so",
            java_home_jli,
            java_home_lib,
            "/data/data/com.aide.mobilestudio/files/toolchain/jdk/lib/jvm/java-17-openjdk/lib/jli/libjli.so",
            "/data/data/com.aide.mobilestudio/files/toolchain/jdk/lib/jvm/java-17-openjdk/lib/libjli.so",
            NULL
    };

    for (int i = 0; candidates[i] != NULL; i++) {
        if (!candidates[i][0]) continue;
        void *handle = dlopen(candidates[i], RTLD_LAZY | RTLD_GLOBAL);
        if (handle) {
            LOGI("libjli.so loaded from: %s", candidates[i]);
            return handle;
        }
        LOGI("dlopen(%s) failed: %s", candidates[i], dlerror());
    }
    return NULL;
}


static void get_jdk_version(char *fullversion, size_t full_size,
                            char *dotversion, size_t dot_size) {
    snprintf(fullversion, full_size, "unknown");
    snprintf(dotversion, dot_size, "unknown");

    const char *java_home = getenv("JAVA_HOME");
    if (!java_home || !java_home[0]) {
        LOGI("JAVA_HOME is not set");
        return;
    }

    char release_path[1024];
    snprintf(release_path, sizeof(release_path),
             "%s/release", java_home);

    FILE *fp = fopen(release_path, "r");
    if (!fp) {
        LOGI("cannot open JDK release file: %s", release_path);
        return;
    }

    char line[2048];

    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "JAVA_VERSION=", 13) != 0)
            continue;

        char *value = line + 13;

        if (*value == '"') {
            value++;
            char *end = strrchr(value, '"');
            if (end)
                *end = '\0';
        }

        value[strcspn(value, "\r\n")] = '\0';

        if (value[0]) {
            snprintf(fullversion, full_size, "%s", value);

            const char *p = strchr(value, '.');

            if (p) {
                p = strchr(p + 1, '.');

                if (p) {
                    size_t len = (size_t)(p - value);

                    if (len >= dot_size)
                        len = dot_size - 1;

                    memcpy(dotversion, value, len);
                    dotversion[len] = '\0';
                } else {
                    snprintf(dotversion, dot_size, "%s", value);
                }
            } else {
                snprintf(dotversion, dot_size, "%s", value);
            }
        }

        break;
    }

    fclose(fp);

    LOGI("detected JDK version: full=%s dot=%s",
         fullversion, dotversion);
}

static int launch_jli(const char *tool_name, const char *module, const char *main_class,
                      char **tool_args, int tool_argc) {
    void *jli = open_jli();
    if (!jli) {
        fprintf(stderr, "jdk_tool_proxy: failed to load libjli.so\n");
        return 127;
    }

    JLI_Launch_t launch = (JLI_Launch_t)dlsym(jli, "JLI_Launch");
    if (!launch) {
        fprintf(stderr, "jdk_tool_proxy: missing JLI_Launch: %s\n", dlerror());
        return 127;
    }

    setlocale(LC_ALL, "C.UTF-8");
    setlocale(LC_CTYPE, "C.UTF-8");

    char fullversion[128];
    char dotversion[64];
    get_jdk_version(fullversion, sizeof(fullversion),
                    dotversion, sizeof(dotversion));

    /*
     * JDK tool launchers use JAVA_ARGS (jargc/jargv) for the
     * launcher-level arguments. These MUST NOT be passed through
     * appclassv, otherwise JLI can interpret javac options such as
     * --system=none as JVM options.
     */
    int jargc = 0;
    const char **jargv = NULL;

    static const char *const javac_jargs[] = {
        "-J--add-modules",
        "-JALL-DEFAULT",
        "-J-ms8m",
        "-m",
        "jdk.compiler/com.sun.tools.javac.Main"
    };

    static const char *const jlink_jargs[] = {
        "-J--add-modules",
        "-JALL-DEFAULT",
        "-J-ms8m",
        "-m",
        "jdk.jlink/jdk.tools.jlink.internal.Main"
    };

    static const char *const jmod_jargs[] = {
        "-J--add-modules",
        "-JALL-DEFAULT",
        "-J-ms8m",
        "-m",
        "jdk.jlink/jdk.tools.jmod.Main"
    };

    static const char *const jdeps_jargs[] = {
        "-J--add-modules",
        "-JALL-DEFAULT",
        "-J-ms8m",
        "-m",
        "jdk.jdeps/com.sun.tools.jdeps.Main"
    };

    if (tool_name && strcmp(tool_name, "javac") == 0) {
        jargv = javac_jargs;
        jargc = (int)(sizeof(javac_jargs) / sizeof(javac_jargs[0]));
        LOGI("tool mode: javac (jargc=%d)", jargc);
    } else if (tool_name && strcmp(tool_name, "jlink") == 0) {
        jargv = jlink_jargs;
        jargc = (int)(sizeof(jlink_jargs) / sizeof(jlink_jargs[0]));
        LOGI("tool mode: jlink (jargc=%d)", jargc);
    } else if (tool_name && strcmp(tool_name, "jmod") == 0) {
        jargv = jmod_jargs;
        jargc = (int)(sizeof(jmod_jargs) / sizeof(jmod_jargs[0]));
        LOGI("tool mode: jmod (jargc=%d)", jargc);
    } else if (tool_name && strcmp(tool_name, "jdeps") == 0) {
        jargv = jdeps_jargs;
        jargc = (int)(sizeof(jdeps_jargs) / sizeof(jdeps_jargs[0]));
        LOGI("tool mode: jdeps (jargc=%d)", jargc);
    }

    int launch_argc = 1 + tool_argc;
    char **launch_argv =
        (char **)calloc((size_t)launch_argc + 1, sizeof(char *));
    if (!launch_argv) {
        fprintf(stderr, "jdk_tool_proxy: calloc failed\n");
        return 127;
    }

    launch_argv[0] = (char *)tool_name;

    for (int i = 0; i < tool_argc; i++) {
        launch_argv[1 + i] = tool_args[i];
    }

    launch_argv[launch_argc] = NULL;

    LOGI("JLI launch: tool=%s argc=%d jargc=%d",
         tool_name ? tool_name : "(null)",
         launch_argc,
         jargc);

    /*
     * IMPORTANT:
     *   appclassc/appclassv = 0/NULL
     *   javaargs = 1
     *
     * The module/main-class belongs in jargv, matching the
     * stock JDK launcher path used by java_launcher.c.
     */
    int code = launch(
        launch_argc,
        launch_argv,
        jargc,
        jargv,
        0,
        NULL,
        fullversion,
        dotversion,
        tool_name,
        tool_name,
        (jboolean)1,
        (jboolean)1,
        (jboolean)0,
        (jint)0
    );

    free(launch_argv);
    return code;
}
static int jdk_tool_proxy_main(int argc, char **argv) {
    /* Disable bionic heap tagging before any dlopen/JLI_Launch */
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0);

    const char *tool_path = NULL;
    int first_tool_arg = 0;

    LOGI("jdk_tool_proxy_main: argc=%d", argc);
    for (int i = 0; i < argc; i++) {
        LOGI("  argv[%d] = %s", i, argv[i] ? argv[i] : "(null)");
    }

    for (int i = 0; i < argc; i++) {
        if (!argv[i] || !argv[i][0]) continue;
        if (is_launcher_arg(argv[i])) {
            if (first_tool_arg == i) first_tool_arg = i + 1;
            continue;
        }
        if (argv[i][0] == '/') {
            tool_path = argv[i];
            first_tool_arg = i + 1;
            break;
        }
    }

    if (!tool_path || !tool_path[0]) {
        fprintf(stderr, "jdk_tool_proxy: missing tool path (argc=%d)\n", argc);
        LOGE("missing tool path");
        return 127;
    }

    const char *tool_name = basename_ptr(tool_path);
    LOGI("detected tool_name: %s", tool_name);

    int tool_argc = argc - first_tool_arg;
    if (tool_argc < 0) tool_argc = 0;
    if (is_native_build_tool_name(tool_name)) {
        LOGI("launching as native build tool: %s", tool_name);
        return launch_native_build_tool(tool_path, tool_name, argv + first_tool_arg, tool_argc);
    }
    const char *module = tool_module(tool_name);
    const char *main_class = tool_main_class(tool_name);

    LOGI("launching as JLI tool: name=%s module=%s", tool_name, module ? module : "(null)");
    return launch_jli(tool_name, module, main_class, argv + first_tool_arg, tool_argc);
}

int main(int argc, char **argv) {
    return jdk_tool_proxy_main(argc, argv);
}



