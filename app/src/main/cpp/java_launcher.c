/*
 * java_launcher.c
 *
 * java_launcher — generic JDK tool launcher for Android arm64-v8a (API 26+),
 * built as a PIE EXECUTABLE (packaged as libjava_launcher.so — see CMakeLists).
 * Invoked from the app's nativeLibraryDir (direct exec works there; the
 * linker64 form stays as fallback):
 *
 *   <nativeLibraryDir>/libjava_launcher.so /path/to/jdk/bin/<tool>.real [args...]
 *   /system/bin/linker64 <nativeLibraryDir>/libjava_launcher.so <tool>.real [args...]
 *
 * argv handed to the process is identical in both forms:
 *   argv[0] = launcher path, argv[1] = <tool>.real, argv[2..] = user args.
 *
 * Location constraint (custom JDK java_md): JLI_Launch derives
 * GetApplicationHome from /proc/self/exe and aborts with "Could not
 * determine application home." unless that path contains "/bin/" or
 * "/lib/". nativeLibraryDir (…/lib/arm64/…) satisfies this; exec from
 * files/, /data/local/tmp, etc. does not.
 *
 * Prefer the direct-exec form: JLI re-execs /proc/self/exe once
 * (mustsetenv). Under the linker64 form /proc/self/exe is linker64 and
 * that re-exec dies with `error: expected absolute path: "<arg>"`.
 *
 * The launcher:
 *   1. dlopens libjli.so (LD_LIBRARY_PATH → derived JDK home → JAVA_HOME env)
 *   2. rewrites argv so JLI_Launch sees argv[0] = the real tool binary path
 *      (JLI derives the JDK home + tool name from argv[0])
 *   3. ensures lib/server is on LD_LIBRARY_PATH for libjvm.so loading
 *   4. never requires LD_PRELOAD; never execve's (JLI_Launch runs in-process)
 *
 * Debug: logcat tag MobileStudioJavaLauncher. Set MOBILESTUDIO_LAUNCHER_DEBUG=1
 * for a full startup dump (argv, JDK home, java.real anchor, LD_LIBRARY_PATH,
 * dlopen result); failure paths always dump.
 *
 * No hardcoded /data/app or Termux paths. JDK version strings come from
 * MOBILESTUDIO_JDK_FULLVERSION env (set by the Kotlin side) with a generic fallback.
 *
 * Build: Android NDK CMake — arm64-v8a
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <limits.h>
#include <android/log.h>

#define TAG  "MobileStudioJavaLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include <stdint.h>
typedef uint8_t  jboolean;
typedef int32_t  jint;

/* JLI_Launch: JDK tool process main entry */
typedef int (*JLI_Launch_t)(
        int    argc,
        char **argv,
        int    jargc,
        const char **jargv,
        int    appclassc,
        const char **appclassv,
        const char  *fullversion,
        const char  *dotversion,
        const char  *pname,
        const char  *lname,
        jboolean javaargs,
        jboolean cpwildcard,
        jboolean javaw,
        jint ergo
);

/* ------------------------------------------------------------------ */
/* dlopen helper: LD_LIBRARY_PATH → derived home → JAVA_HOME env       */
/* ------------------------------------------------------------------ */

/* Last dlopen() outcome — surfaced by debug_dump() (see below). */
static int g_last_dlopen_ok = 0;
static char g_last_dlopen_err[512];

static void *try_dlopen(const char *path) {
    if (!path || !*path) return NULL;
    void *h = dlopen(path, RTLD_LAZY | RTLD_GLOBAL);
    if (h) {
        g_last_dlopen_ok = 1;
        g_last_dlopen_err[0] = '\0';
        LOGI("libjli.so loaded from: %s", path);
        return h;
    }
    const char *err = dlerror();
    snprintf(g_last_dlopen_err, sizeof(g_last_dlopen_err), "%s",
             err ? err : "(dlerror() returned NULL)");
    g_last_dlopen_ok = 0;
    LOGI("dlopen(%s) failed: %s", path, g_last_dlopen_err);
    return NULL;
}

/** Appends path to LD_LIBRARY_PATH (if not already present). */
static void add_ld_path(const char *dir) {
    if (!dir || !*dir) return;
    const char *cur = getenv("LD_LIBRARY_PATH");
    if (cur && strstr(cur, dir)) return;
    char buf[PATH_MAX * 2];
    if (cur && *cur) {
        snprintf(buf, sizeof(buf), "%s:%s", cur, dir);
    } else {
        snprintf(buf, sizeof(buf), "%s", dir);
    }
    setenv("LD_LIBRARY_PATH", buf, 1);
}

/** Ensures lib/server + lib are on LD_LIBRARY_PATH for libjvm.so loading. */
static void add_ld_path_home(const char *home);

/** Derives the JDK home (parent of bin/) from a tool binary path. */
static void derive_jdk_home(const char *realPath, char *out, size_t outSize) {
    out[0] = '\0';
    if (!realPath || !*realPath) return;
    char buf[PATH_MAX];
    snprintf(buf, sizeof(buf), "%s", realPath);
    char *slash = strrchr(buf, '/');
    if (!slash) return;
    *slash = '\0';                       /* .../bin */
    slash = strrchr(buf, '/');
    if (!slash) return;
    *slash = '\0';                       /* JDK home */
    snprintf(out, outSize, "%s", buf);
}

/*
 * Debug dump for logcat tag MobileStudioJavaLauncher. When a launch fails we
 * never guess: argv, anchor (java.real), derived JDK home, LD_LIBRARY_PATH,
 * dlopen result and dlerror are all printed. Also enabled unconditionally via
 * MOBILESTUDIO_LAUNCHER_DEBUG=1. This launcher performs NO execve — JLI_Launch
 * runs in-process — so "execve result/errno" is logged as n/a.
 */
static void debug_dump(int argc, char *argv[], const char *realPath, const char *stage) {
    int i;
    char home[PATH_MAX];

    LOGI("---- debug dump @ %s ----", stage ? stage : "?");
    LOGI("argc=%d", argc);
    for (i = 0; i < argc && i < 64; i++) {
        LOGI("argv[%d]=%s", i, (argv && argv[i]) ? argv[i] : "(null)");
    }
    LOGI("java.real anchor=%s", realPath ? realPath : "(none)");
    home[0] = '\0';
    if (realPath) derive_jdk_home(realPath, home, sizeof(home));
    LOGI("JDK home=%s", home[0] ? home : "(undetermined)");
    LOGI("LD_LIBRARY_PATH=%s",
         getenv("LD_LIBRARY_PATH") ? getenv("LD_LIBRARY_PATH") : "(unset)");
    LOGI("MOBILESTUDIO_JDK_TOOL=%s",
         getenv("MOBILESTUDIO_JDK_TOOL") ? getenv("MOBILESTUDIO_JDK_TOOL") : "(unset)");
    LOGI("MOBILESTUDIO_JDK_FULLVERSION=%s",
         getenv("MOBILESTUDIO_JDK_FULLVERSION") ? getenv("MOBILESTUDIO_JDK_FULLVERSION") : "(unset)");
    LOGI("LD_PRELOAD=%s", getenv("LD_PRELOAD") ? getenv("LD_PRELOAD") : "(unset)");
    LOGI("dlopen(libjli.so): %s", g_last_dlopen_ok ? "OK" : "FAILED");
    if (!g_last_dlopen_ok && g_last_dlopen_err[0]) LOGI("dlerror=%s", g_last_dlopen_err);
    LOGI("execve: n/a (JLI_Launch runs in-process, no exec performed)");
    LOGI("---- end dump ----");
}

static int launcher_debug_enabled(void) {
    const char *v = getenv("MOBILESTUDIO_LAUNCHER_DEBUG");
    return v && *v && strcmp(v, "0") != 0;
}

static void *open_jli(const char *realPath) {
    /* Load thread_stub first for JDK 21 TLS fix */
    dlopen("libthread_stub.so", RTLD_LAZY | RTLD_GLOBAL);

    /* 1. LD_LIBRARY_PATH (set by the wrapper/Gradle env) */
    void *h = try_dlopen("libjli.so");
    if (h) return h;

    /* 2. Derive JDK home from the real tool binary path */
    char home[PATH_MAX];
    derive_jdk_home(realPath, home, sizeof(home));
    if (home[0] != '\0') {
        char p[PATH_MAX];
        snprintf(p, sizeof(p), "%s/lib/libjli.so", home);
        h = try_dlopen(p);
        if (h) { add_ld_path_home(home); return h; }
        snprintf(p, sizeof(p), "%s/lib/jli/libjli.so", home);
        h = try_dlopen(p);
        if (h) { add_ld_path_home(home); return h; }
    }

    /* 3. JAVA_HOME env fallback */
    const char *jh = getenv("JAVA_HOME");
    if (jh && *jh) {
        char p[PATH_MAX];
        snprintf(p, sizeof(p), "%s/lib/libjli.so", jh);
        h = try_dlopen(p);
        if (h) { add_ld_path_home(jh); return h; }
        snprintf(p, sizeof(p), "%s/lib/jli/libjli.so", jh);
        h = try_dlopen(p);
        if (h) { add_ld_path_home(jh); return h; }
    }

    return NULL;
}

/** Ensures lib/server + lib are on LD_LIBRARY_PATH for libjvm.so loading. */
static void add_ld_path_home(const char *home) {
    char p[PATH_MAX];
    snprintf(p, sizeof(p), "%s/lib/server", home);
    add_ld_path(p);
    snprintf(p, sizeof(p), "%s/lib", home);
    add_ld_path(p);
    snprintf(p, sizeof(p), "%s/lib/jli", home);
    add_ld_path(p);
}

/* ------------------------------------------------------------------ */
/* main                                                                */
/* ------------------------------------------------------------------ */

int main(int argc, char *argv[]) {

    /* Invocation (identical argv in both forms):
     *   <nativeLibDir>/libjava_launcher.so <real_binary> [args...]
     *   /system/bin/linker64 <nativeLibDir>/libjava_launcher.so <real_binary> [args...]
     * argv[0] = launcher path, argv[1] = real binary, argv[2..] = user args.
     * Rewrite so JLI_Launch receives argv[0] = real binary path — JLI derives
     * the JDK home and tool name (java/javac/jlink/...) from argv[0]. */
    char **tool_argv = argv;
    int tool_argc = argc;
    const char *realPath = NULL;

    if (argc >= 2 && argv[1] && argv[1][0] == '/') {
        realPath = argv[1];
        tool_argv = argv + 1;
        tool_argc = argc - 1;
    }

    if (launcher_debug_enabled()) {
        debug_dump(argc, argv, realPath, "startup (MOBILESTUDIO_LAUNCHER_DEBUG)");
    }

    /* 1. Load libjli.so */
    void *jli = open_jli(realPath);
    if (!jli) {
        LOGE("FATAL: libjli.so not found (dlerror=%s)", g_last_dlopen_err);
        debug_dump(argc, argv, realPath, "libjli.so load failed");
        fprintf(stderr,
                "java_launcher: libjli.so load failed\n"
                "  real=%s\n"
                "  LD_LIBRARY_PATH=%s\n"
                "  dlerror=%s\n",
                realPath ? realPath : "(none)",
                getenv("LD_LIBRARY_PATH") ? getenv("LD_LIBRARY_PATH") : "(unset)",
                g_last_dlopen_err);
        return 1;
    }

    /* 2. Resolve JLI_Launch */
    JLI_Launch_t launch = (JLI_Launch_t)dlsym(jli, "JLI_Launch");
    if (!launch) {
        const char *err = dlerror();
        LOGE("dlsym(JLI_Launch) failed: %s", err ? err : "(NULL)");
        debug_dump(argc, argv, realPath, "dlsym(JLI_Launch) failed");
        fprintf(stderr, "java_launcher: JLI_Launch symbol not found\n");
        return 1;
    }

    LOGI("JLI_Launch found, argc=%d argv[0]=%s", tool_argc, tool_argc > 0 ? tool_argv[0] : "(null)");

    /* 3. Version string: env first (JDK 21 etc.), generic fallback */
    const char *fullversion = getenv("MOBILESTUDIO_JDK_FULLVERSION");
    if (!fullversion || !*fullversion) fullversion = "21.0";
    const char *dotversion = getenv("MOBILESTUDIO_JDK_DOTVERSION");
    if (!dotversion || !*dotversion) dotversion = "21.0";

    /* pname/lname + tool dispatch: env first (wrapper v11 sets MOBILESTUDIO_JDK_TOOL
     * from the wrapper's own name — the ELF anchor passed as argv[1] may be
     * javac.real even for java, because java_md requires argv[0] to be a real ELF),
     * then fall back to the real binary basename for direct invocations. */
    const char *toolEnv = getenv("MOBILESTUDIO_JDK_TOOL");
    const char *pname = "java";
    const char *lname = "java";
    if (toolEnv && *toolEnv) {
        static char nameBuf[64];
        snprintf(nameBuf, sizeof(nameBuf), "%s", toolEnv);
        pname = nameBuf;
        lname = nameBuf;
    } else if (realPath) {
        const char *base = strrchr(realPath, '/');
        base = base ? base + 1 : realPath;
        static char nameBuf[64];
        snprintf(nameBuf, sizeof(nameBuf), "%s", base);
        char *dot = strchr(nameBuf, '.');
        if (dot) *dot = '\0';
        pname = nameBuf;
        lname = nameBuf;
    }

    /* Dispatch diagnostics (device-side verification of tool identity). */
    {
        const char *base = realPath ? strrchr(realPath, '/') : NULL;
        base = base ? base + 1 : (realPath ? realPath : "(none)");
        LOGI("dispatch: toolEnv=%s anchor=%s -> tool=%s userargs=%d",
             (toolEnv && *toolEnv) ? toolEnv : "(unset)",
             base, pname ? pname : "?", tool_argc > 1 ? tool_argc - 1 : 0);
    }

    /* Heuristic guard: without MOBILESTUDIO_JDK_TOOL the tool comes from the anchor
     * basename. A stale wrapper (pre-v11, no export) passes javac.real as the anchor
     * for EVERY tool, so java daemon/client spawns dispatch as javac and die with
     *   error: invalid flag: -XX:+UseSerialGC / Usage: javac <options>
     * Java-only VM flags among the user args prove the caller meant java: switch. */
    if ((!toolEnv || !*toolEnv) && pname && strcmp(pname, "javac") == 0) {
        int looks_java = 0, looks_javac = 0;
        int i;
        for (i = 1; i < tool_argc; i++) {
            const char *a = tool_argv[i];
            if (!a || a[0] != '-') continue;
            /* Java-only VM flags (javac rejects all of these without -J) */
            if (!strncmp(a, "-XX:", 4) || !strncmp(a, "-Xmx", 4) ||
                !strncmp(a, "-Xms", 4) || !strncmp(a, "-Xss", 4) ||
                !strncmp(a, "-Xmn", 4) || !strcmp(a, "-server") ||
                !strcmp(a, "-client")) {
                looks_java = 1;
            }
            /* javac launcher markers: -J* VM passthrough or javac-only options */
            if (!strncmp(a, "-J", 2) || !strcmp(a, "--system=none") ||
                !strcmp(a, "-classpath") || !strcmp(a, "-cp") ||
                !strcmp(a, "-sourcepath") || !strcmp(a, "-processorpath") ||
                !strncmp(a, "-XD", 3) || !strncmp(a, "-Xlint", 6)) {
                looks_javac = 1;
            }
        }
        if (looks_java && !looks_javac) {
            static char javaName[8] = "java";
            pname = javaName;
            lname = javaName;
            LOGI("heuristic: java-only VM flags, no javac markers -> tool=java");
            fprintf(stderr,
                    "java_launcher: MOBILESTUDIO_JDK_TOOL unset; recovered java "
                    "dispatch via arg heuristic (stale wrapper - regenerate)\n");
        }
    }

    /* Tool-mode dispatch: the JDK's own launcher binaries (javac.real) are never
     * executed directly on this platform (java_md requires the libjava_launcher
     * argv structure), so we must replicate each tool's stock main.c JAVA_ARGS
     * here. Extracted from the JDK build's javac launcher (const_jargs, jargc=5):
     *   -J--add-modules -JALL-DEFAULT -J-ms8m -m jdk.compiler/com.sun.tools.javac.Main
     * TranslateApplicationArgs strips -J and prepends these before the user args,
     * so --system=none (JdkImageTransform) reaches javac as a TOOL option instead
     * of dying in the VM with "Unrecognized option: --system=none". */
    int jargc = 0;
    const char **jargv = NULL;
    static const char *const javac_jargs[] = {
        "-J--add-modules",
        "-JALL-DEFAULT",
        "-J-ms8m",
        "-m",
        "jdk.compiler/com.sun.tools.javac.Main"
    };
    if (pname && strcmp(pname, "javac") == 0) {
        jargc = (int)(sizeof(javac_jargs) / sizeof(javac_jargs[0]));
        jargv = (const char **)javac_jargs;
        LOGI("tool mode: javac (jargc=%d, jdk.compiler/com.sun.tools.javac.Main)", jargc);
    }

    return launch(
            tool_argc, tool_argv,
            jargc, jargv,    /* jargc, jargv — tool JAVA_ARGS (javac tool mode) */
            0, NULL,          /* appclassc, appclassv */
            fullversion,      /* fullversion (--version output) */
            dotversion,       /* dotversion */
            pname,            /* process name (ps display) */
            lname,            /* launcher name */
            (jboolean)1,      /* javaargs — tool mode when jargs present */
            (jboolean)1,      /* cpwildcard */
            (jboolean)0,      /* javaw (Windows only) */
            (jint)0           /* ergo */
    );
}
