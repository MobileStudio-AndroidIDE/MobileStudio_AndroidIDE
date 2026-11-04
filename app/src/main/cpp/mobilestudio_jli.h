/*
 * mobilestudio_jli.h
 * Shared JLI_Launch bootstrap for NativeExec and ExecRedirect.
 * Provides in-process JVM launch via dlopen(libjli.so) + JLI_Launch,
 * bypassing execve on Android noexec partitions.
 */

#ifndef MOBILESTUDIO_JLI_H
#define MOBILESTUDIO_JLI_H

#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Launch JVM in-process via JLI_Launch (no execve).
 * 
 * @param java_bin     Path to java binary (used to derive JAVA_HOME)
 * @param argv         Argument vector (argv[0] = "java", ...)
 * @param envp         Environment vector
 * @param cwd          Working directory (can be NULL)
 * @return             Exit code from JLI_Launch (0 on success), -1 on failure
 *                     This function NEVER RETURNS on success (calls _exit).
 *                     Returns -1 only if setup fails before JLI_Launch.
 */
int mobilestudio_jli_launch_in_process(const char *java_bin,
                                        char *const argv[],
                                        char *const envp[],
                                        const char *cwd);

/**
 * Launch JVM in-process with explicit JAVA_HOME.
 * 
 * @param java_home    Explicit JAVA_HOME path
 * @param argv         Argument vector (argv[0] = "java", ...)
 * @param envp         Environment vector
 * @param cwd          Working directory (can be NULL)
 * @return             Exit code (never returns on success)
 */
int mobilestudio_jli_launch_in_process_with_home(const char *java_home,
                                                  char *const argv[],
                                                  char *const envp[],
                                                  const char *cwd);

/**
 * Preload JDK libraries for JLI_Launch (lib/server copy + LD_PRELOAD).
 * Should be called before JLI_Launch in the child process.
 * 
 * @param java_home    JAVA_HOME path
 * @return             0 on success, -1 on failure
 */
int mobilestudio_jli_preload_libs(const char *java_home);

/**
 * Find and dlopen libjli.so
 * 
 * @param java_home    JAVA_HOME path
 * @return             dlopen handle (RTLD_GLOBAL), or NULL on failure
 */
void *mobilestudio_jli_open_libjli(const char *java_home);

/**
 * Disable bionic heap tagging (mallopt) for JDK compatibility.
 * Must be called BEFORE any dlopen/malloc in the process.
 * 
 * @return 0 on success, -1 on failure
 */
int mobilestudio_jli_disable_heap_tagging(void);

/**
 * Determine JAVA_HOME from java binary path or environment.
 * 
 * @param java_bin     Path to java binary
 * @param envp         Environment vector
 * @param out_buf      Output buffer (caller must provide, size >= 1024)
 * @return             0 on success, -1 on failure
 */
int mobilestudio_jli_resolve_java_home(const char *java_bin,
                                        char *const envp[],
                                        char *out_buf,
                                        size_t out_buf_size);

/**
 * Apply child environment (putenv for each entry).
 * 
 * @param envp         Environment vector
 */
void mobilestudio_jli_apply_child_env(char *const envp[]);

/**
 * Prepend JDK library paths to LD_LIBRARY_PATH.
 * 
 * @param java_home    JAVA_HOME path
 */
void mobilestudio_jli_prepend_ld_paths(const char *java_home);

#ifdef __cplusplus
}
#endif

#endif /* MOBILESTUDIO_JLI_H */