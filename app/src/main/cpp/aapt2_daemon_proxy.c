/*
 * Native AAPT2 daemon protocol proxy.
 *
 * AGP expects a long-running AAPT2 process that prints "Ready" on stdout,
 * accepts daemon requests on stdin, and prints "Done" / "Error" on stderr.
 * On Android app sandboxes, Java/app_process stdout is not reliably connected
 * to the Gradle ProcessBuilder pipe, so the protocol side must stay native.
 */

#define _GNU_SOURCE

#include <android/log.h>
#include <errno.h>
#include <android/log.h>
#include <string.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define TAG "Aapt2DaemonProxy"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern char **environ;

static int aapt2_daemon_proxy_main(int argc, char **argv);

__attribute__((noreturn, used))
void aapt2_daemon_proxy_start_c(uintptr_t *stack) {
    int argc = (int)stack[0];
    char **argv = (char **)(stack + 1);
    environ = argv + argc + 1;
    int code = aapt2_daemon_proxy_main(argc, argv);
    _exit(code);
}

__attribute__((naked, noreturn, visibility("default"), used))
void aapt2_daemon_proxy_start(void) {
    __asm__ volatile(
        "mov x0, sp\n"
        "b aapt2_daemon_proxy_start_c\n"
    );
}

typedef struct {
    char **items;
    int count;
    int capacity;
} StringList;

typedef struct {
    int exit_code;
    char *stdout_text;
    char *stderr_text;
} HelperResult;

static void list_init(StringList *list) {
    memset(list, 0, sizeof(*list));
}

static int list_add_owned(StringList *list, char *value) {
    if (list->count + 1 >= list->capacity) {
        int next_capacity = list->capacity == 0 ? 8 : list->capacity * 2;
        char **next = (char **)realloc(list->items, (size_t)next_capacity * sizeof(char *));
        if (!next) return -1;
        list->items = next;
        list->capacity = next_capacity;
    }
    list->items[list->count++] = value;
    list->items[list->count] = NULL;
    return 0;
}

static int list_add_copy(StringList *list, const char *value) {
    char *copy = strdup(value ? value : "");
    if (!copy) return -1;
    if (list_add_owned(list, copy) != 0) {
        free(copy);
        return -1;
    }
    return 0;
}

static void list_free(StringList *list) {
    for (int i = 0; i < list->count; i++) {
        free(list->items[i]);
    }
    free(list->items);
    memset(list, 0, sizeof(*list));
}

static void trim_newline(char *line) {
    size_t len = strlen(line);
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
        line[--len] = '\0';
    }
}

static int read_request_args(FILE *input, StringList *args) {
    char *line = NULL;
    size_t capacity = 0;
    while (1) {
        ssize_t read = getline(&line, &capacity, input);
        if (read < 0) {
            free(line);
            return 0;
        }
        trim_newline(line);
        if (line[0] == '\0') {
            free(line);
            return 1;
        }

        char *owned = line;
        line = NULL;
        capacity = 0;
        if (list_add_owned(args, owned) != 0) {
            free(owned);
            return 0;
        }
    }
}

static const char *map_daemon_command(const char *command) {
    if (strcmp(command, "c") == 0) return "compile";
    if (strcmp(command, "l") == 0) return "link";
    if (strcmp(command, "convert") == 0) return "convert";
    return NULL;
}

static int has_suffix(const char *value, const char *suffix) {
    if (!value || !suffix) return 0;
    size_t value_len = strlen(value);
    size_t suffix_len = strlen(suffix);
    if (suffix_len > value_len) return 0;
    return strcmp(value + value_len - suffix_len, suffix) == 0;
}

static int is_launcher_arg(const char *arg) {
    return has_suffix(arg, "/linker64") || has_suffix(arg, "/linker");
}

static int is_proxy_arg(const char *arg) {
    const char *env_proxy = getenv("MOBILESTUDIO_AAPT2_DAEMON_PROXY");
    return (env_proxy && env_proxy[0] && strcmp(arg, env_proxy) == 0) ||
            has_suffix(arg, "/libaapt2_daemon_proxy.so");
}

static char *join_path(const char *dir, const char *name) {
    size_t len = strlen(dir) + strlen(name) + 2;
    char *path = (char *)malloc(len);
    if (!path) return NULL;
    snprintf(path, len, "%s/%s", dir, name);
    return path;
}

static char *read_file_text(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return strdup("");

    struct stat st;
    size_t capacity = 4096;
    if (fstat(fd, &st) == 0 && st.st_size > 0) {
        capacity = (size_t)st.st_size + 1;
    }

    char *buffer = (char *)malloc(capacity);
    if (!buffer) {
        close(fd);
        return strdup("");
    }

    size_t used = 0;
    while (1) {
        if (used + 2048 + 1 > capacity) {
            capacity *= 2;
            char *next = (char *)realloc(buffer, capacity);
            if (!next) {
                free(buffer);
                close(fd);
                return strdup("");
            }
            buffer = next;
        }

        ssize_t read_count = read(fd, buffer + used, capacity - used - 1);
        if (read_count == 0) break;
        if (read_count < 0) {
            if (errno == EINTR) continue;
            free(buffer);
            close(fd);
            return strdup("");
        }
        used += (size_t)read_count;
    }
    close(fd);
    buffer[used] = '\0';
    return buffer;
}

static char *make_env_entry(const char *key, const char *value) {
    size_t len = strlen(key) + strlen(value) + 2;
    char *entry = (char *)malloc(len);
    if (!entry) return NULL;
    snprintf(entry, len, "%s=%s", key, value);
    return entry;
}

static int starts_with_key(const char *entry, const char *key) {
    size_t key_len = strlen(key);
    return strncmp(entry, key, key_len) == 0 && entry[key_len] == '=';
}

/* Returns a malloc'd copy of the libexec_redirect.so entry found inside an
   inherited LD_PRELOAD value, or NULL when it is absent. */
static char *find_exec_redirect_entry(const char *preload) {
    if (!preload || !preload[0]) return NULL;
    const char *hit = strstr(preload, "libexec_redirect.so");
    if (!hit) return NULL;
    const char *start = hit;
    while (start > preload && *(start - 1) != ':') start--;
    const char *end = hit + strlen("libexec_redirect.so");
    while (*end && *end != ':') end++;
    size_t len = (size_t)(end - start);
    char *entry = (char *)malloc(len + 1);
    if (!entry) return NULL;
    memcpy(entry, start, len);
    entry[len] = '\0';
    return entry;
}

static char **make_helper_env(
        const char *aapt2_path,
        const char *stdout_path,
        const char *stderr_path,
        const char *apk_path) {
    int base_count = 0;
    while (environ[base_count]) base_count++;

    char **env = (char **)calloc((size_t)base_count + 8, sizeof(char *));
    if (!env) return NULL;

    int out = 0;
    for (int i = 0; i < base_count; i++) {
        if (starts_with_key(environ[i], "MOBILESTUDIO_AAPT2_EXEC") ||
                starts_with_key(environ[i], "MOBILESTUDIO_AAPT2_STDOUT_FILE") ||
                starts_with_key(environ[i], "MOBILESTUDIO_AAPT2_STDERR_FILE") ||
                starts_with_key(environ[i], "CLASSPATH") ||
                starts_with_key(environ[i], "LD_PRELOAD")) {
            continue;
        }
        env[out++] = environ[i];
    }

    /* LD_PRELOAD: keep ONLY the exec-redirect hook.
     *
     * The helper runs under ART (app_process/dalvikvm): inheriting the Gradle
     * client's full JDK preload list there breaks native resolution
     * (system_class_loader abort). But the helper must still spawn aapt2 from
     * the noexec app-data mount, which requires libexec_redirect.so. */
    const char *redirect = getenv("MOBILESTUDIO_EXEC_REDIRECT");
    if (redirect && redirect[0]) {
        env[out++] = make_env_entry("LD_PRELOAD", redirect);
    } else {
        char *entry = find_exec_redirect_entry(getenv("LD_PRELOAD"));
        if (entry) {
            env[out++] = make_env_entry("LD_PRELOAD", entry);
            free(entry);
        }
    }

    env[out++] = make_env_entry("MOBILESTUDIO_AAPT2_EXEC", aapt2_path);
    env[out++] = make_env_entry("MOBILESTUDIO_AAPT2_STDOUT_FILE", stdout_path);
    env[out++] = make_env_entry("MOBILESTUDIO_AAPT2_STDERR_FILE", stderr_path);
    if (apk_path && apk_path[0]) {
        env[out++] = make_env_entry("CLASSPATH", apk_path);
    }
    env[out] = NULL;
    return env;
}

static void free_helper_env(char **env) {
    if (!env) return;
    for (int i = 0; env[i]; i++) {
        if (starts_with_key(env[i], "MOBILESTUDIO_AAPT2_EXEC") ||
                starts_with_key(env[i], "MOBILESTUDIO_AAPT2_STDOUT_FILE") ||
                starts_with_key(env[i], "MOBILESTUDIO_AAPT2_STDERR_FILE") ||
                starts_with_key(env[i], "CLASSPATH") ||
                starts_with_key(env[i], "LD_PRELOAD")) {
            free(env[i]);
        }
    }
    free(env);
}

static char **make_dalvik_argv(const char *apk_path, const char *aapt2_path, char **args, int arg_count) {
    int total = 5 + arg_count;
    char **argv = (char **)calloc((size_t)total + 1, sizeof(char *));
    if (!argv) return NULL;
    int index = 0;
    argv[index++] = "/system/bin/dalvikvm";
    argv[index++] = "-cp";
    argv[index++] = (char *)apk_path;
    argv[index++] = "com.aide.mobilestudio.Aapt2DaemonProxy";
    argv[index++] = (char *)aapt2_path;
    for (int i = 0; i < arg_count; i++) argv[index++] = args[i];
    argv[index] = NULL;
    return argv;
}

static char **make_app_process_argv(const char *aapt2_path, char **args, int arg_count) {
    int total = 6 + arg_count;
    char **argv = (char **)calloc((size_t)total + 1, sizeof(char *));
    if (!argv) return NULL;
    int index = 0;
    argv[index++] = "/system/bin/app_process64";
    argv[index++] = "/system/bin";
    argv[index++] = "--application";
    argv[index++] = "--nice-name=mobilestudio_aapt2_helper";
    argv[index++] = "com.aide.mobilestudio.Aapt2DaemonProxy";
    argv[index++] = (char *)aapt2_path;
    for (int i = 0; i < arg_count; i++) argv[index++] = args[i];
    argv[index] = NULL;
    return argv;
}

static int wait_exit_code(pid_t pid) {
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return 127;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 127;
}

static int spawn_helper(
        const char *aapt2_path,
        char **args,
        int arg_count,
        const char *stdout_path,
        const char *stderr_path) {
    const char *apk_path = getenv("MOBILESTUDIO_APK_PATH");
    if (!apk_path || !apk_path[0]) return 127;

    char **env = make_helper_env(aapt2_path, stdout_path, stderr_path, apk_path);
    char **dalvik_argv = make_dalvik_argv(apk_path, aapt2_path, args, arg_count);
    char **app_argv = make_app_process_argv(aapt2_path, args, arg_count);
    if (!env || !dalvik_argv || !app_argv) {
        free_helper_env(env);
        free(dalvik_argv);
        free(app_argv);
        return 127;
    }

    pid_t pid = fork();
    if (pid < 0) {
        free_helper_env(env);
        free(dalvik_argv);
        free(app_argv);
        return 127;
    }

    if (pid == 0) {
        int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (null_fd >= 0) {
            dup2(null_fd, STDOUT_FILENO);
            dup2(null_fd, STDERR_FILENO);
            close(null_fd);
        }

        if (access("/system/bin/dalvikvm", X_OK) == 0) {
            execve("/system/bin/dalvikvm", dalvik_argv, env);

            int saved_errno = errno;
            __android_log_print(
                ANDROID_LOG_ERROR,
                "MobileStudioAapt2",
                "dalvikvm exec failed: errno=%d (%s)",
                saved_errno,
                strerror(saved_errno)
            );
        }

        const char *app_process = access("/system/bin/app_process64", X_OK) == 0
                ? "/system/bin/app_process64"
                : "/system/bin/app_process";
        app_argv[0] = (char *)app_process;

        execve(app_process, app_argv, env);

        int saved_errno = errno;
        __android_log_print(
            ANDROID_LOG_ERROR,
            "MobileStudioAapt2",
            "app_process exec failed: errno=%d (%s)",
            saved_errno,
            strerror(saved_errno)
        );

        _exit(127);
    }

    int exit_code = wait_exit_code(pid);
    free_helper_env(env);
    free(dalvik_argv);
    free(app_argv);
    return exit_code;
}

static HelperResult run_helper(const char *aapt2_path, char **args, int arg_count) {
    HelperResult result;
    memset(&result, 0, sizeof(result));
    result.exit_code = 127;

    const char *tmp_dir = getenv("TMPDIR");
    if (!tmp_dir || !tmp_dir[0]) tmp_dir = "/data/local/tmp";

    char stdout_name[128];
    char stderr_name[128];
    static int sequence = 0;
    int id = sequence++;
    snprintf(stdout_name, sizeof(stdout_name), "mobilestudio-aapt2-%d-%d.out", getpid(), id);
    snprintf(stderr_name, sizeof(stderr_name), "mobilestudio-aapt2-%d-%d.err", getpid(), id);

    char *stdout_path = join_path(tmp_dir, stdout_name);
    char *stderr_path = join_path(tmp_dir, stderr_name);
    if (!stdout_path || !stderr_path) {
        free(stdout_path);
        free(stderr_path);
        result.stdout_text = strdup("");
        result.stderr_text = strdup("aapt2_proxy: failed to allocate temp paths\n");
        return result;
    }

    unlink(stdout_path);
    unlink(stderr_path);
    result.exit_code = spawn_helper(aapt2_path, args, arg_count, stdout_path, stderr_path);
    result.stdout_text = read_file_text(stdout_path);
    result.stderr_text = read_file_text(stderr_path);
    unlink(stdout_path);
    unlink(stderr_path);
    free(stdout_path);
    free(stderr_path);

    if (!result.stdout_text) result.stdout_text = strdup("");
    if (!result.stderr_text) result.stderr_text = strdup("");
    return result;
}

static void free_result(HelperResult *result) {
    free(result->stdout_text);
    free(result->stderr_text);
    memset(result, 0, sizeof(*result));
}

static void print_text(FILE *stream, const char *text) {
    if (text && text[0]) {
        fputs(text, stream);
        size_t len = strlen(text);
        if (len > 0 && text[len - 1] != '\n') fputc('\n', stream);
        fflush(stream);
    }
}

static void print_prefixed_lines(FILE *stream, const char *prefix, const char *text) {
    if (!text || !text[0]) return;

    const char *cursor = text;
    while (*cursor) {
        const char *newline = strchr(cursor, '\n');
        fputs(prefix, stream);
        if (newline) {
            fwrite(cursor, 1, (size_t)(newline - cursor), stream);
            fputc('\n', stream);
            cursor = newline + 1;
        } else {
            fputs(cursor, stream);
            fputc('\n', stream);
            break;
        }
    }
    fflush(stream);
}

static int run_single_shot(const char *aapt2_path, char **args, int arg_count) {
    HelperResult result = run_helper(aapt2_path, args, arg_count);
    print_text(stdout, result.stdout_text);
    print_text(stderr, result.stderr_text);
    int exit_code = result.exit_code;
    free_result(&result);
    return exit_code;
}

static void run_daemon_request(const char *aapt2_path, const char *subcommand, StringList *request_args) {
    StringList one_shot;
    list_init(&one_shot);
    if (list_add_copy(&one_shot, subcommand) != 0) {
        fprintf(stderr, "Error\naapt2_proxy: out of memory\nDone\n");
        fflush(stderr);
        return;
    }
    for (int i = 0; i < request_args->count; i++) {
        if (list_add_copy(&one_shot, request_args->items[i]) != 0) {
            list_free(&one_shot);
            fprintf(stderr, "Error\naapt2_proxy: out of memory\nDone\n");
            fflush(stderr);
            return;
        }
    }

    HelperResult result = run_helper(aapt2_path, one_shot.items, one_shot.count);
    if (result.exit_code == 0) {
        print_text(stdout, result.stdout_text);
        print_prefixed_lines(stderr, "stderr: ", result.stderr_text);
        fprintf(stderr, "Done\n");
    } else {
        fprintf(stderr, "Error\n");
        print_prefixed_lines(stderr, "stdout: ", result.stdout_text);
        print_prefixed_lines(stderr, "stderr: ", result.stderr_text);
        fprintf(stderr, "aapt2_proxy: aapt2 %s exited with %d\n", subcommand, result.exit_code);
        fprintf(stderr, "Done\n");
    }
    fflush(stdout);
    fflush(stderr);
    free_result(&result);
    list_free(&one_shot);
}

static int run_daemon(const char *aapt2_path) {
    fprintf(stdout, "Ready\n");
    fflush(stdout);

    char *command = NULL;
    size_t capacity = 0;
    while (1) {
        ssize_t read = getline(&command, &capacity, stdin);
        if (read < 0) {
            free(command);
            return 0;
        }
        trim_newline(command);
        if (command[0] == '\0') continue;

        StringList request_args;
        list_init(&request_args);
        read_request_args(stdin, &request_args);

        if (strcmp(command, "quit") == 0) {
            list_free(&request_args);
            fprintf(stdout, "Exiting daemon\n");
            fflush(stdout);
            free(command);
            return 0;
        }

        const char *subcommand = map_daemon_command(command);
        if (!subcommand) {
            fprintf(stderr, "Error\naapt2_proxy: unsupported daemon command: %s\nDone\n", command);
            fflush(stderr);
            list_free(&request_args);
            continue;
        }

        run_daemon_request(aapt2_path, subcommand, &request_args);
        list_free(&request_args);
    }
}

static int aapt2_daemon_proxy_main(int argc, char **argv) {
    const char *env_aapt2_path = getenv("MOBILESTUDIO_AAPT2_EXEC");
    const char *aapt2_path = NULL;
    int first_aapt2_arg = 0;

    for (int i = 0; i < argc; i++) {
        if (!argv[i] || !argv[i][0]) continue;
        if (is_launcher_arg(argv[i]) || is_proxy_arg(argv[i])) {
            if (first_aapt2_arg == i) first_aapt2_arg = i + 1;
            continue;
        }
        if (argv[i][0] == '/') {
            aapt2_path = argv[i];
            first_aapt2_arg = i + 1;
            break;
        }
        if (first_aapt2_arg == i) {
            first_aapt2_arg = i;
            break;
        }
    }

    if ((!aapt2_path || !aapt2_path[0]) && env_aapt2_path && env_aapt2_path[0]) {
        aapt2_path = env_aapt2_path;
    }

    if (!aapt2_path || !aapt2_path[0]) {
        fprintf(stderr, "aapt2_proxy: missing aapt2 path (argc=%d)\n", argc);
        return 127;
    }

    while (first_aapt2_arg < argc && argv[first_aapt2_arg] &&
            (is_launcher_arg(argv[first_aapt2_arg]) || is_proxy_arg(argv[first_aapt2_arg]))) {
        first_aapt2_arg++;
    }

    int forwarded_argc = argc - first_aapt2_arg;
    if (forwarded_argc <= 0 || (forwarded_argc == 1 && strcmp(argv[first_aapt2_arg], "m") == 0)) {
        return run_daemon(aapt2_path);
    }

    return run_single_shot(aapt2_path, argv + first_aapt2_arg, forwarded_argc);
}

int main(int argc, char **argv) {
    return aapt2_daemon_proxy_main(argc, argv);
}
