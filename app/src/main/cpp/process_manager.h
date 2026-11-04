#ifndef PROCESS_MANAGER_H
#define PROCESS_MANAGER_H

#include <stddef.h>
#include <sys/resource.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PM_MAX_ARGS   256
#define PM_MAX_ENV    256
#define PM_BUF_SIZE   65536
#define PM_LINE_MAX   8192

typedef struct {
    pid_t pid;
    int stdout_fd;
    int stderr_fd;
    int stdin_fd;
    int exit_code;
    int signal;
    int finished;
    char error[1024];
} pm_handle_t;

/* Optional resource limits applied in the child before execve(). A limit is
 * only applied when its `have_*` flag is non-zero. */
typedef struct {
    int    have_cpu;      rlim_t cpu_limit;
    int    have_nofile;   rlim_t nofile_limit;
    int    have_nproc;    rlim_t nproc_limit;
    int    have_as;       rlim_t as_limit;
} pm_rlimits_t;

typedef struct {
    const char *executable;
    char *const *argv;
    char *const *envp;
    const char *cwd;
    const char *code_cache_dir;
    int timeout_ms;
    int create_process_group;
    const pm_rlimits_t *rlimits;
} pm_exec_args_t;

int pm_spawn(const pm_exec_args_t *args, pm_handle_t *handle);
int pm_read_stdout(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms);
int pm_read_stderr(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms);
int pm_read_stdout_line(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms);
int pm_read_stderr_line(pm_handle_t *handle, char *buf, size_t buf_sz, int timeout_ms);
int pm_wait(pm_handle_t *handle);
int pm_wait_timeout(pm_handle_t *handle, int timeout_ms);
int pm_kill(pm_handle_t *handle, int signal);
int pm_kill_process_group(pm_handle_t *handle, int signal);
void pm_close(pm_handle_t *handle);

/* Apply optional resource limits via setrlimit(). Returns 0 on success,
 * negative errno on failure. Safe to call only inside the child process. */
int pm_set_rlimits(const pm_rlimits_t *cfg);

/* Close every open file descriptor >= 3 except those listed in `keep`.
 * Prevents FD leak into exec'd children. Returns number of closed fds, or
 * negative errno on failure. */
int pm_close_extra_fds(const int *keep, int keep_count);

/* Reap any already-exited children (zombie cleanup). Returns the number of
 * children reaped. */
int pm_reap_zombies(void);

#ifdef __cplusplus
}
#endif

#endif
