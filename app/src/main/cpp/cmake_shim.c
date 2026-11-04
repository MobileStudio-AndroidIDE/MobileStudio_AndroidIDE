#define _GNU_SOURCE
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>

static char *find_env(char *envp[], const char *name) {
    int nlen = strlen(name);
    for (int i = 0; envp[i]; i++) {
        if (strncmp(envp[i], name, nlen) == 0 && envp[i][nlen] == '=')
            return envp[i] + nlen + 1;
    }
    return NULL;
}

int main(int argc, char *argv[], char *envp[]) {
    const char *real_cmake = getenv("MOBILESTUDIO_REAL_CMAKE_DIR");
    if (!real_cmake) {
        real_cmake = "/data/data/com.aide.mobilestudio/files/toolchain/sdk/android-34/cmake/3.22.1/bin/cmake";
    }

    // Derive cmake lib dir from binary path
    char cmake_lib_dir[512];
    strncpy(cmake_lib_dir, real_cmake, sizeof(cmake_lib_dir) - 10);
    cmake_lib_dir[sizeof(cmake_lib_dir) - 10] = '\0';
    char *p = strrchr(cmake_lib_dir, '/');
    if (p) {
        strcpy(p + 1, "lib");
    } else {
        strcpy(cmake_lib_dir, "/data/data/com.aide.mobilestudio/files/toolchain/sdk/android-34/cmake/3.22.1/lib");
    }

    // Build new LD_LIBRARY_PATH
    char new_ld[4096];
    new_ld[0] = '\0';
    strcat(new_ld, cmake_lib_dir);
    if (access("/data/data/com.termux/files/usr/lib", F_OK) == 0) {
        strcat(new_ld, ":/data/data/com.termux/files/usr/lib");
    }
    const char *old_ld = find_env(envp, "LD_LIBRARY_PATH");
    if (old_ld && old_ld[0]) {
        strcat(new_ld, ":");
        strcat(new_ld, old_ld);
    }

    // Count envp entries
    int envc = 0;
    while (envp[envc]) envc++;

    // Build new envp: +2 for LD_LIBRARY_PATH and NULL terminator
    char **new_envp = malloc((envc + 3) * sizeof(char *));
    if (!new_envp) return 1;

    int ei = 0;
    int replaced = 0;
    for (int i = 0; i < envc; i++) {
        if (strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0) {
            // Replace with our version
            char *entry = malloc(16 + strlen(new_ld) + 1);
            if (!entry) return 1;
            strcpy(entry, "LD_LIBRARY_PATH=");
            strcat(entry, new_ld);
            new_envp[ei++] = entry;
            replaced = 1;
        } else {
            new_envp[ei++] = envp[i];
        }
    }
    if (!replaced) {
        char *entry = malloc(16 + strlen(new_ld) + 1);
        if (!entry) return 1;
        strcpy(entry, "LD_LIBRARY_PATH=");
        strcat(entry, new_ld);
        new_envp[ei++] = entry;
    }
    new_envp[ei] = NULL;

    // Also set in current environment for any library calls
    setenv("LD_LIBRARY_PATH", new_ld, 1);

    // Build argv for linker64
    int new_argc = 1 + argc;
    char **new_argv = malloc((new_argc + 1) * sizeof(char *));
    if (!new_argv) return 1;
    int i = 0;
    new_argv[i++] = "/system/bin/linker64";
    new_argv[i++] = (char *)real_cmake;
    for (int j = 1; j < argc; j++) new_argv[i++] = argv[j];
    new_argv[i] = NULL;

    execve("/system/bin/linker64", new_argv, new_envp);
    fprintf(stderr, "cmake_shim: execve linker64: %s\n", strerror(errno));
    return 1;
}
