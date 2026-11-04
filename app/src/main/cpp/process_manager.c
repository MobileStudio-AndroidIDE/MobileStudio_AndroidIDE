#include "process_manager.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <linux/memfd.h>
#include <sys/syscall.h>

#define TAG "ProcessManager"

#define EXEC_RESULT_OK      0
#define EXEC_RESULT_FAIL    1
#define EXEC_RESULT_EACCES  2
#define EXEC_RESULT_ENOEXEC 3

static int set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static void reap_children(void) {
    int status;
    while (waitpid(-1, &status, WNOHANG) > 0) {}
}

int pm_reap_zombies(void) {
    int reaped = 0;
    int status;
    pid_t pid;
    while ((pid = waitpid(-1, &status, WNOHANG)) > 0) reaped++;
    return reaped;
}

int pm_set_rlimits(const pm_rlimits_t *cfg) {
    if (!cfg) return 0;

    struct rlimit rl;
    if (cfg->have_cpu) {
        rl.rlim_cur = cfg->cpu_limit;
        rl.rlim_max = cfg->cpu_limit;
        setrlimit(RLIMIT_CPU, &rl);
    }
    if (cfg->have_nofile) {
        rl.rlim_cur = cfg->nofile_limit;
        rl.rlim_max = cfg->nofile_limit;
        setrlimit(RLIMIT_NOFILE, &rl);
    }
    if (cfg->have_nproc) {
        rl.rlim_cur = cfg->nproc_limit;
        rl.rlim_max = cfg->nproc_limit;
        setrlimit(RLIMIT_NPROC, &rl);
    }
    if (cfg->have_as) {
        rl.rlim_cur = cfg->as_limit;
        rl.rlim_max = cfg->as_limit;
        setrlimit(RLIMIT_AS, &rl);
    }
    return 0;
}

int pm_close_extra_fds(const int *keep, int keep_count) {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return -errno;

    int closed = 0;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        if (ent->d_name[0] == '.') continue;
        char *end = NULL;
        long fd = strtol(ent->d_name, &end, 10);
        if (!end || *end != '\0' || fd < 3) continue;

        int keep_it = 0;
        for (int i = 0; i < keep_count; i++) {
            if (keep[i] == (int)fd) { keep_it = 1; break; }
        }
        if (!keep_it) {
            close((int)fd);
            closed++;
        }
    }
    closedir(dir);
    return closed;
}

static int memfd_create_wrapper(const char *name, unsigned int flags) {
    return (int)syscall(__NR_memfd_create, name, flags);
}

static int try_copy_to_memfd(const char *path, int *out_fd) {
    int src_fd = open(path, O_RDONLY);
    if (src_fd < 0) return -1;

    struct stat st;
    if (fstat(src_fd, &st) < 0) {
        close(src_fd);
        return -1;
    }

    int mfd = memfd_create_wrapper("exec_cache", MFD_CLOEXEC);
    if (mfd < 0) {
        close(src_fd);
        return -1;
    }

    char buf[65536];
    ssize_t total = 0;
    while (total < st.st_size) {
        ssize_t n = read(src_fd, buf, sizeof(buf));
        if (n <= 0) break;
        ssize_t w = write(mfd, buf, n);
        if (w != n) {
            close(src_fd);
            close(mfd);
            return -1;
        }
        total += n;
    }
    close(src_fd);

    if (total == 0) {
        close(mfd);
        return -1;
    }

    *out_fd = mfd;
    return 0;
}

static int try_copy_to_codecache(const char *path, const char *code_cache_dir, char *out_path, size_t out_sz) {
    if (!code_cache_dir || !code_cache_dir[0]) return -1;

    char cache_dir[1024];
    snprintf(cache_dir, sizeof(cache_dir), "%s/exec_cache", code_cache_dir);
    mkdir(cache_dir, 0755);

    const char *base = basename(path);
    snprintf(out_path, out_sz, "%s/%s", cache_dir, base);

    int src_fd = open(path, O_RDONLY);
    if (src_fd < 0) return -1;

    int dst_fd = open(out_path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (dst_fd < 0) {
        close(src_fd);
        return -1;
    }

    char buf[65536];
    ssize_t n;
    while ((n = read(src_fd, buf, sizeof(buf))) > 0) {
        ssize_t w = write(dst_fd, buf, n);
        if (w != n) {
            close(src_fd);
            close(dst_fd);
            return -1;
        }
    }
    close(src_fd);
    close(dst_fd);

    chmod(out_path, 0755);
    return 0;
}

static int try_chmod_exec(const char *path) {
    struct stat st;
    if (stat(path, &st) < 0) return -1;
    if (st.st_mode & S_IXUSR) return 0;
    return chmod(path, st.st_mode | S_IXUSR | S_IXGRP | S_IXOTH);
}

static int try_linker64_exec(const char *path, char *const *argv,
                              char *const *envp, const char *cwd,
                              int create_process_group) {
    const char *linker = "/system/bin/linker64";
    if (access(linker, X_OK) < 0) return -1;

    const char *new_argv[PM_MAX_ARGS];
    new_argv[0] = linker;
    new_argv[1] = path;
    int i = 2;
    for (int j = 1; argv[j] && i < PM_MAX_ARGS - 1; j++) {
        new_argv[i++] = argv[j];
    }
    new_argv[i] = NULL;

    if (create_process_group) setpgid(0, 0);
    if (cwd && cwd[0] && chdir(cwd) < 0) {
        fprintf(stderr, "chdir(%s) failed: %s\n", cwd, strerror(errno));
        _exit(126);
    }

    execve(linker, (char *const *)new_argv, envp);
    _exit(127);
    return -1;
}

static int try_memfd_exec(const char *path, char *const *argv,
                           char *const *envp, const char *cwd,
                           int create_process_group) {
    int mfd = -1;
    if (try_copy_to_memfd(path, &mfd) < 0) return -1;

    char fd_path[64];
    snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", mfd);

    if (create_process_group) setpgid(0, 0);
    if (cwd && cwd[0] && chdir(cwd) < 0) {
        fprintf(stderr, "chdir(%s) failed: %s\n", cwd, strerror(errno));
        close(mfd);
        _exit(126);
    }

    execve(fd_path, argv, envp);
    int saved_errno = errno;
    close(mfd);
    errno = saved_errno;
    return -1;
}

static int try_codecache_exec(const char *path, const char *code_cache_dir,
                               char *const *argv, char *const *envp,
                               const char *cwd, int create_process_group) {
    char cached_path[1024];
    if (try_copy_to_codecache(path, code_cache_dir, cached_path, sizeof(cached_path)) < 0)
        return -1;

    if (create_process_group) setpgid(0, 0);
    if (cwd && cwd[0] && chdir(cwd) < 0) {
        fprintf(stderr, "chdir(%s) failed: %s\n", cwd, strerror(errno));
        _exit(126);
    }

    execve(cached_path, argv, envp);
    _exit(127);
    return -1;
}

static int try_direct_exec(const char *path, char *const *argv,
                            char *const *envp, const char *cwd,
                            int create_process_group) {
    if (create_process_group) setpgid(0, 0);
    if (cwd && cwd[0] && chdir(cwd) < 0) {
        fprintf(stderr, "chdir(%s) failed: %s\n", cwd, strerror(errno));
        _exit(126);
    }

    execve(path, argv, envp);
    return -1;
}

static int detect_exec_strategy(const char *path) {
    if (access(path, X_OK) == 0) return 0;

    struct stat st;
    if (stat(path, &st) < 0) return -1;
    if (!(st.st_mode & S_IXUSR)) return 1;
    return 2;
}

int pm_spawn(const pm_exec_args_t *args, pm_handle_t *handle) {
    if (!args || !args->executable || !handle) return -EINVAL;
    memset(handle, 0, sizeof(pm_handle_t));
    handle->stdout_fd = -1;
    handle->stderr_fd = -1;
    handle->stdin_fd = -1;

    int sync_pipe[2] = {-1, -1};
    if (pipe2(sync_pipe, O_CLOEXEC) < 0) {
        snprintf(handle->error, sizeof(handle->error),
                 "pipe2(sync) failed: %s", strerror(errno));
        return -errno;
    }

    int stdout_pipe[2] = {-1, -1};
    int stderr_pipe[2] = {-1, -1};
    int stdin_pipe[2] = {-1, -1};

    if (pipe2(stdout_pipe, O_CLOEXEC) < 0) {
        close(sync_pipe[0]); close(sync_pipe[1]);
        snprintf(handle->error, sizeof(handle->error),
                 "pipe2(stdout) failed: %s", strerror(errno));
        return -errno;
    }
    if (pipe2(stderr_pipe, O_CLOEXEC) < 0) {
        close(sync_pipe[0]); close(sync_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        snprintf(handle->error, sizeof(handle->error),
                 "pipe2(stderr) failed: %s", strerror(errno));
        return -errno;
    }
    if (pipe2(stdin_pipe, O_CLOEXEC) < 0) {
        close(sync_pipe[0]); close(sync_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);
        snprintf(handle->error, sizeof(handle->error),
                 "pipe2(stdin) failed: %s", strerror(errno));
        return -errno;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(sync_pipe[0]); close(sync_pipe[1]);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stderr_pipe[0]); close(stderr_pipe[1]);
        close(stdin_pipe[0]); close(stdin_pipe[1]);
        snprintf(handle->error, sizeof(handle->error),
                 "fork failed: %s", strerror(errno));
        return -errno;
    }

    if (pid == 0) {
        close(sync_pipe[0]);
        close(stdout_pipe[0]);
        close(stderr_pipe[0]);
        close(stdin_pipe[1]);

        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stderr_pipe[1], STDERR_FILENO);
        dup2(stdin_pipe[0],  STDIN_FILENO);
        close(stdout_pipe[1]);
        close(stderr_pipe[1]);
        close(stdin_pipe[0]);

        pm_set_rlimits(args->rlimits);

        /* Close inherited fds leaked from the parent, keeping only the sync
         * pipe needed for exec-failure signalling. */
        {
            int keep[1] = { sync_pipe[1] };
            pm_close_extra_fds(keep, 1);
        }

        char *const *envp = args->envp ? args->envp : environ;
        const char *path = args->executable;
        char *const *argv = args->argv;
        const char *cwd = args->cwd;
        int pg = args->create_process_group;

        if (access(path, X_OK) == 0) {
            if (pg) setpgid(0, 0);
            if (cwd && cwd[0] && chdir(cwd) < 0) {
                fprintf(stderr, "chdir(%s) failed: %s\n", cwd, strerror(errno));
                _exit(126);
            }
            execve(path, argv, envp);
            int e = errno;
            if (e == EACCES || e == EPERM) {
                write(sync_pipe[1], "E", 1);
                close(sync_pipe[1]);
                _exit(127);
            }
            write(sync_pipe[1], "F", 1);
            close(sync_pipe[1]);
            _exit(127);
        }

        struct stat st;
        if (stat(path, &st) == 0 && !(st.st_mode & S_IXUSR)) {
            chmod(path, st.st_mode | S_IXUSR | S_IXGRP | S_IXOTH);
            if (access(path, X_OK) == 0) {
                if (pg) setpgid(0, 0);
                if (cwd && cwd[0] && chdir(cwd) < 0) _exit(126);
                execve(path, argv, envp);
            }
        }

        int mfd = -1;
        if (try_copy_to_memfd(path, &mfd) == 0) {
            char fd_path[64];
            snprintf(fd_path, sizeof(fd_path), "/proc/self/fd/%d", mfd);
            if (pg) setpgid(0, 0);
            if (cwd && cwd[0] && chdir(cwd) < 0) { close(mfd); _exit(126); }
            execve(fd_path, argv, envp);
            close(mfd);
        }

        if (args->code_cache_dir && args->code_cache_dir[0]) {
            char cached[1024];
            if (try_copy_to_codecache(path, args->code_cache_dir, cached, sizeof(cached)) == 0) {
                if (pg) setpgid(0, 0);
                if (cwd && cwd[0] && chdir(cwd) < 0) _exit(126);
                execve(cached, argv, envp);
            }
        }

        if (pg) setpgid(0, 0);
        if (cwd && cwd[0] && chdir(cwd) < 0) _exit(126);
        execve(path, argv, envp);
        _exit(127);
    }

    close(sync_pipe[1]);
    close(stdout_pipe[1]);
    close(stderr_pipe[1]);
    close(stdin_pipe[0]);

    char sync_buf[4] = {0};
    int sync_result = read(sync_pipe[0], sync_buf, 1);
    close(sync_pipe[0]);

    if (sync_result == 1) {
        int status;
        waitpid(pid, &status, 0);
        close(stdout_pipe[0]);
        close(stderr_pipe[0]);
        close(stdin_pipe[1]);

        if (sync_buf[0] == 'E') {
            snprintf(handle->error, sizeof(handle->error),
                     "Permission denied: %s (EACCES)", args->executable);
            return -EACCES;
        }
        snprintf(handle->error, sizeof(handle->error),
                 "exec failed: %s", args->executable);
        return -ENOEXEC;
    }

    handle->pid = pid;
    handle->stdout_fd = stdout_pipe[0];
    handle->stderr_fd = stderr_pipe[0];
    handle->stdin_fd  = stdin_pipe[1];

    set_nonblocking(handle->stdout_fd);
    set_nonblocking(handle->stderr_fd);

    return 0;
}

int pm_read_stdout(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms) {
    if (!handle || handle->stdout_fd < 0) return -EINVAL;
    if (timeout_ms > 0) {
        struct pollfd pfd = {.fd = handle->stdout_fd, .events = POLLIN};
        int r = poll(&pfd, 1, timeout_ms);
        if (r == 0) return 0;
        if (r < 0 && errno != EINTR) return -errno;
    }
    ssize_t n = read(handle->stdout_fd, buf, buf_sz - 1);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        return -errno;
    }
    if (n == 0) return 0;
    buf[n] = '\0';
    return (int)n;
}

int pm_read_stderr(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms) {
    if (!handle || handle->stderr_fd < 0) return -EINVAL;
    if (timeout_ms > 0) {
        struct pollfd pfd = {.fd = handle->stderr_fd, .events = POLLIN};
        int r = poll(&pfd, 1, timeout_ms);
        if (r == 0) return 0;
        if (r < 0 && errno != EINTR) return -errno;
    }
    ssize_t n = read(handle->stderr_fd, buf, buf_sz - 1);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        return -errno;
    }
    if (n == 0) return 0;
    buf[n] = '\0';
    return (int)n;
}

int pm_read_stdout_line(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms) {
    if (!handle || handle->stdout_fd < 0) return -EINVAL;
    size_t pos = 0;
    while (pos < buf_sz - 1) {
        if (timeout_ms > 0) {
            struct pollfd pfd = {.fd = handle->stdout_fd, .events = POLLIN};
            int r = poll(&pfd, 1, timeout_ms);
            if (r == 0) {
                if (pos > 0) { buf[pos] = '\0'; return (int)pos; }
                return 0;
            }
            if (r < 0 && errno != EINTR) return -errno;
        }
        char c;
        ssize_t n = read(handle->stdout_fd, &c, 1);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (pos > 0) { buf[pos] = '\0'; return (int)pos; }
                return 0;
            }
            return -errno;
        }
        if (n == 0) break;
        buf[pos++] = c;
        if (c == '\n') break;
    }
    buf[pos] = '\0';
    return (int)pos;
}

int pm_read_stderr_line(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms) {
    if (!handle || handle->stderr_fd < 0) return -EINVAL;
    size_t pos = 0;
    while (pos < buf_sz - 1) {
        if (timeout_ms > 0) {
            struct pollfd pfd = {.fd = handle->stderr_fd, .events = POLLIN};
            int r = poll(&pfd, 1, timeout_ms);
            if (r == 0) {
                if (pos > 0) { buf[pos] = '\0'; return (int)pos; }
                return 0;
            }
            if (r < 0 && errno != EINTR) return -errno;
        }
        char c;
        ssize_t n = read(handle->stderr_fd, &c, 1);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (pos > 0) { buf[pos] = '\0'; return (int)pos; }
                return 0;
            }
            return -errno;
        }
        if (n == 0) break;
        buf[pos++] = c;
        if (c == '\n') break;
    }
    buf[pos] = '\0';
    return (int)pos;
}

int pm_wait(pm_handle_t *handle) {
    if (!handle || handle->pid <= 0) return -EINVAL;
    int status;
    pid_t ret = waitpid(handle->pid, &status, 0);
    if (ret < 0) return -errno;
    handle->finished = 1;
    if (WIFEXITED(status)) {
        handle->exit_code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        handle->signal = WTERMSIG(status);
        handle->exit_code = 128 + handle->signal;
    }
    return handle->exit_code;
}

int pm_wait_timeout(pm_handle_t *handle, int timeout_ms) {
    if (!handle || handle->pid <= 0) return -EINVAL;
    if (timeout_ms <= 0) return pm_wait(handle);

    int elapsed = 0;
    int interval = 50;
    while (elapsed < timeout_ms) {
        int status;
        pid_t ret = waitpid(handle->pid, &status, WNOHANG);
        if (ret > 0) {
            handle->finished = 1;
            if (WIFEXITED(status)) {
                handle->exit_code = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                handle->signal = WTERMSIG(status);
                handle->exit_code = 128 + handle->signal;
            }
            return handle->exit_code;
        }
        if (ret < 0 && errno != ECHILD) return -errno;
        usleep(interval * 1000);
        elapsed += interval;
    }

    pm_kill(handle, SIGTERM);
    usleep(100 * 1000);
    pm_kill(handle, SIGKILL);
    waitpid(handle->pid, NULL, 0);
    handle->finished = 1;
    handle->signal = SIGKILL;
    handle->exit_code = 137;
    return 137;
}

int pm_kill(pm_handle_t *handle, int signal) {
    if (!handle || handle->pid <= 0) return -EINVAL;
    if (kill(handle->pid, signal) < 0) return -errno;
    return 0;
}

int pm_kill_process_group(pm_handle_t *handle, int signal) {
    if (!handle || handle->pid <= 0) return -EINVAL;
    if (kill(-handle->pid, signal) < 0) return -errno;
    return 0;
}

void pm_close(pm_handle_t *handle) {
    if (!handle) return;
    if (handle->stdout_fd >= 0) { close(handle->stdout_fd); handle->stdout_fd = -1; }
    if (handle->stderr_fd >= 0) { close(handle->stderr_fd); handle->stderr_fd = -1; }
    if (handle->stdin_fd >= 0)  { close(handle->stdin_fd);  handle->stdin_fd  = -1; }
    if (handle->pid > 0) {
        pm_wait_timeout(handle, 1000);
        handle->pid = 0;
    }
}
