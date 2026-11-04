#include "virtual_runtime.h"
#include "elf_analysis.h"
#include "vlib_resolver.h"
#include "linker_resolver.h"
#include "virtual_filesystem.h"
#include "process_manager.h"
#include "ms_utils.h"

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define TAG "VirtualRuntime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    char runtime_dir[1024];
    char native_lib_dir[1024];
    char code_cache_dir[1024];
    char java_home[1024];
    int initialized;
} vr_context_t;

typedef struct {
    pm_handle_t pm;
    pid_t pid;
    int active;
} vr_process_t;

#define MAX_PROCESSES 32

static vr_context_t g_ctx = {0};
static vr_process_t g_processes[MAX_PROCESSES];
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

static int find_executable_in_path(const char *name, const char *path_list,
                                    char *out, size_t out_sz) {
    if (!name || !path_list || !out) return -1;
    char *dup = strdup(path_list);
    if (!dup) return -1;
    char *save = NULL;
    char *dir = strtok_r(dup, ":", &save);
    while (dir) {
        char buf[1024];
        snprintf(buf, sizeof(buf), "%s/%s", dir, name);
        if (access(buf, X_OK) == 0) {
            strncpy(out, buf, out_sz - 1);
            out[out_sz - 1] = '\0';
            free(dup);
            return 0;
        }
        dir = strtok_r(NULL, ":", &save);
    }
    free(dup);
    return -1;
}

static int find_executable(const char *name, vr_context_t *ctx, char *out, size_t out_sz) {
    if (strchr(name, '/')) {
        if (access(name, X_OK) == 0) {
            strncpy(out, name, out_sz - 1);
            out[out_sz - 1] = '\0';
            return 0;
        }
        return -1;
    }

    const char *search_dirs[] = {
        ctx->runtime_dir,
        NULL
    };

    char buf[2048];
    for (int i = 0; search_dirs[i] && search_dirs[i][0]; i++) {
        snprintf(buf, sizeof(buf), "%s/bin/%s", search_dirs[i], name);
        if (access(buf, X_OK) == 0) {
            strncpy(out, buf, out_sz - 1);
            out[out_sz - 1] = '\0';
            return 0;
        }
        snprintf(buf, sizeof(buf), "%s/usr/bin/%s", search_dirs[i], name);
        if (access(buf, X_OK) == 0) {
            strncpy(out, buf, out_sz - 1);
            out[out_sz - 1] = '\0';
            return 0;
        }
    }

    if (ctx->java_home[0]) {
        snprintf(buf, sizeof(buf), "%s/bin/%s", ctx->java_home, name);
        if (access(buf, X_OK) == 0) {
            strncpy(out, buf, out_sz - 1);
            out[out_sz - 1] = '\0';
            return 0;
        }
    }

    const char *path_env = getenv("PATH");
    if (path_env && find_executable_in_path(name, path_env, out, out_sz) == 0) {
        return 0;
    }

    char default_path[4096];
    snprintf(default_path, sizeof(default_path),
             "%s/bin:%s/usr/bin:%s/bin:%s/usr/bin:/system/bin:/system/xbin",
             ctx->runtime_dir, ctx->runtime_dir,
             ctx->runtime_dir, ctx->runtime_dir);
    if (find_executable_in_path(name, default_path, out, out_sz) == 0) {
        return 0;
    }

    return -ENOENT;
}

static int build_envp(vr_context_t *ctx, const char *ld_path,
                       char *const *user_envp, char ***out_envp) {
    int base_count = 0;
    char *base_env[PM_MAX_ENV];

    const char *inherit[] = {
        "HOME", "USER", "SHELL", "LANG", "TERM",
        "TMPDIR", "TMP", "TEMP",
        NULL
    };

    for (int i = 0; user_envp && user_envp[i]; i++) {
        if (base_count >= PM_MAX_ENV - 20) break;
        if (strncmp(user_envp[i], "LD_LIBRARY_PATH=", 16) == 0) continue;
        if (strncmp(user_envp[i], "LD_PRELOAD=", 11) == 0) continue;
        base_env[base_count++] = user_envp[i];
    }

    char buf[2048];

    snprintf(buf, sizeof(buf), "PATH=%s/bin:%s/usr/bin:%s/bin:%s/usr/bin:/system/bin:/system/xbin",
             ctx->runtime_dir, ctx->runtime_dir, ctx->runtime_dir, ctx->runtime_dir);
    base_env[base_count++] = strdup(buf);

    if (ctx->java_home[0]) {
        snprintf(buf, sizeof(buf), "JAVA_HOME=%s", ctx->java_home);
        base_env[base_count++] = strdup(buf);
    }

    if (ld_path && *ld_path) {
        snprintf(buf, sizeof(buf), "LD_LIBRARY_PATH=%s", ld_path);
        base_env[base_count++] = strdup(buf);
    }

    snprintf(buf, sizeof(buf), "TMPDIR=%s/tmp", ctx->runtime_dir);
    base_env[base_count++] = strdup(buf);

    snprintf(buf, sizeof(buf), "HOME=%s", ctx->runtime_dir);
    base_env[base_count++] = strdup(buf);

    base_env[base_count] = NULL;

    *out_envp = calloc(base_count + 1, sizeof(char *));
    if (!*out_envp) return -ENOMEM;
    memcpy(*out_envp, base_env, (base_count + 1) * sizeof(char *));
    return base_count;
}

static void free_envp(char **envp, int count) {
    if (!envp) return;
    for (int i = 0; i < count; i++) {
        if (envp[i]) free(envp[i]);
    }
    free(envp);
}

static int alloc_process_slot(void) {
    for (int i = 0; i < MAX_PROCESSES; i++) {
        if (!g_processes[i].active) return i;
    }
    return -1;
}

/* Shebang detection: reads the first line of a file; if it starts with "#!"
 * writes the interpreter path (and optional single argument) into out_interp,
 * out_arg (optional, may be NULL). Returns 0 when a valid shebang was found,
 * -1 otherwise. */
static int detect_shebang(const char *path, char *out_interp, size_t interp_sz,
                          char *out_arg, size_t arg_sz) {
    FILE *fp = fopen(path, "r");
    if (!fp) return -1;

    char line[2048];
    char *p = fgets(line, sizeof(line), fp);
    fclose(fp);
    if (!p) return -1;

    if (line[0] != '#' || line[1] != '!') return -1;
    p = line + 2;
    while (*p == ' ' || *p == '\t') p++;

    char *nl = strchr(p, '\n');
    if (nl) *nl = '\0';
    while (nl > p && *(nl - 1) == ' ') { nl--; *nl = '\0'; }

    /* Handle /usr/bin/env <name> form: extract the actual command. */
    if (strncmp(p, "/usr/bin/env ", 13) == 0) {
        p += 13;
        while (*p == ' ' || *p == '\t') p++;
    }

    /* Split into interpreter and optional single argument. */
    char *space = NULL;
    if (*p == '"') {
        p++;
        space = strchr(p, '"');
        if (space) {
            *space = '\0';
            space++;
            while (*space == ' ' || *space == '\t') space++;
        }
    } else {
        space = strchr(p, ' ');
        if (space) {
            *space = '\0';
            space++;
            while (*space == ' ' || *space == '\t') space++;
        }
    }

    strncpy(out_interp, p, interp_sz - 1);
    out_interp[interp_sz - 1] = '\0';

    if (out_arg && arg_sz > 0 && space && *space) {
        strncpy(out_arg, space, arg_sz - 1);
        out_arg[arg_sz - 1] = '\0';
    } else if (out_arg && arg_sz > 0) {
        out_arg[0] = '\0';
    }

    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeInitialize(
    JNIEnv *env, jobject thiz,
    jstring runtimeDir,
    jstring nativeLibDir,
    jstring codeCacheDir) {
    const char *rt = (*env)->GetStringUTFChars(env, runtimeDir, NULL);
    const char *nl = nativeLibDir ? (*env)->GetStringUTFChars(env, nativeLibDir, NULL) : NULL;
    const char *cc = codeCacheDir ? (*env)->GetStringUTFChars(env, codeCacheDir, NULL) : NULL;

    pthread_mutex_lock(&g_mutex);

    strncpy(g_ctx.runtime_dir, rt, sizeof(g_ctx.runtime_dir) - 1);
    if (nl) {
        strncpy(g_ctx.native_lib_dir, nl, sizeof(g_ctx.native_lib_dir) - 1);
    }
    if (cc) {
        strncpy(g_ctx.code_cache_dir, cc, sizeof(g_ctx.code_cache_dir) - 1);
    }
    g_ctx.initialized = 1;

    ms_mkdir_p(g_ctx.runtime_dir);
    char buf[2048];
    snprintf(buf, sizeof(buf), "%s/tmp", g_ctx.runtime_dir);
    ms_mkdir_p(buf);
    snprintf(buf, sizeof(buf), "%s/bin", g_ctx.runtime_dir);
    ms_mkdir_p(buf);
    snprintf(buf, sizeof(buf), "%s/lib", g_ctx.runtime_dir);
    ms_mkdir_p(buf);
    snprintf(buf, sizeof(buf), "%s/lib64", g_ctx.runtime_dir);
    ms_mkdir_p(buf);

    vlr_init();
    vlr_set_runtime_dir(g_ctx.runtime_dir);
    if (nl) vlr_set_native_lib_dir(nl);

    vfs_init();
    vfs_add_mapping("/sdcard",           "/storage/emulated/0");
    vfs_add_mapping("/mnt/sdcard",       "/storage/emulated/0");
    vfs_add_mapping("/storage/emulated/0", "/storage/emulated/0");

    memset(g_processes, 0, sizeof(g_processes));

    pthread_mutex_unlock(&g_mutex);

    if (cc) (*env)->ReleaseStringUTFChars(env, codeCacheDir, cc);
    if (nl) (*env)->ReleaseStringUTFChars(env, nativeLibDir, nl);
    (*env)->ReleaseStringUTFChars(env, runtimeDir, rt);

    LOGI("VirtualRuntime initialized: runtime=%s nativeLib=%s codeCache=%s",
         g_ctx.runtime_dir, nl ? nl : "(none)", cc ? cc : "(none)");
    return (jlong)(intptr_t)&g_ctx;
}

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeShutdown(
    JNIEnv *env, jobject thiz,
    jlong handle) {
    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < MAX_PROCESSES; i++) {
        if (g_processes[i].active) {
            pm_kill_process_group(&g_processes[i].pm, SIGTERM);
            usleep(100 * 1000);
            pm_kill_process_group(&g_processes[i].pm, SIGKILL);
            pm_close(&g_processes[i].pm);
            g_processes[i].active = 0;
        }
    }
    vlr_cleanup();
    vfs_cleanup();
    g_ctx.initialized = 0;
    pthread_mutex_unlock(&g_mutex);
}

JNIEXPORT jlong JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeExecute(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring executable,
    jobjectArray args,
    jobjectArray envVars,
    jstring cwd,
    jint timeoutMs,
    jboolean createProcessGroup) {
    const char *exec_path = (*env)->GetStringUTFChars(env, executable, NULL);
    const char *cwd_str = cwd ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    /* --- 1. VFS path translation --- */
    char vfs_resolved[1024];
    if (vfs_resolve_path(exec_path, vfs_resolved, sizeof(vfs_resolved)) == 1) {
        LOGI("VFS resolved: %s -> %s", exec_path, vfs_resolved);
    }

    /* --- 2. Path security validation --- */
    const char *effective_path = vfs_resolved;
    if (!vfs_is_path_secure(effective_path)) {
        LOGW("Path security check failed for: %s", effective_path);
    }

    /* --- 3. Find executable --- */
    char resolved_exec[1024];
    int found = find_executable(effective_path, &g_ctx, resolved_exec, sizeof(resolved_exec));
    if (found < 0) {
        strncpy(resolved_exec, effective_path, sizeof(resolved_exec) - 1);
        resolved_exec[sizeof(resolved_exec) - 1] = '\0';
    }

    /* --- 4. Shebang detection --- */
    char shebang_interp[1024] = {0};
    char shebang_arg[1024]    = {0};
    int is_script = (detect_shebang(resolved_exec, shebang_interp, sizeof(shebang_interp),
                                    shebang_arg, sizeof(shebang_arg)) == 0);

    char effective_exec[1024];
    if (is_script && shebang_interp[0]) {
        char interp_resolved[1024];
        if (find_executable(shebang_interp, &g_ctx, interp_resolved, sizeof(interp_resolved)) == 0) {
            strncpy(effective_exec, interp_resolved, sizeof(effective_exec) - 1);
            LOGI("Shebang: %s -> %s %s", resolved_exec, interp_resolved, shebang_arg);
        } else {
            strncpy(effective_exec, shebang_interp, sizeof(effective_exec) - 1);
            LOGW("Shebang interpreter not found: %s, falling back to direct exec", shebang_interp);
        }
        effective_exec[sizeof(effective_exec) - 1] = '\0';
    } else {
        strncpy(effective_exec, resolved_exec, sizeof(effective_exec) - 1);
        effective_exec[sizeof(effective_exec) - 1] = '\0';
    }

    /* --- 5. ELF analysis + dependency resolution --- */
    vlr_result_t vlr_result;
    memset(&vlr_result, 0, sizeof(vlr_result));

    elf_info_t elf_info;
    int has_elf = (elf_analyze(effective_exec, &elf_info) == 0);

    lr_result_t lr_info;
    memset(&lr_info, 0, sizeof(lr_info));

    if (has_elf) {
        lr_resolve_interp(&elf_info, &lr_info);
        if (elf_info.needed_count > 0) {
            vlr_resolve(effective_exec, &vlr_result);
            if (!vlr_result.success && vlr_result.missing_count > 0) {
                LOGW("Dependency resolution had %zu missing libraries", vlr_result.missing_count);
            }
        }
    }

    /* --- 6. Build LD_LIBRARY_PATH --- */
    char ld_library_path[8192] = {0};
    lr_build_ld_library_path(effective_exec, &elf_info, &vlr_result,
                             ld_library_path, sizeof(ld_library_path));

    /* --- 7. Build argv --- */
    int argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = calloc(argc + 4, sizeof(char *)); /* +4: shebang interp + arg + exec + NULL */
    int arg_idx = 0;

    if (is_script && shebang_interp[0]) {
        char interp_resolved[1024];
        if (find_executable(shebang_interp, &g_ctx, interp_resolved, sizeof(interp_resolved)) == 0) {
            argv[arg_idx++] = strdup(interp_resolved);
        } else {
            argv[arg_idx++] = strdup(shebang_interp);
        }
        if (shebang_arg[0]) {
            argv[arg_idx++] = strdup(shebang_arg);
        }
        argv[arg_idx++] = strdup(resolved_exec);  /* pass the script as argv[1] or argv[2] */
    }

    argv[arg_idx++] = strdup(effective_exec);
    for (int i = 0; i < argc; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *s = (*env)->GetStringUTFChars(env, js, NULL);
        argv[arg_idx++] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, js, s);
        (*env)->DeleteLocalRef(env, js);
    }
    argv[arg_idx] = NULL;

    /* --- 8. Build envp --- */
    char *user_env[PM_MAX_ENV];
    int user_envc = 0;
    if (envVars) {
        user_envc = (*env)->GetArrayLength(env, envVars);
        if (user_envc > PM_MAX_ENV - 20) user_envc = PM_MAX_ENV - 20;
        for (int i = 0; i < user_envc; i++) {
            jstring js = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
            const char *s = (*env)->GetStringUTFChars(env, js, NULL);
            user_env[i] = strdup(s);
            (*env)->ReleaseStringUTFChars(env, js, s);
            (*env)->DeleteLocalRef(env, js);
        }
        user_env[user_envc] = NULL;
    } else {
        user_env[0] = NULL;
    }

    char **final_envp = NULL;
    int env_count = build_envp(&g_ctx, ld_library_path[0] ? ld_library_path : vlr_result.ld_library_path,
                                (char *const *)user_env, &final_envp);

    /* --- 9. Spawn process --- */
    pm_exec_args_t pm_args;
    memset(&pm_args, 0, sizeof(pm_args));
    pm_args.executable = effective_exec;
    pm_args.argv = (char *const *)argv;
    pm_args.envp = (char *const *)final_envp;
    pm_args.cwd = cwd_str;
    pm_args.code_cache_dir = g_ctx.code_cache_dir[0] ? g_ctx.code_cache_dir : NULL;
    pm_args.timeout_ms = timeoutMs;
    pm_args.create_process_group = createProcessGroup;

    pthread_mutex_lock(&g_mutex);
    int slot = alloc_process_slot();
    if (slot < 0) {
        pthread_mutex_unlock(&g_mutex);
        free_envp(final_envp, env_count);
        for (int i = 0; i < arg_idx; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < user_envc; i++) free(user_env[i]);
        (*env)->ReleaseStringUTFChars(env, executable, exec_path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);
        LOGE("No free process slots");
        return -1;
    }

    int ret = pm_spawn(&pm_args, &g_processes[slot].pm);
    if (ret < 0) {
        pthread_mutex_unlock(&g_mutex);
        LOGE("pm_spawn failed: %d (%s)", ret, g_processes[slot].pm.error);
        free_envp(final_envp, env_count);
        for (int i = 0; i < arg_idx; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < user_envc; i++) free(user_env[i]);
        (*env)->ReleaseStringUTFChars(env, executable, exec_path);
        if (cwd) (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);
        return -1;
    }

    g_processes[slot].active = 1;
    g_processes[slot].pid = g_processes[slot].pm.pid;
    pthread_mutex_unlock(&g_mutex);

    free_envp(final_envp, env_count);
    for (int i = 0; i < arg_idx; i++) free(argv[i]);
    free(argv);
    for (int i = 0; i < user_envc; i++) free(user_env[i]);
    (*env)->ReleaseStringUTFChars(env, executable, exec_path);
    if (cwd) (*env)->ReleaseStringUTFChars(env, cwd, cwd_str);

    LOGI("Process started: pid=%d slot=%d exec=%s", g_processes[slot].pid, slot, effective_exec);
    return (jlong)slot;
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeReadStdout(
    JNIEnv *env, jobject thiz,
    jlong processHandle, jint timeoutMs) {
    int slot = (int)processHandle;
    if (slot < 0 || slot >= MAX_PROCESSES || !g_processes[slot].active) return NULL;

    char buf[PM_LINE_MAX];
    int n = pm_read_stdout_line(&g_processes[slot].pm, buf, sizeof(buf), timeoutMs);
    if (n > 0) return (*env)->NewStringUTF(env, buf);
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeReadStderr(
    JNIEnv *env, jobject thiz,
    jlong processHandle, jint timeoutMs) {
    int slot = (int)processHandle;
    if (slot < 0 || slot >= MAX_PROCESSES || !g_processes[slot].active) return NULL;

    char buf[PM_LINE_MAX];
    int n = pm_read_stderr_line(&g_processes[slot].pm, buf, sizeof(buf), timeoutMs);
    if (n > 0) return (*env)->NewStringUTF(env, buf);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeWait(
    JNIEnv *env, jobject thiz,
    jlong processHandle) {
    int slot = (int)processHandle;
    if (slot < 0 || slot >= MAX_PROCESSES || !g_processes[slot].active) return -1;
    return pm_wait(&g_processes[slot].pm);
}

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeWaitTimeout(
    JNIEnv *env, jobject thiz,
    jlong processHandle, jint timeoutMs) {
    int slot = (int)processHandle;
    if (slot < 0 || slot >= MAX_PROCESSES || !g_processes[slot].active) return -1;
    return pm_wait_timeout(&g_processes[slot].pm, timeoutMs);
}

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeKill(
    JNIEnv *env, jobject thiz,
    jlong processHandle) {
    int slot = (int)processHandle;
    if (slot < 0 || slot >= MAX_PROCESSES || !g_processes[slot].active) return;
    pm_kill(&g_processes[slot].pm, SIGTERM);
}

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeKillGroup(
    JNIEnv *env, jobject thiz,
    jlong processHandle) {
    int slot = (int)processHandle;
    if (slot < 0 || slot >= MAX_PROCESSES || !g_processes[slot].active) return;
    pm_kill_process_group(&g_processes[slot].pm, SIGTERM);
    usleep(100 * 1000);
    pm_kill_process_group(&g_processes[slot].pm, SIGKILL);
}

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeFreeProcess(
    JNIEnv *env, jobject thiz,
    jlong processHandle) {
    int slot = (int)processHandle;
    if (slot < 0 || slot >= MAX_PROCESSES || !g_processes[slot].active) return;
    pm_close(&g_processes[slot].pm);
    g_processes[slot].active = 0;
    g_processes[slot].pid = 0;
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeResolveDependencies(
    JNIEnv *env, jobject thiz,
    jlong handle, jstring executable) {
    const char *path = (*env)->GetStringUTFChars(env, executable, NULL);

    vlr_result_t result;
    memset(&result, 0, sizeof(result));
    vlr_resolve(path, &result);

    char buf[8192];
    size_t pos = 0;

    if (result.missing_count > 0) {
        pos += snprintf(buf + pos, sizeof(buf) - pos,
                        "Missing libraries (%zu):\n", result.missing_count);
        for (size_t i = 0; i < result.missing_count && pos < sizeof(buf); i++) {
            pos += snprintf(buf + pos, sizeof(buf) - pos,
                            "  %s\n    Required by: %s\n",
                            result.missing[i].lib_name,
                            result.missing[i].required_by);
            for (int j = 0; j < result.missing[i].search_count && pos < sizeof(buf); j++) {
                pos += snprintf(buf + pos, sizeof(buf) - pos,
                                "    Searched: %s\n",
                                result.missing[i].searched_paths[j]);
            }
        }
    }

    if (result.ld_library_path[0]) {
        pos += snprintf(buf + pos, sizeof(buf) - pos,
                        "LD_LIBRARY_PATH=%s\n", result.ld_library_path);
    }

    pos += snprintf(buf + pos, sizeof(buf) - pos,
                    "Resolved: %zu libraries\n", result.resolved_count);

    (*env)->ReleaseStringUTFChars(env, executable, path);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeAnalyzeElf(
    JNIEnv *env, jobject thiz,
    jstring executable) {
    const char *path = (*env)->GetStringUTFChars(env, executable, NULL);

    elf_info_t info;
    int ret = elf_analyze(path, &info);

    char buf[4096];
    if (ret < 0) {
        snprintf(buf, sizeof(buf), "ELF analysis failed: %s\nPath: %s",
                 strerror(-ret), path);
    } else {
        snprintf(buf, sizeof(buf),
                 "ELF Analysis: %s\n"
                 "  Type: %s\n"
                 "  Architecture: %s\n"
                 "  64-bit: %s\n"
                 "  Interpreter: %s\n"
                 "  SONAME: %s\n"
                 "  RPATH: %s\n"
                 "  RUNPATH: %s\n"
                 "  Build ID: %s\n"
                 "  Needed: %zu libraries\n",
                 path,
                 elf_type_str(info.type),
                 elf_arch_str(info.machine),
                 info.is_64bit ? "yes" : "no",
                 info.interp[0] ? info.interp : "(none)",
                 info.soname[0] ? info.soname : "(none)",
                 info.rpath[0] ? info.rpath : "(none)",
                 info.runpath[0] ? info.runpath : "(none)",
                 info.build_id[0] ? info.build_id : "(none)",
                 info.needed_count);

        for (size_t i = 0; i < info.needed_count && strlen(buf) < sizeof(buf) - 128; i++) {
            char tmp[256];
            snprintf(tmp, sizeof(tmp), "    [%zu] %s\n", i, info.needed[i]);
            strncat(buf, tmp, sizeof(buf) - strlen(buf) - 1);
        }
    }

    (*env)->ReleaseStringUTFChars(env, executable, path);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeFindExecutable(
    JNIEnv *env, jobject thiz,
    jlong handle, jstring name) {
    const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
    char found[1024];
    int ret = find_executable(cname, &g_ctx, found, sizeof(found));
    (*env)->ReleaseStringUTFChars(env, name, cname);
    if (ret == 0) return (*env)->NewStringUTF(env, found);
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeAddSearchPath(
    JNIEnv *env, jobject thiz,
    jlong handle, jstring path) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    vlr_add_search_path(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
}

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeIsArm64Elf(
    JNIEnv *env, jobject thiz,
    jstring path) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    int result = elf_is_arm64(cpath);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeGetSearchPaths(
    JNIEnv *env, jobject thiz,
    jlong handle) {
    const char *paths = vlr_get_search_paths_string();
    return (*env)->NewStringUTF(env, paths);
}

JNIEXPORT jint JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeVfsAddMapping(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring virtualPath,
    jstring realPath) {
    const char *vp = (*env)->GetStringUTFChars(env, virtualPath, NULL);
    const char *rp = (*env)->GetStringUTFChars(env, realPath, NULL);
    int ret = vfs_add_mapping(vp, rp);
    (*env)->ReleaseStringUTFChars(env, virtualPath, vp);
    (*env)->ReleaseStringUTFChars(env, realPath, rp);
    return (jint)ret;
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeVfsResolvePath(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring path) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    char resolved[VFS_PATH_LEN];
    int mapped = vfs_resolve_path(cpath, resolved, sizeof(resolved));
    (*env)->ReleaseStringUTFChars(env, path, cpath);

    char buf[2048];
    snprintf(buf, sizeof(buf), "%s\nmapped=%d", resolved, mapped);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeGetLinkerInfo(
    JNIEnv *env, jobject thiz,
    jstring executable) {
    const char *path = (*env)->GetStringUTFChars(env, executable, NULL);

    elf_info_t elf_info;
    lr_result_t lr_info;
    int has_elf = (elf_analyze(path, &elf_info) == 0);

    char buf[4096];
    if (!has_elf) {
        snprintf(buf, sizeof(buf),
                 "Linker info for: %s\n"
                 "  ELF analysis failed\n"
                 "  Path: %s", path, path);
    } else {
        lr_resolve_interp(&elf_info, &lr_info);
        snprintf(buf, sizeof(buf),
                 "Linker info for: %s\n"
                 "  Interpreter: %s\n"
                 "  Is 64-bit: %s\n"
                 "  Is static: %s\n"
                 "  System linker (64-bit): %s\n"
                 "  System linker (32-bit): %s\n"
                 "  Needed: %zu libraries\n",
                 path,
                 lr_info.interp[0] ? lr_info.interp : "(none)",
                 lr_info.is_64bit ? "yes" : "no",
                 lr_info.is_static ? "yes" : "no",
                 lr_info.linker64_path[0] ? lr_info.linker64_path : "(not found)",
                 lr_info.linker32_path[0] ? lr_info.linker32_path : "(not found)",
                 elf_info.needed_count);
    }

    (*env)->ReleaseStringUTFChars(env, executable, path);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_aide_mobilestudio_VirtualProcessExecutor_nativeCheckPathSecurity(
    JNIEnv *env, jobject thiz,
    jlong handle,
    jstring path,
    jstring root) {
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    const char *croot = root ? (*env)->GetStringUTFChars(env, root, NULL) : NULL;

    char buf[4096];
    size_t pos = 0;

    /* Basic validation */
    int secure = vfs_is_path_secure(cpath);
    pos += snprintf(buf + pos, sizeof(buf) - pos,
                    "Path: %s\nSecure: %s\n", cpath, secure ? "yes" : "no");

    /* VFS resolution */
    char resolved[VFS_PATH_LEN];
    int mapped = vfs_resolve_path(cpath, resolved, sizeof(resolved));
    pos += snprintf(buf + pos, sizeof(buf) - pos,
                    "VFS mapped: %s\nResolved: %s\n", mapped ? "yes" : "no", resolved);

    /* Symlink escape check under root */
    if (croot) {
        char symlink_esc[1024] = {0};
        char target_esc[1024]  = {0};
        int safe = vfs_check_symlink_escape(resolved, croot,
                                            symlink_esc, sizeof(symlink_esc),
                                            target_esc, sizeof(target_esc));
        pos += snprintf(buf + pos, sizeof(buf) - pos,
                        "Root: %s\nSymlink escape: %s\n",
                        croot, safe == 0 ? "safe" : "ESCAPE DETECTED");
        if (safe < 0) {
            pos += snprintf(buf + pos, sizeof(buf) - pos,
                            "  Escape at: %s\n  Target: %s\n",
                            symlink_esc, target_esc);
        }
    }

    if (root) (*env)->ReleaseStringUTFChars(env, root, croot);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    return (*env)->NewStringUTF(env, buf);
}
