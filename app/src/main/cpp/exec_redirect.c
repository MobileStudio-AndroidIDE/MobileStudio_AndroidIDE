#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

#ifndef R_AARCH64_JUMP_SLOT
#define R_AARCH64_JUMP_SLOT 1026
#endif

/*
 * MobileStudio Exec Redirect
 *
 * Android / arm64-v8a
 *
 * Main purpose:
 *
 *   /data/... noexec executable
 *          |
 *          +--> memfd
 *          |
 *          +--> execveat(fd, "", ..., AT_EMPTY_PATH)
 *          |
 *          +--> fexecve() -> execveat()
 *          |
 *          +--> /system/bin/linker64 <target> <args...>
 *          |
 *          +--> /proc/self/fd/<fd>        LAST RESORT ONLY
 *
 * Important:
 *
 *   /proc/self/fd/<memfd> must NOT be the primary execution path.
 *
 * Android 16 may reject:
 *
 *   execve("/proc/self/fd/<memfd>", ...)
 *
 * with:
 *
 *   EACCES
 *
 * even though the memfd itself was successfully created.
 *
 * posix_spawn() is handled separately because its API cannot directly
 * express execveat(AT_EMPTY_PATH). For spawn, linker64 is the primary
 * noexec workaround.
 */

#define TAG "ExecRedirect"

/*
 * Logging stays disabled until the constructor runs.
 *
 * The wrapper script exports LD_PRELOAD (JDK libs + this library), so
 * fresh processes (sh, linker64, the Gradle daemon) load
 * libexec_redirect.so at exec time.  Its interposed mprotect() is then
 * first called from libc's own __libc_preinit_impl (global init)
 * BEFORE any constructor has run.  __android_log_print in that window
 * crashes: liblog's __android_log_global_level dereferences
 * not-yet-initialized state (strncmp(NULL) -> SIGSEGV inside the
 * linker constructor phase), killing the process before any of our
 * code logs a single line.  Gate every LOG macro until the constructor
 * has marked libc/log state usable.
 */
static volatile int exec_redirect_ctor_done = 0;

#define LOGI(...) do { if (exec_redirect_ctor_done) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); } while (0)
#define LOGE(...) do { if (exec_redirect_ctor_done) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); } while (0)
#define LOGV(...) do { if (exec_redirect_ctor_done) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__); } while (0)

/* ============================================================
 * JLI runtime API
 * ============================================================ */

typedef int  (*jli_resolve_java_home_fn)(
        const char *,
        char *const[],
        char *,
        size_t);

typedef void (*jli_apply_child_env_fn)(
        char *const[]);

typedef void (*jli_prepend_ld_paths_fn)(
        const char *);

typedef int (*jli_preload_libs_fn)(
        const char *);

typedef int (*jli_disable_heap_tagging_fn)(
        void);

typedef int (*jli_launch_in_process_fn)(
        const char *,
        char *const[],
        char *const[],
        const char *);

static struct {
    void *handle;

    jli_resolve_java_home_fn
        resolve_java_home;

    jli_apply_child_env_fn
        apply_child_env;

    jli_prepend_ld_paths_fn
        prepend_ld_paths;

    jli_preload_libs_fn
        preload_libs;

    jli_disable_heap_tagging_fn
        disable_heap_tagging;

    jli_launch_in_process_fn
        launch_in_process;

} g_jli;

/* ============================================================
 * Android / Linux constants
 * ============================================================ */

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

#ifndef AT_FDCWD
#define AT_FDCWD (-100)
#endif

#ifndef AT_EMPTY_PATH
#define AT_EMPTY_PATH 0x1000
#endif

/*
 * arm64 syscall numbers.
 *
 * MobileStudio currently targets arm64-v8a.
 */
#ifndef __NR_memfd_create
#define __NR_memfd_create 279
#endif

#ifndef __NR_execveat
#define __NR_execveat 281
#endif

#ifndef M_HEAP_TAGGING_LEVEL_NONE
#define M_HEAP_TAGGING_LEVEL_NONE 0
#endif

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-105)
#endif

extern char **environ;

/* ============================================================
 * Function pointer types
 * ============================================================ */

typedef int (*execve_fn)(
        const char *,
        char *const[],
        char *const[]);

typedef int (*access_fn)(
        const char *,
        int);

typedef int (*faccessat_fn)(
        int,
        const char *,
        int,
        int);

typedef void *(*mmap_fn)(
        void *,
        size_t,
        int,
        int,
        int,
        off_t);

typedef int (*mprotect_fn)(
        void *,
        size_t,
        int);

typedef int (*posix_spawn_fn)(
        pid_t *,
        const char *,
        const posix_spawn_file_actions_t *,
        const posix_spawnattr_t *,
        char *const[],
        char *const[]);

typedef int (*posix_spawnp_fn)(
        pid_t *,
        const char *,
        const posix_spawn_file_actions_t *,
        const posix_spawnattr_t *,
        char *const[],
        char *const[]);

typedef pid_t (*fork_fn)(void);

typedef ssize_t (*write_fn)(int, const void *, size_t);
typedef ssize_t (*read_fn)(int, void *, size_t);
typedef ssize_t (*writev_fn)(int, const struct iovec *, int);

/* ============================================================
 * Real libc symbols
 * ============================================================ */

static execve_fn real_execve = NULL;
static access_fn real_access = NULL;
static faccessat_fn real_faccessat = NULL;
static mmap_fn real_mmap = NULL;
static mprotect_fn real_mprotect = NULL;
static posix_spawn_fn real_posix_spawn = NULL;
static posix_spawnp_fn real_posix_spawnp = NULL;
static fork_fn real_fork = NULL;
static write_fn real_write = NULL;
static read_fn real_read = NULL;
static writev_fn real_writev = NULL;

static int exec_redirect_init_done = 0;

/* ============================================================
 * Forward declarations
 * ============================================================ */

static void init_real_symbols(void);

static int is_elf(const char *path);
static int is_arm64_elf(const char *path);
static int has_interp(const char *path);
static int is_noexec_path(const char *path);
static int is_preload_path(const char *path);
static int is_native_build_tool(const char *path);
static int is_jdk_tool(const char *path);
static int is_shell_script(const char *path);

static int copy_to_memfd(const char *path);
static int copy_to_inheritable_memfd(const char *path);

static int syscall_memfd_create(
        const char *name,
        unsigned int flags);

static int syscall_execveat(
        int dirfd,
        const char *pathname,
        char *const argv[],
        char *const envp[],
        int flags);

static int execute_memfd(
        int fd,
        char *const argv[],
        char *const envp[],
        const char *original_path);

static int execute_memfd_fallback(
        int fd,
        char *const argv[],
        char *const envp[],
        const char *original_path);

static char **build_linker64_argv(
        const char *target,
        char *const argv[]);

static int exec_with_linker64(
        const char *target,
        char *const argv[],
        char *const envp[]);

static int spawn_with_linker64(
        pid_t *pid,
        const char *target,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attrp,
        char *const argv[],
        char *const envp[]);

static char **build_noexec_script_argv(
        const char *target,
        char *const argv[]);

static char **build_native_tool_shell_argv(
        const char *target,
        char *const argv[]);

static char **ensure_preload(
        char *const envp[],
        const char *self_path);

static char **strip_preload_env(
        char *const envp[]);

static void free_augmented_env(
        char **env,
        char *const original[]);

static void free_stripped_env(
        char **env,
        char *const original[]);

static const char *get_self_path(void);

static int execve_with_retry(
        const char *pathname,
        char *const argv[],
        char *const envp[]);

int exec_redirect_rebind_exec_slots(void);

/* ============================================================
 * Constructor
 *
 * Disable Bionic heap tagging before fork/spawn.
 * ============================================================ */

__attribute__((constructor))
static void exec_redirect_disable_heap_tagging(void) {

    exec_redirect_ctor_done = 1;

    mallopt(
            M_BIONIC_SET_HEAP_TAGGING_LEVEL,
            M_HEAP_TAGGING_LEVEL_NONE);

    LOGI(
            "[ExecRedirect] constructor: heap tagging disabled");

    /*
     * Rebind exec-family PLT slots of every library loaded so far
     * (LD_PRELOAD startup loads the JDK libs together with us).
     * Covers processes where preload ordering still loses
     * execve@LIBC resolution to DT_NEEDED libc.
     */
    exec_redirect_rebind_exec_slots();
}

/* ============================================================
 * Initialize libc symbols
 * ============================================================ */

static void init_real_symbols(void) {
    if (exec_redirect_init_done) {
        return;
    }

    exec_redirect_init_done = 1;

    real_execve =
        (execve_fn)dlsym(RTLD_NEXT, "execve");

    real_access =
        (access_fn)dlsym(RTLD_NEXT, "access");

    real_faccessat =
        (faccessat_fn)dlsym(RTLD_NEXT, "faccessat");

    real_mmap =
        (mmap_fn)dlsym(RTLD_NEXT, "mmap");

    real_mprotect =
        (mprotect_fn)dlsym(RTLD_NEXT, "mprotect");

    real_posix_spawn =
        (posix_spawn_fn)dlsym(
                RTLD_NEXT,
                "posix_spawn");

    real_posix_spawnp =
        (posix_spawnp_fn)dlsym(
                RTLD_NEXT,
                "posix_spawnp");

    real_fork =
        (fork_fn)dlsym(RTLD_NEXT, "fork");

    real_write =
        (write_fn)dlsym(RTLD_NEXT, "write");

    real_read =
        (read_fn)dlsym(RTLD_NEXT, "read");

    real_writev =
        (writev_fn)dlsym(RTLD_NEXT, "writev");

    LOGI(
            "[ExecRedirect] libc symbols initialized: "
            "execve=%p spawn=%p spawnp=%p fork=%p",
            real_execve,
            real_posix_spawn,
            real_posix_spawnp,
            real_fork);
}

/* ============================================================
 * write() tap (diagnostic)
 *
 * Logs small writes to fd 3/4 (FAIL_FILENO / CHILDENV_FILENO
 * in the JDK spawn protocol) so we can see exactly who puts
 * bytes on the fail pipe and what those bytes are.
 * ============================================================ */

static __thread int exec_redirect_write_tap_active = 0;

ssize_t write(int fd, const void *buf, size_t count) {

    if (!exec_redirect_init_done) {
        init_real_symbols();
    }

    if (!real_write) {
        errno = ENOSYS;
        return -1;
    }

    if (!exec_redirect_write_tap_active &&
        count > 0 && count <= 4096 &&
        fd >= 3 && fd <= 32) {

        struct stat st;

        if (fstat(fd, &st) == 0 && S_ISFIFO(st.st_mode)) {

            const unsigned char *b =
                    (const unsigned char *)buf;

            char hex[40];
            int n = 0;
            size_t lim = count < 8 ? count : 8;

            for (size_t i = 0;
                 i < lim && n < (int)sizeof(hex) - 4;
                 ++i) {

                n += snprintf(
                        hex + n,
                        sizeof(hex) - (size_t)n,
                        "%02x",
                        b[i]);
            }

            void *caller = __builtin_return_address(0);
            Dl_info info = {};
            const char *fname = "?";
            const char *sname = "-";

            if (dladdr(caller, &info)) {
                if (info.dli_fname) fname = info.dli_fname;
                if (info.dli_sname) sname = info.dli_sname;
            }

            exec_redirect_write_tap_active = 1;

            LOGI(
                    "[ExecRedirect] write tap: fd=%d n=%zu "
                    "bytes=%s caller=%p base=%p %s+%s",
                    fd,
                    count,
                    hex,
                    caller,
                    info.dli_fbase,
                    fname,
                    sname);

            exec_redirect_write_tap_active = 0;
        }
    }

    return real_write(fd, buf, count);
}

ssize_t read(int fd, void *buf, size_t count) {

    if (!exec_redirect_init_done) {
        init_real_symbols();
    }

    if (!real_read) {
        errno = ENOSYS;
        return -1;
    }

    ssize_t ret = real_read(fd, buf, count);

    if (!exec_redirect_write_tap_active &&
        count == 4 &&
        fd >= 3 && fd <= 32) {

        const unsigned char *b = (const unsigned char *)buf;
        char hex[16];

        if (ret == 4) {
            snprintf(hex, sizeof(hex), "%02x%02x%02x%02x",
                     b[0], b[1], b[2], b[3]);
        } else {
            snprintf(hex, sizeof(hex), "ret=%zd", ret);
        }

        exec_redirect_write_tap_active = 1;

        LOGI(
                "[ExecRedirect] read tap: fd=%d ret=%zd bytes=%s "
                "caller=%p",
                fd,
                ret,
                hex,
                __builtin_return_address(0));

        exec_redirect_write_tap_active = 0;
    }

    return ret;
}

ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {

    if (!exec_redirect_init_done) {
        init_real_symbols();
    }

    if (!real_writev) {
        errno = ENOSYS;
        return -1;
    }

    if (!exec_redirect_write_tap_active &&
        iovcnt > 0 &&
        fd >= 3 && fd <= 32) {

        struct stat st;

        if (fstat(fd, &st) == 0 && S_ISFIFO(st.st_mode)) {

            size_t total = 0;
            for (int i = 0; i < iovcnt; ++i) total += iov[i].iov_len;

            char hex[40];
            int n = 0;
            size_t got = 0;

            for (int i = 0; i < iovcnt && got < 8; ++i) {
                const unsigned char *b =
                        (const unsigned char *)iov[i].iov_base;
                size_t l = iov[i].iov_len;
                if (got + l > 8) l = 8 - got;
                for (size_t j = 0;
                     j < l && n < (int)sizeof(hex) - 4;
                     ++j) {
                    n += snprintf(
                            hex + n,
                            sizeof(hex) - (size_t)n,
                            "%02x",
                            b[j]);
                }
                got += l;
            }

            void *caller = __builtin_return_address(0);
            Dl_info info = {};
            const char *fname = "?";
            const char *sname = "-";

            if (dladdr(caller, &info)) {
                if (info.dli_fname) fname = info.dli_fname;
                if (info.dli_sname) sname = info.dli_sname;
            }

            exec_redirect_write_tap_active = 1;

            LOGI(
                    "[ExecRedirect] writev tap: fd=%d iov=%d "
                    "total=%zu bytes=%s caller=%p base=%p %s+%s",
                    fd,
                    iovcnt,
                    total,
                    hex,
                    caller,
                    info.dli_fbase,
                    fname,
                    sname);

            exec_redirect_write_tap_active = 0;
        }
    }

    return real_writev(fd, iov, iovcnt);
}

/* ============================================================
 * __android_log_* gate (diagnostic + fix candidate)
 *
 * In the spawn child, moveDescriptor() overwrites fd 3 (the
 * logd socket liblog cached) with the fail-pipe write end.
 * Any __android_log_* call in the window between that move
 * and exec() therefore lands binary log frames on the fail
 * pipe, which the parent JVM misreads as the child's errno.
 *
 * While fd 3 is a FIFO, drop log calls and emit a marker on
 * fd 2 (the child stdout pipe) so the window is observable.
 * ============================================================ */

typedef int (*alvp_fn)(int, const char *, const char *, va_list);
typedef int (*alw_fn)(int, const char *, const char *);

static alvp_fn real_android_log_vprint = NULL;
static alw_fn real_android_log_write = NULL;

static int exec_redirect_log_window(void) {
    struct stat st;
    return fstat(3, &st) == 0 && S_ISFIFO(st.st_mode);
}

static void exec_redirect_log_drop(const char *kind, const char *tag) {
    if (!real_write) init_real_symbols();
    if (!real_write || exec_redirect_write_tap_active) return;

    char buf[160];
    int l = snprintf(buf, sizeof(buf), "[LOGDROP %s tag=%s]\n",
                     kind, tag ? tag : "?");
    if (l > 0) {
        exec_redirect_write_tap_active = 1;
        real_write(2, buf, (size_t)l);
        exec_redirect_write_tap_active = 0;
    }
}

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {

    if (exec_redirect_log_window()) {
        exec_redirect_log_drop("print", tag);
        return 0;
    }

    if (!real_android_log_vprint) {
        real_android_log_vprint = (alvp_fn)
                dlsym(RTLD_NEXT, "__android_log_vprint");
    }
    if (!real_android_log_vprint) return 0;

    va_list ap;
    va_start(ap, fmt);
    int r = real_android_log_vprint(prio, tag, fmt, ap);
    va_end(ap);
    return r;
}

int __android_log_write(int prio, const char *tag, const char *msg) {

    if (exec_redirect_log_window()) {
        exec_redirect_log_drop("write", tag);
        return 0;
    }

    if (!real_android_log_write) {
        real_android_log_write = (alw_fn)
                dlsym(RTLD_NEXT, "__android_log_write");
    }
    if (!real_android_log_write) return 0;

    return real_android_log_write(prio, tag, msg);
}

/* ============================================================
 * JLI loader
 * ============================================================ */

static void ensure_jli_loaded(void) {

    if (g_jli.handle) {
        return;
    }

    const char *native_lib_dir =
            getenv("MOBILESTUDIO_NATIVE_LIB_DIR");

    if (!native_lib_dir || !native_lib_dir[0]) {
        LOGE(
                "[ExecRedirect] JLI: "
                "MOBILESTUDIO_NATIVE_LIB_DIR not set");
        return;
    }

    char path[1024];

    snprintf(
            path,
            sizeof(path),
            "%s/libmobilestudio_jli.so",
            native_lib_dir);

    dlerror();

    g_jli.handle =
            dlopen(
                    path,
                    RTLD_LAZY | RTLD_GLOBAL);

    if (!g_jli.handle) {
        LOGE(
                "[ExecRedirect] JLI: dlopen failed: %s",
                dlerror());
        return;
    }

    g_jli.resolve_java_home =
            (jli_resolve_java_home_fn)dlsym(
                    g_jli.handle,
                    "mobilestudio_jli_resolve_java_home");

    g_jli.apply_child_env =
            (jli_apply_child_env_fn)dlsym(
                    g_jli.handle,
                    "mobilestudio_jli_apply_child_env");

    g_jli.prepend_ld_paths =
            (jli_prepend_ld_paths_fn)dlsym(
                    g_jli.handle,
                    "mobilestudio_jli_prepend_ld_paths");

    g_jli.preload_libs =
            (jli_preload_libs_fn)dlsym(
                    g_jli.handle,
                    "mobilestudio_jli_preload_libs");

    g_jli.disable_heap_tagging =
            (jli_disable_heap_tagging_fn)dlsym(
                    g_jli.handle,
                    "mobilestudio_jli_disable_heap_tagging");

    g_jli.launch_in_process =
            (jli_launch_in_process_fn)dlsym(
                    g_jli.handle,
                    "mobilestudio_jli_launch_in_process");

    if (!g_jli.disable_heap_tagging ||
        !g_jli.resolve_java_home ||
        !g_jli.launch_in_process) {

        LOGE(
                "[ExecRedirect] JLI: "
                "required symbols missing");

        dlclose(g_jli.handle);
        memset(&g_jli, 0, sizeof(g_jli));
        return;
    }

    LOGI(
            "[ExecRedirect] JLI loaded: %s",
            path);
}

/* ============================================================
 * ELF detection
 * ============================================================ */

static int is_elf(const char *path) {

    if (!path || !path[0]) {
        return 0;
    }

    int fd =
            open(
                    path,
                    O_RDONLY | O_CLOEXEC);

    if (fd < 0) {
        return 0;
    }

    unsigned char magic[4];

    ssize_t n =
            read(
                    fd,
                    magic,
                    sizeof(magic));

    close(fd);

    return n == 4 &&
           magic[0] == 0x7f &&
           magic[1] == 'E' &&
           magic[2] == 'L' &&
           magic[3] == 'F';
}

/* ============================================================
 * ARM64 ELF detection
 * ============================================================ */

static int is_arm64_elf(const char *path) {

    if (!path || !path[0]) {
        return 0;
    }

    int fd =
            open(
                    path,
                    O_RDONLY | O_CLOEXEC);

    if (fd < 0) {
        return 0;
    }

    unsigned char hdr[20];

    ssize_t n =
            read(
                    fd,
                    hdr,
                    sizeof(hdr));

    close(fd);

    if (n < 20) {
        return 0;
    }

    if (hdr[0] != 0x7f ||
        hdr[1] != 'E' ||
        hdr[2] != 'L' ||
        hdr[3] != 'F') {
        return 0;
    }

    /* ELF64 */
    if (hdr[4] != 2) {
        return 0;
    }

    /* Little endian */
    if (hdr[5] != 1) {
        return 0;
    }

    unsigned short machine =
            (unsigned short)(
                    hdr[18] |
                    ((unsigned short)hdr[19] << 8));

    return machine == 0xB7;
}

/* ============================================================
 * PT_INTERP detection
 * ============================================================ */

static int has_interp(const char *path) {

    if (!path || !path[0]) {
        return 0;
    }

    int fd =
            open(
                    path,
                    O_RDONLY | O_CLOEXEC);

    if (fd < 0) {
        return 0;
    }

    unsigned char ehdr[64];

    ssize_t n =
            read(
                    fd,
                    ehdr,
                    sizeof(ehdr));

    if (n < 64 ||
        ehdr[0] != 0x7f ||
        ehdr[1] != 'E' ||
        ehdr[2] != 'L' ||
        ehdr[3] != 'F' ||
        ehdr[4] != 2 ||
        ehdr[5] != 1) {

        close(fd);
        return 0;
    }

    unsigned long long phoff = 0;

    for (int i = 0; i < 8; i++) {
        phoff |= ((unsigned long long)ehdr[32 + i]) << (8 * i);
    }

    unsigned short phentsize =
            (unsigned short)(
                    ehdr[54] |
                    ((unsigned short)ehdr[55] << 8));

    unsigned short phnum =
            (unsigned short)(
                    ehdr[56] |
                    ((unsigned short)ehdr[57] << 8));

    int found = 0;

    if (phentsize >= 4 &&
        phnum > 0 &&
        phnum <= 128 &&
        phoff > 0 &&
        phoff < 0x40000000ULL) {

        for (unsigned short i = 0; i < phnum; i++) {

            unsigned char ph[4];

            ssize_t pn =
                    pread(
                            fd,
                            ph,
                            sizeof(ph),
                            (off_t)(phoff +
                                    (unsigned long long)i *
                                    (unsigned long long)phentsize));

            if (pn < 4) {
                break;
            }

            unsigned int p_type =
                    ((unsigned int)ph[0]) |
                    (((unsigned int)ph[1]) << 8) |
                    (((unsigned int)ph[2]) << 16) |
                    (((unsigned int)ph[3]) << 24);

            if (p_type == 3) {
                found = 1;
                break;
            }
        }
    }

    close(fd);

    return found;
}

/* ============================================================
 * noexec path detection
 * ============================================================ */

static int is_noexec_path(const char *path) {

    if (!path) {
        return 0;
    }

    return
        strncmp(
                path,
                "/data/",
                6) == 0 ||

        strncmp(
                path,
                "/storage/emulated/",
                18) == 0 ||

        strncmp(
                path,
                "/sdcard/",
                8) == 0;
}

/* ============================================================
 * Preload library detection
 * ============================================================ */

static int is_preload_path(const char *path) {

    if (!path) {
        return 0;
    }

    return
        strstr(path, "libexec_redirect.so") != NULL ||
        strstr(path, "libcwd_fix.so") != NULL ||
        strstr(path, "libandroid-shmem.so") != NULL ||
        strstr(path, "libandroid-spawn.so") != NULL;
}

/* ============================================================
 * Native build tools
 * ============================================================ */

static int is_native_build_tool(const char *path) {

    if (!path || !path[0]) {
        return 0;
    }

    const char *name =
            strrchr(path, '/');

    name = name ? name + 1 : path;

    size_t len = strlen(name);

    return
        strcmp(name, "cmake") == 0 ||
        strcmp(name, "ninja") == 0 ||
        strcmp(name, "ctest") == 0 ||
        strcmp(name, "cpack") == 0 ||
        strcmp(name, "lua") == 0 ||
        strcmp(name, "luac") == 0 ||
        strcmp(name, "clang") == 0 ||
        strcmp(name, "clang++") == 0 ||
        strcmp(name, "ld.lld") == 0 ||
        strcmp(name, "lld") == 0 ||
        strcmp(name, "llvm-ar") == 0 ||
        strcmp(name, "llvm-ranlib") == 0 ||
        strcmp(name, "llvm-strip") == 0 ||
        strcmp(name, "llvm-objcopy") == 0 ||
        strcmp(name, "llvm-readelf") == 0 ||
        strcmp(name, "llvm-nm") == 0 ||
        strcmp(name, "llvm-objdump") == 0 ||
        strcmp(name, "llvm-addr2line") == 0 ||
        strcmp(name, "llvm-profdata") == 0 ||
        strcmp(name, "llvm-cov") == 0 ||
        (len >= 6 &&
         strcmp(
                 name + len - 6,
                 "-clang") == 0) ||
        (len >= 8 &&
         strcmp(
                 name + len - 8,
                 "-clang++") == 0);
}

/* ============================================================
 * JDK executable detection
 * ============================================================ */

static int is_jdk_tool(const char *path) {

    if (!path || !path[0]) {
        return 0;
    }

    const char *name =
            strrchr(path, '/');

    name = name ? name + 1 : path;

    size_t len = strlen(name);

    if (len > 5 &&
        strcmp(
                name + len - 5,
                ".real") == 0) {
        len -= 5;
    }

    if (len == 4 &&
        strncmp(name, "java", 4) == 0) {
        return 1;
    }

    if (len == 5 &&
        strncmp(name, "javac", 5) == 0) {
        return 1;
    }

    if (len == 3 &&
        strncmp(name, "jar", 3) == 0) {
        return 1;
    }

    if (len == 5 &&
        strncmp(name, "jlink", 5) == 0) {
        return 1;
    }

    if (len == 4 &&
        strncmp(name, "jmod", 4) == 0) {
        return 1;
    }

    if (len == 5 &&
        strncmp(name, "jdeps", 5) == 0) {
        return 1;
    }

    return 0;
}

/* ============================================================
 * Shell script detection
 * ============================================================ */

static int is_shell_script(const char *path) {

    if (!path || !path[0]) {
        return 0;
    }

    int fd =
            open(
                    path,
                    O_RDONLY | O_CLOEXEC);

    if (fd < 0) {
        return 0;
    }

    unsigned char magic[2];

    ssize_t n =
            read(
                    fd,
                    magic,
                    sizeof(magic));

    close(fd);

    return n == 2 &&
           magic[0] == '#' &&
           magic[1] == '!';
}

/* ============================================================
 * memfd syscall
 * ============================================================ */

static int syscall_memfd_create(
        const char *name,
        unsigned int flags) {

    return (int)syscall(
            __NR_memfd_create,
            name,
            flags);
}

/* ============================================================
 * Copy executable -> memfd
 * ============================================================ */

static int copy_to_memfd_internal(
        const char *path,
        unsigned int base_flags) {

    if (!path || !path[0]) {
        errno = EINVAL;
        return -1;
    }

    int src =
            open(
                    path,
                    O_RDONLY | O_CLOEXEC);

    if (src < 0) {
        LOGE(
                "[ExecRedirect] memfd: "
                "open(%s) failed: errno=%d (%s)",
                path,
                errno,
                strerror(errno));
        return -1;
    }

    int mfd = -1;

    /*
     * First choice:
     *
     *     MFD_EXEC
     *
     * Android/Linux kernels supporting executable memfds
     * can enforce executable permission correctly.
     */
    mfd =
            syscall_memfd_create(
                    "mobilestudio-exec",
                    base_flags |
                    MFD_EXEC |
                    MFD_ALLOW_SEALING);

    if (mfd < 0 && errno == EINVAL) {

        LOGI(
                "[ExecRedirect] memfd: "
                "MFD_EXEC unsupported, retrying");

        mfd =
                syscall_memfd_create(
                        "mobilestudio-exec",
                        base_flags |
                        MFD_ALLOW_SEALING);
    }

    if (mfd < 0) {

        mfd =
                syscall_memfd_create(
                        "mobilestudio-exec",
                        base_flags);
    }

    if (mfd < 0) {

        int saved = errno;

        LOGE(
                "[ExecRedirect] memfd_create failed: "
                "errno=%d (%s)",
                saved,
                strerror(saved));

        close(src);
        errno = saved;

        return -1;
    }

    LOGI(
            "[ExecRedirect] memfd: "
            "created fd=%d for %s",
            mfd,
            path);

    char buffer[65536];

    for (;;) {

        ssize_t n =
                read(
                        src,
                        buffer,
                        sizeof(buffer));

        if (n == 0) {
            break;
        }

        if (n < 0) {

            int saved = errno;

            LOGE(
                    "[ExecRedirect] memfd: "
                    "read failed: errno=%d (%s)",
                    saved,
                    strerror(saved));

            close(src);
            close(mfd);

            errno = saved;

            return -1;
        }

        ssize_t written = 0;

        while (written < n) {

            ssize_t w =
                    write(
                            mfd,
                            buffer + written,
                            (size_t)(n - written));

            if (w < 0) {

                int saved = errno;

                LOGE(
                        "[ExecRedirect] memfd: "
                        "write failed: errno=%d (%s)",
                        saved,
                        strerror(saved));

                close(src);
                close(mfd);

                errno = saved;

                return -1;
            }

            if (w == 0) {
                LOGE(
                        "[ExecRedirect] memfd: "
                        "write returned 0");
                close(src);
                close(mfd);
                errno = EIO;
                return -1;
            }

            written += w;
        }
    }

    close(src);

    /*
     * Immutable executable.
     *
     * Failure is non-fatal because some kernels don't allow
     * seals on the selected memfd configuration.
     */
    if (fcntl(
            mfd,
            F_ADD_SEALS,
            F_SEAL_WRITE |
            F_SEAL_SHRINK |
            F_SEAL_GROW |
            F_SEAL_SEAL) < 0) {

        LOGI(
                "[ExecRedirect] memfd: "
                "F_ADD_SEALS failed: errno=%d (%s)",
                errno,
                strerror(errno));
    }

    return mfd;
}

static int copy_to_memfd(
        const char *path) {

    /*
     * CLOEXEC is correct for execveat().
     *
     * The kernel consumes the fd during execveat,
     * then closes it in the new image.
     */
    return copy_to_memfd_internal(
            path,
            MFD_CLOEXEC);
}

static int copy_to_inheritable_memfd(
        const char *path) {

    /*
     * Used only by the final /proc/self/fd fallback.
     */
    return copy_to_memfd_internal(
            path,
            0);
}

/* ============================================================
 * Raw execveat syscall
 * ============================================================ */

static int syscall_execveat(
        int dirfd,
        const char *pathname,
        char *const argv[],
        char *const envp[],
        int flags) {

    return (int)syscall(
            __NR_execveat,
            dirfd,
            pathname,
            argv,
            envp,
            flags);
}

/* ============================================================
 * Self path
 * ============================================================ */

static const char *get_self_path(void) {

    static char self_path[1024];

    if (self_path[0]) {
        return self_path;
    }

    Dl_info info;

    memset(
            &info,
            0,
            sizeof(info));

    if (dladdr(
            (void *)get_self_path,
            &info) &&
        info.dli_fname) {

        strncpy(
                self_path,
                info.dli_fname,
                sizeof(self_path) - 1);

        self_path[
                sizeof(self_path) - 1] = '\0';
    }

    return self_path[0]
           ? self_path
           : NULL;
}

/* ============================================================
 * Environment helpers
 * ============================================================ */

static char **ensure_preload(
        char *const envp[],
        const char *self_path) {

    char *const *source =
            envp ? envp : environ;

    if (!source ||
        !self_path ||
        !self_path[0]) {

        return (char **)source;
    }

    int count = 0;
    int preload_index = -1;

    while (source[count]) {

        if (strncmp(
                source[count],
                "LD_PRELOAD=",
                11) == 0) {

            preload_index = count;
        }

        count++;
    }

    char **out =
            (char **)calloc(
                    (size_t)count + 2,
                    sizeof(char *));

    if (!out) {
        return (char **)source;
    }

    memcpy(
            out,
            source,
            (size_t)count * sizeof(char *));

    if (preload_index >= 0) {

        const char *old =
                source[preload_index] + 11;

        size_t len =
                strlen("LD_PRELOAD=") +
                strlen(old) +
                1 +
                strlen(self_path) +
                1;

        char *entry =
                (char *)malloc(len);

        if (!entry) {
            free(out);
            return (char **)source;
        }

        snprintf(
                entry,
                len,
                "LD_PRELOAD=%s:%s",
                old,
                self_path);

        out[preload_index] = entry;

        out[count] = NULL;

    } else {

        size_t len =
                strlen("LD_PRELOAD=") +
                strlen(self_path) +
                1;

        char *entry =
                (char *)malloc(len);

        if (!entry) {
            free(out);
            return (char **)source;
        }

        snprintf(
                entry,
                len,
                "LD_PRELOAD=%s",
                self_path);

        out[count] = entry;
        out[count + 1] = NULL;
    }

    return out;
}

static char **strip_preload_env(
        char *const envp[]) {

    char *const *source =
            envp ? envp : environ;

    if (!source) {
        return NULL;
    }

    int count = 0;
    int kept = 0;

    while (source[count]) {

        if (strncmp(
                source[count],
                "LD_PRELOAD=",
                11) != 0) {

            kept++;
        }

        count++;
    }

    char **out =
            (char **)calloc(
                    (size_t)kept + 1,
                    sizeof(char *));

    if (!out) {
        return (char **)source;
    }

    int index = 0;

    for (int i = 0; source[i]; ++i) {

        if (strncmp(
                source[i],
                "LD_PRELOAD=",
                11) == 0) {

            continue;
        }

        out[index++] = source[i];
    }

    out[index] = NULL;

    return out;
}

static void free_augmented_env(
        char **env,
        char *const original[]) {

    if (!env ||
        env == original) {

        return;
    }

    for (int i = 0; env[i]; ++i) {

        if (strncmp(
                env[i],
                "LD_PRELOAD=",
                11) == 0) {

            free(env[i]);
        }
    }

    free(env);
}

static void free_stripped_env(
        char **env,
        char *const original[]) {

    if (!env ||
        env == original ||
        env == environ) {

        return;
    }

    free(env);
}

/* ============================================================
 * linker64 argv
 *
 * Result:
 *
 *   /system/bin/linker64
 *   /data/.../program
 *   arg1
 *   arg2
 *
 * linker64 will load the target ELF.
 * ============================================================ */

static char **build_linker64_argv(
        const char *target,
        char *const argv[]) {

    if (!target ||
        !target[0]) {

        return NULL;
    }

    if (!is_arm64_elf(target)) {
        return NULL;
    }

    int argc = 0;

    if (argv) {

        while (argv[argc]) {
            argc++;
        }
    }

    /*
     * +2:
     *
     * linker64
     * target
     *
     * plus NULL is included by calloc.
     */
    char **out =
            (char **)calloc(
                    (size_t)argc + 2,
                    sizeof(char *));

    if (!out) {
        return NULL;
    }

    int n = 0;

    out[n++] =
            (char *)"/system/bin/linker64";

    out[n++] =
            (char *)target;

    /*
     * argv[0] is the original program name.
     *
     * linker64 already receives target as argv[1],
     * so original argv[1...] become linker64 argv[2...].
     */
    if (argv) {

        for (int i = 1;
             i < argc;
             ++i) {

            out[n++] = argv[i];
        }
    }

    out[n] = NULL;

    return out;
}

/* ============================================================
 * exec through linker64
 * ============================================================ */

static int exec_with_linker64(
        const char *target,
        char *const argv[],
        char *const envp[]) {

    if (!target ||
        !is_arm64_elf(target)) {

        errno = ENOEXEC;
        return -1;
    }

    if (!has_interp(target)) {

        LOGE(
                "[ExecRedirect] linker64: "
                "target has no PT_INTERP (static ELF): "
                "the target receives the raw argv "
                "[linker64, target, ...] because static "
                "binaries never apply the bionic argv shift. "
                "argv-sensitive programs (Go/cobra) will "
                "misread os.Args[1]. target=%s",
                target);
    }

    char **linker_argv =
            build_linker64_argv(
                    target,
                    argv);

    if (!linker_argv) {

        errno = ENOMEM;

        LOGE(
                "[ExecRedirect] linker64: "
                "failed to build argv for %s",
                target);

        return -1;
    }

    char *const *base_env =
            envp ? envp : environ;

    char **new_env =
            ensure_preload(
                    base_env,
                    get_self_path());

    char *const *final_env =
            new_env
            ? new_env
            : base_env;

    LOGI(
            "[ExecRedirect] strategy=linker64 "
            "target=%s",
            target);

    int ret =
            real_execve(
                    "/system/bin/linker64",
                    linker_argv,
                    final_env);

    int saved = errno;

    LOGE(
            "[ExecRedirect] strategy=linker64 "
            "failed: errno=%d (%s)",
            saved,
            strerror(saved));

    free(linker_argv);

    free_augmented_env(
            new_env,
            base_env);

    errno = saved;

    return ret;
}

/* ============================================================
 * Spawn through linker64
 *
 * IMPORTANT:
 *
 * posix_spawn() returns an error number directly.
 * It does NOT use errno in the same way execve() does.
 * ============================================================ */

static int spawn_with_linker64(
        pid_t *pid,
        const char *target,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attrp,
        char *const argv[],
        char *const envp[]) {

    init_real_symbols();

    if (!real_posix_spawn) {
        return ENOSYS;
    }

    if (!target ||
        !is_arm64_elf(target)) {

        return ENOEXEC;
    }

    if (!has_interp(target)) {

        LOGE(
                "[ExecRedirect] linker64: "
                "spawn target has no PT_INTERP (static ELF): "
                "the target receives the raw argv "
                "[linker64, target, ...] because static "
                "binaries never apply the bionic argv shift. "
                "argv-sensitive programs (Go/cobra) will "
                "misread os.Args[1]. target=%s",
                target);
    }

    char **linker_argv =
            build_linker64_argv(
                    target,
                    argv);

    if (!linker_argv) {
        return ENOMEM;
    }

    char *const *base_env =
            envp ? envp : environ;

    char **new_env =
            ensure_preload(
                    base_env,
                    get_self_path());

    char *const *final_env =
            new_env
            ? new_env
            : base_env;

    LOGI(
            "[ExecRedirect] strategy=spawn-linker64 "
            "target=%s",
            target);

    /*
     * This is intentionally /system/bin/linker64.
     *
     * We do NOT use:
     *
     *     /proc/self/fd/N
     *
     * here.
     *
     * Android bionic's posix_spawn implementation creates a child
     * and invokes execve in that child, so linker64 is the safe
     * spawn-side equivalent of our execve linker fallback.
     */
    int ret =
            real_posix_spawn(
                    pid,
                    "/system/bin/linker64",
                    file_actions,
                    attrp,
                    linker_argv,
                    final_env);

    LOGI(
            "[ExecRedirect] strategy=spawn-linker64 "
            "result=%d",
            ret);

    if (ret != 0) {

        LOGE(
                "[ExecRedirect] strategy=spawn-linker64 "
                "failed: error=%d (%s)",
                ret,
                strerror(ret));
    }

    free(linker_argv);

    free_augmented_env(
            new_env,
            base_env);

    return ret;
}

/* ============================================================
 * JDK shell script -> /system/bin/sh
 * ============================================================ */

static char **build_noexec_script_argv(
        const char *target,
        char *const argv[]) {

    if (!target ||
        !target[0] ||
        !is_noexec_path(target) ||
        !is_shell_script(target)) {

        return NULL;
    }

    int argc = 0;

    if (argv) {

        while (argv[argc]) {
            argc++;
        }
    }

    char **out =
            (char **)calloc(
                    (size_t)argc + 3,
                    sizeof(char *));

    if (!out) {
        return NULL;
    }

    int n = 0;

    out[n++] =
            (char *)"/system/bin/sh";

    out[n++] =
            (char *)target;

    for (int i = 1;
         i < argc;
         ++i) {

        out[n++] = argv[i];
    }

    out[n] = NULL;

    return out;
}

/* ============================================================
 * Native build tool -> optional shell shim
 * ============================================================ */

static char **build_native_tool_shell_argv(
        const char *target,
        char *const argv[]) {

    /*
     * The shim re-execs its argument (exec "$@"). Without this guard the
     * re-exec is redirected into the shim again — an infinite loop that
     * prepends runtime-tools to PATH on every pass until MAX_ARG_STRLEN
     * blows up as E2BIG ("Argument list too long"). The shim exports this
     * marker just before its exec, so only the first hop uses the shim.
     */
    if (getenv("MOBILESTUDIO_SHIM_ACTIVE")) {
        return NULL;
    }

    const char *shell =
            getenv(
                    "MOBILESTUDIO_NATIVE_TOOL_SHELL");

    if (!shell ||
        !shell[0] ||
        !target ||
        !target[0] ||
        !is_native_build_tool(target)) {

        return NULL;
    }

    int argc = 0;

    if (argv) {

        while (argv[argc]) {
            argc++;
        }
    }

    char **out =
            (char **)calloc(
                    (size_t)argc + 4,
                    sizeof(char *));

    if (!out) {
        return NULL;
    }

    int n = 0;

    out[n++] =
            (char *)"/system/bin/sh";

    out[n++] =
            (char *)shell;

    out[n++] =
            (char *)target;

    for (int i = 1;
         i < argc;
         ++i) {

        out[n++] = argv[i];
    }

    out[n] = NULL;

    return out;
}

/* ============================================================
 * memfd execution
 *
 * PRIMARY:
 *
 *     execveat(fd, "", ..., AT_EMPTY_PATH)
 *
 * SECONDARY:
 *
 *     fexecve equivalent
 *
 * LAST:
 *
 *     /proc/self/fd/N
 * ============================================================ */

static int execute_memfd(
        int fd,
        char *const argv[],
        char *const envp[],
        const char *original_path) {

    if (fd < 0) {
        errno = EBADF;
        return -1;
    }

    LOGI(
            "[ExecRedirect] strategy=execveat "
            "fd=%d target=%s",
            fd,
            original_path
                ? original_path
                : "<unknown>");

    /*
     * FIRST STRATEGY
     *
     * This is the important Android noexec bypass.
     */
    int ret =
            syscall_execveat(
                    fd,
                    "",
                    argv,
                    envp,
                    AT_EMPTY_PATH);

    int saved = errno;

    LOGI(
            "[ExecRedirect] "
            "execveat(AT_EMPTY_PATH)=%d "
            "errno=%d (%s)",
            ret,
            saved,
            saved
                ? strerror(saved)
                : "OK");

    if (ret == 0) {
        return 0;
    }

    /*
     * Some kernels may reject AT_EMPTY_PATH.
     *
     * Do NOT immediately jump to /proc/self/fd.
     *
     * FIRST make sure the memfd is executable: kernels without MFD_EXEC
     * create it 0600, and execveat() then fails with EACCES no matter how
     * often it is retried.  This is the real difference between attempt 1
     * and the fexecve-equivalent attempt below.
     */
    if (fchmod(fd, 0755) != 0) {

        int chmod_err = errno;

        LOGI(
                "[ExecRedirect] strategy=fexecve "
                "fd=%d fchmod(0755) result=-1 errno=%d (%s)",
                fd,
                chmod_err,
                strerror(chmod_err));
    } else {

        LOGI(
                "[ExecRedirect] strategy=fexecve "
                "fd=%d fchmod(0755) result=0",
                fd);
    }

    ret =
            syscall_execveat(
                    fd,
                    "",
                    argv,
                    envp,
                    AT_EMPTY_PATH);

    saved = errno;

    LOGI(
            "[ExecRedirect] strategy=fexecve "
            "fd=%d execveat result=%d errno=%d (%s)",
            fd,
            ret,
            saved,
            saved
                ? strerror(saved)
                : "OK");

    /*
     * Do not use /proc/self/fd here yet.
     *
     * Caller will decide whether linker64 should be tried.
     */
    errno = saved;

    return ret;
}

/* ============================================================
 * /proc/self/fd fallback
 *
 * LAST RESORT ONLY
 * ============================================================ */

static int execute_memfd_fallback(
        int fd,
        char *const argv[],
        char *const envp[],
        const char *original_path) {

    if (fd < 0) {
        errno = EBADF;
        return -1;
    }

    char fd_path[64];

    snprintf(
            fd_path,
            sizeof(fd_path),
            "/proc/self/fd/%d",
            fd);

    LOGI(
            "[ExecRedirect] strategy=procfd-LAST "
            "fd=%d target=%s",
            fd,
            original_path
                ? original_path
                : "<unknown>");

    int ret =
            real_execve(
                    fd_path,
                    argv,
                    envp);

    int saved = errno;

    LOGI(
            "[ExecRedirect] "
            "execve(%s)=%d "
            "errno=%d (%s)",
            fd_path,
            ret,
            saved,
            saved
                ? strerror(saved)
                : "OK");

    errno = saved;

    return ret;
}

/* ============================================================
 * exec JDK tool
 *
 * We intentionally do NOT use JLI_Launch here.
 *
 * The previous implementation already showed that launching the
 * JVM inside a forked Android process can interact badly with
 * ART/Scudo.
 *
 * JDK executables therefore use the normal exec chain.
 * ============================================================ */

/*
 * Kept as a separate function only for future use.
 *
 * Current execve() deliberately does not call it.
 */
static int exec_jli_launch_disabled(
        const char *target,
        char *const argv[],
        char *const envp[]) {

    (void)target;
    (void)argv;
    (void)envp;

    return -1;
}

/* ============================================================
 * access()
 * ============================================================ */

int access(
        const char *path,
        int mode) {

    init_real_symbols();

    if (real_access &&
        path &&
        (mode & X_OK) &&
        is_noexec_path(path) &&
        !is_preload_path(path) &&
        is_elf(path)) {

        LOGI(
                "[ExecRedirect] access: "
                "fake X_OK: %s",
                path);

        return 0;
    }

    return real_access
           ? real_access(path, mode)
           : -1;
}

/* ============================================================
 * faccessat()
 * ============================================================ */

int faccessat(
        int dirfd,
        const char *path,
        int mode,
        int flags) {

    init_real_symbols();

    if (real_faccessat &&
        path &&
        (mode & X_OK) &&
        path[0] == '/' &&
        !is_preload_path(path) &&
        is_noexec_path(path) &&
        is_elf(path)) {

        LOGI(
                "[ExecRedirect] faccessat: "
                "fake X_OK: %s",
                path);

        return 0;
    }

    return real_faccessat
           ? real_faccessat(
                   dirfd,
                   path,
                   mode,
                   flags)
           : -1;
}

/* ============================================================
 * mmap()
 * ============================================================ */

void *mmap(
        void *addr,
        size_t length,
        int prot,
        int flags,
        int fd,
        off_t offset) {

    init_real_symbols();

    if (!real_mmap) {
        errno = ENOSYS;
        return MAP_FAILED;
    }

    /*
     * Keep the old special MAP_FIXED behavior.
     *
     * Do not interfere with ordinary executable mappings.
     */
    if ((prot & PROT_EXEC) &&
        (flags & MAP_PRIVATE) &&
        fd >= 0 &&
        (flags & (MAP_FIXED | MAP_FIXED_NOREPLACE))) {

        char proc_path[64];
        char real_path[1024];

        snprintf(
                proc_path,
                sizeof(proc_path),
                "/proc/self/fd/%d",
                fd);

        ssize_t n =
                readlink(
                        proc_path,
                        real_path,
                        sizeof(real_path) - 1);

        if (n > 0) {

            real_path[n] = '\0';

            if (is_noexec_path(real_path) &&
                !is_preload_path(real_path)) {

                LOGI(
                        "[ExecRedirect] mmap: "
                        "redirecting MAP_FIXED noexec "
                        "%s -> anonymous",
                        real_path);

                void *memory =
                        real_mmap(
                                addr,
                                length,
                                PROT_READ |
                                PROT_WRITE,
                                MAP_PRIVATE |
                                MAP_ANONYMOUS,
                                -1,
                                0);

                if (memory == MAP_FAILED) {
                    return MAP_FAILED;
                }

                off_t old_offset =
                        lseek(
                                fd,
                                0,
                                SEEK_CUR);

                if (lseek(
                        fd,
                        offset,
                        SEEK_SET) != (off_t)-1) {

                    size_t total = 0;

                    while (total < length) {

                        ssize_t r =
                                read(
                                        fd,
                                        (char *)memory + total,
                                        length - total);

                        if (r <= 0) {
                            break;
                        }

                        total += (size_t)r;
                    }
                }

                if (old_offset != (off_t)-1) {
                    lseek(
                            fd,
                            old_offset,
                            SEEK_SET);
                }

                if (real_mprotect) {

                    if (real_mprotect(
                            memory,
                            length,
                            prot) != 0) {

                        LOGE(
                                "[ExecRedirect] mmap: "
                                "mprotect failed: "
                                "errno=%d (%s)",
                                errno,
                                strerror(errno));

                        /*
                         * Never leave a writable+executable
                         * mapping if we can remove WRITE.
                         */
                        if ((prot & PROT_WRITE) &&
                            (prot & PROT_EXEC)) {

                            real_mprotect(
                                    memory,
                                    length,
                                    prot & ~PROT_WRITE);
                        }
                    }
                }

                return memory;
            }
        }
    }

    return real_mmap(
            addr,
            length,
            prot,
            flags,
            fd,
            offset);
}

/* ============================================================
 * mprotect()
 * ============================================================ */

int mprotect(
        void *addr,
        size_t len,
        int prot) {

    init_real_symbols();

    if (!real_mprotect) {
        errno = ENOSYS;
        return -1;
    }

    int ret =
            real_mprotect(
                    addr,
                    len,
                    prot);

    if (ret != 0 &&
        (prot & PROT_EXEC)) {

        LOGV(
                "[ExecRedirect] mprotect: "
                "EXEC failed: %p len=%zu errno=%d (%s)",
                addr,
                len,
                errno,
                strerror(errno));
    }

    return ret;
}

/* ============================================================
 * vfork()
 *
 * Android's UNIXProcess forkAndExec (libjavacore, ojluni
 * UNIXProcess_md.c) calls vfork() via START_CHILD_USE_VFORK.
 * The child shares the parent's address space until exec, so any
 * interposed function reached from that child - our own execve,
 * which mallocs argv, talks to logd, and runs the memfd/linker
 * strategies - writes straight into the PARENT's heap and log
 * state.  Observed effect: the helper's fail-pipe 4-byte errno
 * slot comes back as garbage (aapt2 "error=170835200, Exec
 * failed") and pre-exec strategy logs are swallowed, even though
 * the exec itself succeeded (image loads, childProcess exit 0
 * path never reached - hence "WhyCantJohnnyExec" fires with a
 * poisoned errno).
 *
 * OpenJDK ships MODE_FORK for the exact same childProcess(), so
 * delegating PLT-bound vfork to real fork() keeps the contract
 * (child runs then execs, parent resumes against the pipes)
 * while giving the child a private copy-on-write address space
 * that is safe for malloc/dlsym/LOGI.
 *
 * bionic's internal posix_spawn vfork is a libc-internal call
 * that never resolves through a PLT slot, so it is unaffected.
 * ============================================================ */

pid_t vfork(void) {

    init_real_symbols();

    if (!real_fork) {
        errno = ENOSYS;
        return (pid_t)-1;
    }

    pid_t pid = real_fork();

    if (pid == 0) {
        LOGI(
                "[ExecRedirect] vfork -> fork: "
                "child in private address space");
    }

    return pid;
}

/* ============================================================
 * execve()
 * ============================================================ */

int execve(
        const char *pathname,
        char *const argv[],
        char *const envp[]) {

    init_real_symbols();

    if (!real_execve) {
        errno = ENOSYS;
        return -1;
    }

    if (!pathname ||
        !pathname[0]) {

        errno = EINVAL;
        return -1;
    }

    /*
     * --------------------------------------------------------
     * 1. Native build tool shell shim
     * --------------------------------------------------------
     */

    char **native_tool_argv =
            build_native_tool_shell_argv(
                    pathname,
                    argv);

    if (native_tool_argv) {

        LOGI(
                "[ExecRedirect] execve: "
                "native build tool -> shell: %s",
                pathname);

        char *const *base_env =
                envp ? envp : environ;

        char **new_env =
                ensure_preload(
                        base_env,
                        get_self_path());

        char *const *final_env =
                new_env
                ? new_env
                : base_env;

        int ret =
                real_execve(
                        "/system/bin/sh",
                        native_tool_argv,
                        final_env);

        int saved = errno;

        free(native_tool_argv);

        free_augmented_env(
                new_env,
                base_env);

        errno = saved;

        return ret;
    }

    /*
     * --------------------------------------------------------
     * 2. Shell script on noexec storage
     * --------------------------------------------------------
     */

    char **script_argv =
            build_noexec_script_argv(
                    pathname,
                    argv);

    if (script_argv) {

        LOGI(
                "[ExecRedirect] execve: "
                "script -> /system/bin/sh: %s",
                pathname);

        char *const *base_env =
                envp ? envp : environ;

        char **new_env =
                ensure_preload(
                        base_env,
                        get_self_path());

        char *const *final_env =
                new_env
                ? new_env
                : base_env;

        int ret =
                real_execve(
                        "/system/bin/sh",
                        script_argv,
                        final_env);

        int saved = errno;

        free(script_argv);

        free_augmented_env(
                new_env,
                base_env);

        errno = saved;

        return ret;
    }

    /*
     * --------------------------------------------------------
     * 3. JDK tool
     *
     * Don't use JLI_Launch.
     * Continue through the normal executable chain.
     * --------------------------------------------------------
     */

    if (is_jdk_tool(pathname)) {

        LOGI(
                "[ExecRedirect] execve: "
                "JDK executable -> native exec chain: %s",
                pathname);
    }

    /*
     * --------------------------------------------------------
     * 4. noexec ELF
     *
     * Strategy:
     *
     *   A. memfd + execveat
     *   B. linker64
     *   C. procfd LAST
     *   D. normal execve
     * --------------------------------------------------------
     */

    if (is_noexec_path(pathname) &&
        is_elf(pathname)) {

        LOGI(
                "[ExecRedirect] execve: "
                "noexec ELF detected: %s",
                pathname);

        /*
         * Try the DIRECT execve FIRST.  The APK's nativeLibraryDir is
         * exec-permitted on current Android, so forcing every daemon/tool
         * fork through the memfd/linker64 dance was a false positive: the
         * dance's extra malloc/fd work inside the JVM's forkAndExec child
         * breaks the fail-pipe protocol and surfaces as a garbage errno
         * ("error=<big number>, Unknown error").  The dance is now only
         * the fallback when the mount is genuinely noexec.
         */
        int direct = real_execve(
                pathname,
                argv,
                envp ? envp : environ);
        int direct_err = errno;

        if (direct == 0) {
            return 0;
        }

        LOGI(
                "[ExecRedirect] execve: direct exec failed "
                "errno=%d (%s), path=%s",
                direct_err,
                strerror(direct_err),
                pathname);

        if (direct_err != EACCES &&
            direct_err != EPERM &&
            direct_err != ENOEXEC) {
            errno = direct_err;
            return direct;
        }

        char *const *base_env =
                envp ? envp : environ;

        char **new_env =
                ensure_preload(
                        base_env,
                        get_self_path());

        char *const *final_env =
                new_env
                ? new_env
                : base_env;

        int mfd =
                copy_to_memfd(
                        pathname);

        if (mfd >= 0) {

            int ret =
                    execute_memfd(
                            mfd,
                            argv,
                            final_env,
                            pathname);

            int saved = errno;

            /*
             * IMPORTANT:
             *
             * linker64 is now BEFORE /proc/self/fd.
             */
            if (ret < 0 &&
                is_arm64_elf(pathname)) {

                LOGI(
                        "[ExecRedirect] "
                        "execve strategy 2: "
                        "linker64");

                ret =
                        exec_with_linker64(
                                pathname,
                                argv,
                                final_env);

                saved = errno;
            }

            /*
             * /proc/self/fd is genuinely last.
             */
            if (ret < 0 &&
                (saved == ENOSYS ||
                 saved == EINVAL ||
                 saved == EPERM ||
                 saved == EACCES ||
                 saved == ENOEXEC)) {

                LOGI(
                        "[ExecRedirect] "
                        "execve strategy 3: "
                        "procfd LAST");

                ret =
                        execute_memfd_fallback(
                                mfd,
                                argv,
                                final_env,
                                pathname);

                saved = errno;
            }

            close(mfd);

            free_augmented_env(
                    new_env,
                    base_env);

            errno = saved;

            if (ret < 0) {

                LOGE(
                        "[ExecRedirect] "
                        "execve: ALL memfd strategies failed "
                        "target=%s errno=%d (%s)",
                        pathname,
                        saved,
                        strerror(saved));
            }

            return ret;
        }

        /*
         * memfd creation failed.
         *
         * Try linker64 directly before giving up.
         */
        int memfd_error = errno;

        LOGE(
                "[ExecRedirect] "
                "memfd creation failed for %s: "
                "errno=%d (%s)",
                pathname,
                memfd_error,
                strerror(memfd_error));

        if (is_arm64_elf(pathname)) {

            int ret =
                    exec_with_linker64(
                            pathname,
                            argv,
                            final_env);

            int saved = errno;

            free_augmented_env(
                    new_env,
                    base_env);

            errno = saved;

            return ret;
        }

        free_augmented_env(
                new_env,
                base_env);
    }

    /*
     * --------------------------------------------------------
     * 5. Normal executable
     * --------------------------------------------------------
     */

    return execve_with_retry(
            pathname,
            argv,
            envp);
}

/* ============================================================
 * execve retry
 * ============================================================ */

static int execve_with_retry(
        const char *pathname,
        char *const argv[],
        char *const envp[]) {

    init_real_symbols();

    int ret =
            real_execve(
                    pathname,
                    argv,
                    envp);

    int saved = errno;

    if (ret == 0) {
        return 0;
    }

    if ((saved == EACCES ||
         saved == EPERM) &&
        is_noexec_path(pathname)) {

        LOGI(
                "[ExecRedirect] "
                "normal execve failed: "
                "target=%s errno=%d (%s)",
                pathname,
                saved,
                strerror(saved));

        /*
         * Shell script.
         */
        if (is_shell_script(pathname)) {

            char **script_argv =
                    build_noexec_script_argv(
                            pathname,
                            argv);

            if (script_argv) {

                char *const *base_env =
                        envp ? envp : environ;

                char **new_env =
                        ensure_preload(
                                base_env,
                                get_self_path());

                char *const *final_env =
                        new_env
                        ? new_env
                        : base_env;

                ret =
                        real_execve(
                                "/system/bin/sh",
                                script_argv,
                                final_env);

                saved = errno;

                free(script_argv);

                free_augmented_env(
                        new_env,
                        base_env);

                if (ret == 0) {
                    return 0;
                }
            }
        }

        /*
         * ARM64 linker fallback.
         */
        if (is_arm64_elf(pathname)) {

            ret =
                    exec_with_linker64(
                            pathname,
                            argv,
                            envp);

            saved = errno;

            if (ret == 0) {
                return 0;
            }
        }

        /*
         * Last-resort memfd.
         */
        if (is_elf(pathname)) {

            char *const *base_env =
                    envp ? envp : environ;

            char **new_env =
                    ensure_preload(
                            base_env,
                            get_self_path());

            char *const *final_env =
                    new_env
                    ? new_env
                    : base_env;

            int mfd =
                    copy_to_memfd(
                            pathname);

            if (mfd >= 0) {

                ret =
                        execute_memfd(
                                mfd,
                                argv,
                                final_env,
                                pathname);

                saved = errno;

                if (ret < 0 &&
                    is_arm64_elf(pathname)) {

                    ret =
                            exec_with_linker64(
                                    pathname,
                                    argv,
                                    final_env);

                    saved = errno;
                }

                close(mfd);

                free_augmented_env(
                        new_env,
                        base_env);

                if (ret == 0) {
                    return 0;
                }
            }
        }
    }

    errno = saved;

    return ret;
}

/* ============================================================
 * execv()
 * ============================================================ */

int execv(
        const char *pathname,
        char *const argv[]) {

    return execve(
            pathname,
            argv,
            environ);
}

/* ============================================================
 * execvp()
 * ============================================================ */

int execvp(
        const char *file,
        char *const argv[]) {

    if (!file || !file[0]) {
        errno = EINVAL;
        return -1;
    }

    if (strchr(file, '/')) {
        return execve(
                file,
                argv,
                environ);
    }

    const char *path =
            getenv("PATH");

    if (!path || !path[0]) {
        path =
            "/system/bin:/system/xbin";
    }

    char *path_dup =
            strdup(path);

    if (!path_dup) {
        errno = ENOMEM;
        return -1;
    }

    char *save = NULL;

    char *dir =
            strtok_r(
                    path_dup,
                    ":",
                    &save);

    int last_error = ENOENT;

    while (dir) {

        size_t dlen =
                strlen(dir);

        size_t flen =
                strlen(file);

        char *full =
                (char *)malloc(
                        dlen +
                        1 +
                        flen +
                        1);

        if (full) {

            memcpy(
                    full,
                    dir,
                    dlen);

            full[dlen] = '/';

            memcpy(
                    full + dlen + 1,
                    file,
                    flen + 1);

            if (access(
                    full,
                    X_OK) == 0) {

                int ret =
                        execve(
                                full,
                                argv,
                                environ);

                last_error = errno;

                free(full);
                free(path_dup);

                errno = last_error;

                return ret;
            }

            last_error = errno;

            free(full);
        }

        dir =
                strtok_r(
                        NULL,
                        ":",
                        &save);
    }

    free(path_dup);

    errno =
            last_error == 0
            ? ENOENT
            : last_error;

    return -1;
}

/* ============================================================
 * execvpe()
 * ============================================================ */

int execvpe(
        const char *file,
        char *const argv[],
        char *const envp[]) {

    if (!file || !file[0]) {
        errno = EINVAL;
        return -1;
    }

    if (strchr(file, '/')) {

        return execve(
                file,
                argv,
                envp);
    }

    const char *path =
            getenv("PATH");

    if (!path || !path[0]) {
        path =
            "/system/bin:/system/xbin";
    }

    char *path_dup =
            strdup(path);

    if (!path_dup) {
        errno = ENOMEM;
        return -1;
    }

    char *save = NULL;

    char *dir =
            strtok_r(
                    path_dup,
                    ":",
                    &save);

    int last_error = ENOENT;

    while (dir) {

        size_t dlen =
                strlen(dir);

        size_t flen =
                strlen(file);

        char *full =
                (char *)malloc(
                        dlen +
                        1 +
                        flen +
                        1);

        if (full) {

            memcpy(
                    full,
                    dir,
                    dlen);

            full[dlen] = '/';

            memcpy(
                    full + dlen + 1,
                    file,
                    flen + 1);

            if (access(
                    full,
                    X_OK) == 0) {

                int ret =
                        execve(
                                full,
                                argv,
                                envp);

                last_error = errno;

                free(full);
                free(path_dup);

                errno = last_error;

                return ret;
            }

            last_error = errno;

            free(full);
        }

        dir =
                strtok_r(
                        NULL,
                        ":",
                        &save);
    }

    free(path_dup);

    errno =
            last_error == 0
            ? ENOENT
            : last_error;

    return -1;
}

/* ============================================================
 * fexecve()
 *
 * IMPORTANT:
 *
 * Never convert fd -> /proc/self/fd -> execve() first.
 *
 * Direct execveat(AT_EMPTY_PATH) is the whole point.
 * ============================================================ */

int fexecve(
        int fd,
        char *const argv[],
        char *const envp[]) {

    if (fd < 0) {
        errno = EBADF;
        return -1;
    }

    LOGI(
            "[ExecRedirect] fexecve: "
            "using execveat(fd, AT_EMPTY_PATH), fd=%d",
            fd);

    int ret =
            syscall_execveat(
                    fd,
                    "",
                    argv,
                    envp,
                    AT_EMPTY_PATH);

    int saved = errno;

    LOGI(
            "[ExecRedirect] fexecve: "
            "execveat result=%d errno=%d (%s)",
            ret,
            saved,
            saved
                ? strerror(saved)
                : "OK");

    /*
     * /proc/self/fd is NOT the primary route.
     *
     * Only use it when execveat itself is unavailable.
     */
    if (ret < 0 &&
        (saved == ENOSYS ||
         saved == EINVAL ||
         saved == EPERM)) {

        char fd_path[64];

        snprintf(
                fd_path,
                sizeof(fd_path),
                "/proc/self/fd/%d",
                fd);

        LOGI(
                "[ExecRedirect] fexecve: "
                "LAST procfd fallback: %s",
                fd_path);

        ret =
                real_execve(
                        fd_path,
                        argv,
                        envp);

        saved = errno;
    }

    errno = saved;

    return ret;
}

/* ============================================================
 * execveat()
 *
 * This is critical:
 *
 * The old code ignored flags and routed to execve().
 *
 * That breaks:
 *
 *     execveat(fd, "", ..., AT_EMPTY_PATH)
 *
 * which is exactly what we need.
 * ============================================================ */

int execveat(
        int dirfd,
        const char *pathname,
        char *const argv[],
        char *const envp[],
        int flags) {

    /*
     * AT_EMPTY_PATH:
     *
     *     execveat(fd, "", argv, envp, AT_EMPTY_PATH)
     *
     * must reach the kernel directly.
     */
    if ((flags & AT_EMPTY_PATH) &&
        pathname &&
        pathname[0] == '\0') {

        LOGI(
                "[ExecRedirect] execveat: "
                "AT_EMPTY_PATH fd=%d",
                dirfd);

        int ret =
                syscall_execveat(
                        dirfd,
                        pathname,
                        argv,
                        envp,
                        flags);

        int saved = errno;

        LOGI(
                "[ExecRedirect] execveat: "
                "AT_EMPTY_PATH result=%d "
                "errno=%d (%s)",
                ret,
                saved,
                saved
                    ? strerror(saved)
                    : "OK");

        errno = saved;

        return ret;
    }

    /*
     * Normal absolute path.
     */
    if (pathname &&
        pathname[0] == '/') {

        return execve(
                pathname,
                argv,
                envp);
    }

    /*
     * AT_FDCWD + relative path.
     */
    if (pathname &&
        pathname[0] != '\0' &&
        dirfd == AT_FDCWD) {

        char cwd[1024];

        if (getcwd(
                cwd,
                sizeof(cwd))) {

            size_t cwd_len =
                    strlen(cwd);

            size_t path_len =
                    strlen(pathname);

            char *full =
                    (char *)malloc(
                            cwd_len +
                            1 +
                            path_len +
                            1);

            if (full) {

                memcpy(
                        full,
                        cwd,
                        cwd_len);

                full[cwd_len] = '/';

                memcpy(
                        full + cwd_len + 1,
                        pathname,
                        path_len + 1);

                int ret =
                        execve(
                                full,
                                argv,
                                envp);

                int saved = errno;

                free(full);

                errno = saved;

                return ret;
            }
        }
    }

    /*
     * We don't have enough information to safely emulate
     * other execveat forms, so call the raw syscall.
     */
    return syscall_execveat(
            dirfd,
            pathname,
            argv,
            envp,
            flags);
}

/* ============================================================
 * posix_spawn()
 *
 * IMPORTANT:
 *
 * posix_spawn() returns:
 *
 *     0     success
 *     errno error number
 *
 * It does not behave like execve().
 * ============================================================ */

int posix_spawn(
        pid_t *pid,
        const char *path,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attrp,
        char *const argv[],
        char *const envp[]) {

    init_real_symbols();

    if (!real_posix_spawn) {
        return ENOSYS;
    }

    if (!path ||
        !path[0]) {
        return EINVAL;
    }

    /*
     * --------------------------------------------------------
     * 1. Native build tool
     * --------------------------------------------------------
     */

    char **native_tool_argv =
            build_native_tool_shell_argv(
                    path,
                    argv);

    if (native_tool_argv) {

        LOGI(
                "[ExecRedirect] posix_spawn: "
                "native build tool -> shell: %s",
                path);

        char *const *base_env =
                envp ? envp : environ;

        char **new_env =
                ensure_preload(
                        base_env,
                        get_self_path());

        char *const *final_env =
                new_env
                ? new_env
                : base_env;

        int ret =
                real_posix_spawn(
                        pid,
                        "/system/bin/sh",
                        file_actions,
                        attrp,
                        native_tool_argv,
                        final_env);

        LOGI(
                "[ExecRedirect] "
                "posix_spawn native-tool result=%d",
                ret);

        free(native_tool_argv);

        free_augmented_env(
                new_env,
                base_env);

        return ret;
    }

    /*
     * --------------------------------------------------------
     * 2. Shell script
     * --------------------------------------------------------
     */

    char **script_argv =
            build_noexec_script_argv(
                    path,
                    argv);

    if (script_argv) {

        LOGI(
                "[ExecRedirect] posix_spawn: "
                "script -> /system/bin/sh: %s",
                path);

        char *const *base_env =
                envp ? envp : environ;

        char **new_env =
                ensure_preload(
                        base_env,
                        get_self_path());

        char *const *final_env =
                new_env
                ? new_env
                : base_env;

        int ret =
                real_posix_spawn(
                        pid,
                        "/system/bin/sh",
                        file_actions,
                        attrp,
                        script_argv,
                        final_env);

        LOGI(
                "[ExecRedirect] "
                "posix_spawn script result=%d",
                ret);

        free(script_argv);

        free_augmented_env(
                new_env,
                base_env);

        return ret;
    }

    /*
     * --------------------------------------------------------
     * 3. noexec ARM64 ELF
     *
     * PRIMARY spawn strategy:
     *
     *     /system/bin/linker64 target args...
     *
     * NOT:
     *
     *     /proc/self/fd/N
     *
     * --------------------------------------------------------
     */

    if (is_noexec_path(path) &&
        is_elf(path)) {

        LOGI(
                "[ExecRedirect] posix_spawn: "
                "noexec ELF: %s",
                path);

        if (is_arm64_elf(path)) {

            int ret =
                    spawn_with_linker64(
                            pid,
                            path,
                            file_actions,
                            attrp,
                            argv,
                            envp);

            if (ret == 0) {

                LOGI(
                        "[ExecRedirect] "
                        "posix_spawn: "
                        "linker64 strategy succeeded");

                return 0;
            }

            LOGE(
                    "[ExecRedirect] "
                    "posix_spawn: "
                    "linker64 strategy failed: "
                    "error=%d (%s)",
                    ret,
                    strerror(ret));
        }

        /*
         * ----------------------------------------------------
         * SECONDARY:
         *
         * normal posix_spawn(path)
         *
         * This gives bionic one chance to execute it directly.
         * ----------------------------------------------------
         */

        LOGI(
                "[ExecRedirect] posix_spawn: "
                "trying direct spawn after linker64: %s",
                path);

        int direct_ret =
                real_posix_spawn(
                        pid,
                        path,
                        file_actions,
                        attrp,
                        argv,
                        envp);

        LOGI(
                "[ExecRedirect] "
                "posix_spawn direct result=%d",
                direct_ret);

        if (direct_ret == 0) {
            return 0;
        }

        /*
         * ----------------------------------------------------
         * LAST RESORT:
         *
         * memfd + /proc/self/fd
         *
         * This is intentionally after linker64 and direct spawn.
         * ----------------------------------------------------
         */

        int mfd =
                copy_to_inheritable_memfd(
                        path);

        if (mfd >= 0) {

            /*
             * Same rule as the execve path: memfds created without MFD_EXEC
             * are mode 0600, and spawning /proc/self/fd/N then fails with
             * EACCES.  Make it executable first.
             */
            if (fchmod(mfd, 0755) != 0) {

                int chmod_err = errno;

                LOGI(
                        "[ExecRedirect] strategy=fexecve "
                        "fd=%d fchmod(0755) result=-1 errno=%d (%s)",
                        mfd,
                        chmod_err,
                        strerror(chmod_err));
            }

            char fd_path[64];

            snprintf(
                    fd_path,
                    sizeof(fd_path),
                    "/proc/self/fd/%d",
                    mfd);

            LOGI(
                    "[ExecRedirect] "
                    "posix_spawn LAST strategy: "
                    "procfd=%s",
                    fd_path);

            char *const *base_env =
                    envp ? envp : environ;

            char **new_env =
                    ensure_preload(
                            base_env,
                            get_self_path());

            char *const *final_env =
                    new_env
                    ? new_env
                    : base_env;

            int ret =
                    real_posix_spawn(
                            pid,
                            fd_path,
                            file_actions,
                            attrp,
                            argv,
                            final_env);

            LOGI(
                    "[ExecRedirect] "
                    "posix_spawn procfd result=%d",
                    ret);

            close(mfd);

            free_augmented_env(
                    new_env,
                    base_env);

            if (ret == 0) {
                return 0;
            }

            LOGE(
                    "[ExecRedirect] "
                    "posix_spawn: ALL noexec "
                    "strategies failed: "
                    "last_error=%d (%s)",
                    ret,
                    strerror(ret));

            return ret;
        }

        LOGE(
                "[ExecRedirect] "
                "posix_spawn: memfd creation failed: "
                "errno=%d (%s)",
                errno,
                strerror(errno));

        /*
         * Return the original direct-spawn error.
         */
        return direct_ret;
    }

    /*
     * --------------------------------------------------------
     * 4. Normal executable
     * --------------------------------------------------------
     */

    int ret =
            real_posix_spawn(
                    pid,
                    path,
                    file_actions,
                    attrp,
                    argv,
                    envp);

    LOGV(
            "[ExecRedirect] "
            "posix_spawn normal path=%s result=%d",
            path,
            ret);

    /*
     * posix_spawn returns an error number.
     */
    if (ret != 0 &&
        (ret == EACCES ||
         ret == EPERM) &&
        is_noexec_path(path)) {

        LOGI(
                "[ExecRedirect] "
                "normal spawn denied: "
                "trying fallback: %s",
                path);

        if (is_shell_script(path)) {

            char **fallback_script =
                    build_noexec_script_argv(
                            path,
                            argv);

            if (fallback_script) {

                char *const *base_env =
                        envp ? envp : environ;

                char **new_env =
                        ensure_preload(
                                base_env,
                                get_self_path());

                char *const *final_env =
                        new_env
                        ? new_env
                        : base_env;

                int spawn_ret =
                        real_posix_spawn(
                                pid,
                                "/system/bin/sh",
                                file_actions,
                                attrp,
                                fallback_script,
                                final_env);

                free(fallback_script);

                free_augmented_env(
                        new_env,
                        base_env);

                if (spawn_ret == 0) {
                    return 0;
                }

                ret = spawn_ret;
            }
        }

        if (is_arm64_elf(path)) {

            int linker_ret =
                    spawn_with_linker64(
                            pid,
                            path,
                            file_actions,
                            attrp,
                            argv,
                            envp);

            if (linker_ret == 0) {
                return 0;
            }

            ret = linker_ret;
        }
    }

    return ret;
}

/* ============================================================
 * posix_spawnp()
 * ============================================================ */

int posix_spawnp(
        pid_t *pid,
        const char *file,
        const posix_spawn_file_actions_t *file_actions,
        const posix_spawnattr_t *attrp,
        char *const argv[],
        char *const envp[]) {

    init_real_symbols();

    if (!real_posix_spawnp) {
        return ENOSYS;
    }

    if (!file ||
        !file[0]) {
        return EINVAL;
    }

    /*
     * Absolute path:
     *
     * route through our posix_spawn hook.
     */
    if (file[0] == '/') {

        return posix_spawn(
                pid,
                file,
                file_actions,
                attrp,
                argv,
                envp);
    }

    /*
     * Search PATH ourselves so that:
     *
     *     posix_spawnp("java", ...)
     *
     * can still reach our noexec/JDK handling.
     */
    const char *path_env =
            getenv("PATH");

    if (!path_env ||
        !path_env[0]) {

        path_env =
            "/system/bin:/system/xbin";
    }

    char *path_dup =
            strdup(path_env);

    if (!path_dup) {
        return ENOMEM;
    }

    char *save = NULL;

    char *dir =
            strtok_r(
                    path_dup,
                    ":",
                    &save);

    int last_error = ENOENT;

    while (dir) {

        size_t dlen =
                strlen(dir);

        size_t flen =
                strlen(file);

        char *full =
                (char *)malloc(
                        dlen +
                        1 +
                        flen +
                        1);

        if (full) {

            memcpy(
                    full,
                    dir,
                    dlen);

            full[dlen] = '/';

            memcpy(
                    full + dlen + 1,
                    file,
                    flen + 1);

            if (access(
                    full,
                    X_OK) == 0) {

                int ret =
                        posix_spawn(
                                pid,
                                full,
                                file_actions,
                                attrp,
                                argv,
                                envp);

                last_error = ret;

                free(full);
                free(path_dup);

                return ret;
            }

            last_error = errno;

            free(full);
        }

        dir =
                strtok_r(
                        NULL,
                        ":",
                        &save);
    }

    free(path_dup);

    return last_error
           ? last_error
           : ENOENT;
}
/* ============================================================
 * Exec-family PLT rebind
 *
 * The Gradle client starts via fork + JLI_Launch (Strategy 1): no
 * execve happens for the client process itself, so LD_PRELOAD never
 * applies to it.  The manual dlopen(RTLD_GLOBAL) of
 * libexec_redirect.so runs too late to win libjava.so's symbol
 * resolution - libjava has DT_NEEDED libc.so and its execve@PLT
 * resolves to libc before the RTLD_GLOBAL group is consulted.  The
 * daemon spawn (ProcessBuilder -> forkAndExec -> JDK_execvpe ->
 * execve@plt) then calls libc's real execve on
 * /data/.../toolchain/jdk/bin/java and fails with EACCES (SELinux
 * execute denial on app_data_file for untrusted_app).
 *
 * Rewriting the already-resolved PLT GOT slots to our interposers
 * fixes the binding regardless of load order or symbol versioning.
 *
 * Invoked from the constructor (covers LD_PRELOAD-started processes:
 * sh, linker64, the Gradle daemon) and explicitly from GradleExec_C
 * after the manual preloads (covers the fork+JLI client).
 * ============================================================ */

static void *exec_rebind_target(const char *name) {
    if (!name || !name[0]) return NULL;
    if (!strcmp(name, "execve"))         return (void *)execve;
    if (!strcmp(name, "execv"))          return (void *)execv;
    if (!strcmp(name, "execvp"))         return (void *)execvp;
    if (!strcmp(name, "execvpe"))        return (void *)execvpe;
    if (!strcmp(name, "execveat"))       return (void *)execveat;
    if (!strcmp(name, "fexecve"))        return (void *)fexecve;
    if (!strcmp(name, "posix_spawn"))    return (void *)posix_spawn;
    if (!strcmp(name, "posix_spawnp"))   return (void *)posix_spawnp;
    if (!strcmp(name, "vfork"))          return (void *)vfork;
    return NULL;
}

struct exec_rebind_state {
    int objects;
    int slots;
};

static int exec_rebind_object(
        struct dl_phdr_info *info,
        size_t size,
        void *data) {

    (void)size;

    struct exec_rebind_state *st = (struct exec_rebind_state *)data;

    if (!info->dlpi_name || !info->dlpi_name[0]) {
        return 0;
    }

    if (strstr(info->dlpi_name, "libexec_redirect.so")) {
        return 0;
    }

    const Elf64_Phdr *dyn_phdr = NULL;

    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn_phdr = &info->dlpi_phdr[i];
            break;
        }
    }

    if (!dyn_phdr) {
        return 0;
    }

    const Elf64_Dyn *dyn =
            (const Elf64_Dyn *)(info->dlpi_addr + dyn_phdr->p_vaddr);

    const Elf64_Sym *symtab = NULL;
    const char *strtab = NULL;
    const Elf64_Rela *rela = NULL;
    size_t rela_bytes = 0;
    int plt_is_rela = 0;

    for (; dyn->d_tag != DT_NULL; dyn++) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                symtab =
                        (const Elf64_Sym *)(info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                strtab =
                        (const char *)(info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_JMPREL:
                rela =
                        (const Elf64_Rela *)(info->dlpi_addr + dyn->d_un.d_ptr);
                break;
            case DT_PLTRELSZ:
                rela_bytes = dyn->d_un.d_val;
                break;
            case DT_PLTREL:
                plt_is_rela = (dyn->d_un.d_val == DT_RELA);
                break;
            default:
                break;
        }
    }

    if (!symtab || !strtab || !rela || rela_bytes == 0 || !plt_is_rela) {
        return 0;
    }

    int patched = 0;
    size_t count = rela_bytes / sizeof(Elf64_Rela);

    for (size_t i = 0; i < count; i++) {

        if (ELF64_R_TYPE(rela[i].r_info) != R_AARCH64_JUMP_SLOT) {
            continue;
        }

        unsigned long sym_idx = ELF64_R_SYM(rela[i].r_info);

        /*
         * Only rebind slots IMPORTED from another object (SHN_UNDEF).
         * Objects that DEFINE the symbol locally (libc.so's own PLT
         * entries for execve/posix_spawn) must keep their bindings:
         * libc's posix_spawn performs vfork+execve, and running our
         * interposer (malloc/log) in the shared vfork address space
         * would corrupt the parent.
         */
        if (symtab[sym_idx].st_shndx != SHN_UNDEF) {
            continue;
        }

        const char *name = strtab + symtab[sym_idx].st_name;

        void *target = exec_rebind_target(name);
        if (!target) {
            continue;
        }

        uintptr_t slot =
                (uintptr_t)(info->dlpi_addr + rela[i].r_offset);
        uintptr_t page = slot & ~(uintptr_t)4095;

        if (real_mprotect(
                (void *)page,
                4096,
                PROT_READ | PROT_WRITE) != 0) {

            LOGE(
                    "[ExecRedirect] rebind: mprotect(RW) %s in %s "
                    "failed: %s",
                    name,
                    info->dlpi_name,
                    strerror(errno));
            continue;
        }

        *(void **)slot = target;
        patched++;

        for (int p = 0; p < info->dlpi_phnum; p++) {
            if (info->dlpi_phdr[p].p_type != PT_GNU_RELRO) {
                continue;
            }

            uintptr_t relro_start =
                    (uintptr_t)(info->dlpi_addr +
                                info->dlpi_phdr[p].p_vaddr);
            uintptr_t relro_end =
                    relro_start + info->dlpi_phdr[p].p_memsz;

            if (slot >= relro_start && slot < relro_end) {
                real_mprotect((void *)page, 4096, PROT_READ);
                break;
            }
        }
    }

    if (patched > 0) {
        st->objects++;
        st->slots += patched;

        LOGI(
                "[ExecRedirect] rebind: %s -> %d exec-family slots",
                info->dlpi_name,
                patched);
    }

    return 0;
}

int exec_redirect_rebind_exec_slots(void) {

    init_real_symbols();

    if (!real_mprotect) {
        return 0;
    }

    struct exec_rebind_state st = {0, 0};

    dl_iterate_phdr(exec_rebind_object, &st);

    LOGI(
            "[ExecRedirect] rebind: %d slots in %d objects",
            st.slots,
            st.objects);

    return st.slots;
}
