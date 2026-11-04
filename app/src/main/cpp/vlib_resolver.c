#include "vlib_resolver.h"

#include <dirent.h>
#include <errno.h>
#include <libgen.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "elf_analysis.h"

static char search_paths[VLR_MAX_PATHS][VLR_PATH_LEN];
static int search_path_count = 0;
static char runtime_dir[VLR_PATH_LEN] = {0};
static char native_lib_dir[VLR_PATH_LEN] = {0};
static pthread_mutex_t resolver_mutex = PTHREAD_MUTEX_INITIALIZER;

static const char *system_paths[] = {
    "/system/lib64",
    "/system/lib",
    "/apex/com.android.runtime/lib64",
    "/apex/com.android.runtime/lib",
    NULL
};

int vlr_init(void) {
    pthread_mutex_lock(&resolver_mutex);
    search_path_count = 0;

    for (int i = 0; system_paths[i]; i++) {
        if (search_path_count < VLR_MAX_PATHS) {
            strncpy(search_paths[search_path_count], system_paths[i], VLR_PATH_LEN - 1);
            search_path_count++;
        }
    }

    const char *ld_path = getenv("LD_LIBRARY_PATH");
    if (ld_path && *ld_path) {
        char *dup = strdup(ld_path);
        if (dup) {
            char *save = NULL;
            char *dir = strtok_r(dup, ":", &save);
            while (dir && search_path_count < VLR_MAX_PATHS) {
                strncpy(search_paths[search_path_count], dir, VLR_PATH_LEN - 1);
                search_path_count++;
                dir = strtok_r(NULL, ":", &save);
            }
            free(dup);
        }
    }

    pthread_mutex_unlock(&resolver_mutex);
    return 0;
}

void vlr_cleanup(void) {
    pthread_mutex_lock(&resolver_mutex);
    search_path_count = 0;
    memset(search_paths, 0, sizeof(search_paths));
    pthread_mutex_unlock(&resolver_mutex);
}

void vlr_add_search_path(const char *path) {
    if (!path || !*path) return;
    pthread_mutex_lock(&resolver_mutex);

    for (int i = 0; i < search_path_count; i++) {
        if (strcmp(search_paths[i], path) == 0) {
            pthread_mutex_unlock(&resolver_mutex);
            return;
        }
    }

    if (search_path_count < VLR_MAX_PATHS) {
        strncpy(search_paths[search_path_count], path, VLR_PATH_LEN - 1);
        search_paths[search_path_count][VLR_PATH_LEN - 1] = '\0';
        search_path_count++;
    }

    pthread_mutex_unlock(&resolver_mutex);
}

void vlr_set_runtime_dir(const char *dir) {
    if (!dir) return;
    pthread_mutex_lock(&resolver_mutex);
    strncpy(runtime_dir, dir, VLR_PATH_LEN - 1);
    runtime_dir[VLR_PATH_LEN - 1] = '\0';

    char buf[VLR_PATH_LEN];
    snprintf(buf, sizeof(buf), "%s/lib", dir);
    vlr_add_search_path(buf);
    snprintf(buf, sizeof(buf), "%s/lib64", dir);
    vlr_add_search_path(buf);
    snprintf(buf, sizeof(buf), "%s/usr/lib", dir);
    vlr_add_search_path(buf);
    snprintf(buf, sizeof(buf), "%s/usr/lib64", dir);
    vlr_add_search_path(buf);

    pthread_mutex_unlock(&resolver_mutex);
}

void vlr_set_native_lib_dir(const char *dir) {
    if (!dir) return;
    pthread_mutex_lock(&resolver_mutex);
    strncpy(native_lib_dir, dir, VLR_PATH_LEN - 1);
    native_lib_dir[VLR_PATH_LEN - 1] = '\0';
    vlr_add_search_path(dir);
    pthread_mutex_unlock(&resolver_mutex);
}

static int find_library(const char *lib_name, char *out_path, size_t path_sz) {
    char path_buf[VLR_PATH_LEN];

    for (int i = 0; i < search_path_count; i++) {
        snprintf(path_buf, sizeof(path_buf), "%s/%s", search_paths[i], lib_name);
        if (access(path_buf, R_OK) == 0) {
            strncpy(out_path, path_buf, path_sz - 1);
            out_path[path_sz - 1] = '\0';
            return 0;
        }
    }

    if (runtime_dir[0]) {
        snprintf(path_buf, sizeof(path_buf), "%s/lib/%s", runtime_dir, lib_name);
        if (access(path_buf, R_OK) == 0) {
            strncpy(out_path, path_buf, path_sz - 1);
            out_path[path_sz - 1] = '\0';
            return 0;
        }
        snprintf(path_buf, sizeof(path_buf), "%s/lib64/%s", runtime_dir, lib_name);
        if (access(path_buf, R_OK) == 0) {
            strncpy(out_path, path_buf, path_sz - 1);
            out_path[path_sz - 1] = '\0';
            return 0;
        }
    }

    return -ENOENT;
}

static int find_library_with_soname(const char *lib_name, char *out_path, size_t path_sz) {
    if (find_library(lib_name, out_path, path_sz) == 0) return 0;

    size_t name_len = strlen(lib_name);
    if (name_len > 3 && strcmp(lib_name + name_len - 3, ".so") == 0) {
        char pattern[EA_MAX_SONAME];
        snprintf(pattern, sizeof(pattern), "%s.", lib_name);

        for (int i = 0; i < search_path_count; i++) {
            DIR *dir = opendir(search_paths[i]);
            if (!dir) continue;
            struct dirent *ent;
            while ((ent = readdir(dir)) != NULL) {
                if (strncmp(ent->d_name, pattern, strlen(pattern)) == 0) {
                    char full[VLR_PATH_LEN];
                    snprintf(full, sizeof(full), "%s/%s", search_paths[i], ent->d_name);
                    if (access(full, R_OK) == 0) {
                        strncpy(out_path, full, path_sz - 1);
                        out_path[path_sz - 1] = '\0';
                        closedir(dir);
                        return 0;
                    }
                }
            }
            closedir(dir);
        }
    }

    return -ENOENT;
}

static int validate_architecture(const char *path) {
    elf_info_t info;
    int ret = elf_analyze(path, &info);
    if (ret < 0) return -ENOEXEC;
    if (!info.is_64bit || info.machine != EA_ARCH_AARCH64) return -ENOEXEC;
    return 0;
}

static int resolve_recursive(const char *lib_name, const char *required_by,
                              vlr_result_t *result, char visited[][EA_MAX_SONAME],
                              size_t *visited_count, int depth) {
    if (depth > VLR_MAX_DEPTH) {
        if (result->missing_count < VLR_MAX_MISSING) {
            vlr_missing_t *m = &result->missing[result->missing_count++];
            strncpy(m->lib_name, lib_name, EA_MAX_SONAME - 1);
            strncpy(m->required_by, required_by, VLR_PATH_LEN - 1);
            m->search_count = 0;
        }
        return -ELOOP;
    }

    for (size_t i = 0; i < *visited_count; i++) {
        if (strcmp(visited[i], lib_name) == 0) return 0;
    }

    for (size_t i = 0; i < result->resolved_count; i++) {
        if (strcmp(result->resolved[i].lib_name, lib_name) == 0 &&
            result->resolved[i].found) {
            return 0;
        }
    }

    if (*visited_count < VLR_MAX_DEPTH) {
        strncpy(visited[*visited_count], lib_name, EA_MAX_SONAME - 1);
        (*visited_count)++;
    }

    char resolved_path[VLR_PATH_LEN];
    if (find_library_with_soname(lib_name, resolved_path, sizeof(resolved_path)) < 0) {
        if (result->missing_count < VLR_MAX_MISSING) {
            vlr_missing_t *m = &result->missing[result->missing_count++];
            strncpy(m->lib_name, lib_name, EA_MAX_SONAME - 1);
            strncpy(m->required_by, required_by, VLR_PATH_LEN - 1);
            m->search_count = 0;
            for (int i = 0; i < search_path_count && i < VLR_MAX_MISSING; i++) {
                strncpy(m->searched_paths[i], search_paths[i], VLR_PATH_LEN - 1);
                m->search_count++;
            }
        }
        return -ENOENT;
    }

    if (validate_architecture(resolved_path) < 0) {
        if (result->missing_count < VLR_MAX_MISSING) {
            vlr_missing_t *m = &result->missing[result->missing_count++];
            snprintf(m->lib_name, EA_MAX_SONAME, "%s (wrong architecture)", lib_name);
            strncpy(m->required_by, required_by, VLR_PATH_LEN - 1);
            m->search_count = 0;
        }
        return -ENOEXEC;
    }

    if (result->resolved_count < EA_MAX_NEEDED * VLR_MAX_DEPTH) {
        vlr_resolved_t *r = &result->resolved[result->resolved_count++];
        strncpy(r->lib_name, lib_name, EA_MAX_SONAME - 1);
        strncpy(r->resolved_path, resolved_path, VLR_PATH_LEN - 1);
        strncpy(r->required_by, required_by, VLR_PATH_LEN - 1);
        r->found = 1;
    }

    elf_info_t info;
    if (elf_analyze(resolved_path, &info) < 0) return 0;

    for (size_t i = 0; i < info.needed_count; i++) {
        int ret = resolve_recursive(info.needed[i], lib_name, result,
                                     visited, visited_count, depth + 1);
        if (ret < 0 && ret != -ELOOP) return ret;
    }

    return 0;
}

static void build_ld_library_path(vlr_result_t *result) {
    char dirs[VLR_MAX_MISSING][VLR_PATH_LEN];
    int dir_count = 0;

    for (size_t i = 0; i < result->resolved_count; i++) {
        if (!result->resolved[i].found) continue;
        char *slash = strrchr(result->resolved[i].resolved_path, '/');
        if (!slash) continue;
        size_t dir_len = (size_t)(slash - result->resolved[i].resolved_path);
        if (dir_len == 0) continue;

        int dup = 0;
        for (int j = 0; j < dir_count; j++) {
            if (strncmp(dirs[j], result->resolved[i].resolved_path, dir_len) == 0 &&
                dirs[j][dir_len] == '\0') {
                dup = 1;
                break;
            }
        }
        if (!dup && dir_count < VLR_MAX_MISSING) {
            memcpy(dirs[dir_count], result->resolved[i].resolved_path, dir_len);
            dirs[dir_count][dir_len] = '\0';
            dir_count++;
        }
    }

    size_t pos = 0;
    for (int i = 0; i < dir_count; i++) {
        size_t len = strlen(dirs[i]);
        if (pos + len + 1 < sizeof(result->ld_library_path)) {
            if (pos > 0) result->ld_library_path[pos++] = ':';
            memcpy(result->ld_library_path + pos, dirs[i], len);
            pos += len;
        }
    }
    result->ld_library_path[pos] = '\0';
}

int vlr_resolve(const char *executable_path, vlr_result_t *result) {
    if (!executable_path || !result) return -EINVAL;
    memset(result, 0, sizeof(vlr_result_t));

    elf_info_t exe_info;
    int ret = elf_analyze(executable_path, &exe_info);
    if (ret < 0) {
        snprintf(result->error, sizeof(result->error),
                 "Failed to analyze ELF: %s", strerror(-ret));
        return ret;
    }

    if (!exe_info.is_64bit || exe_info.machine != EA_ARCH_AARCH64) {
        snprintf(result->error, sizeof(result->error),
                 "ELF architecture mismatch\n"
                 "  Expected: AArch64 / arm64-v8a\n"
                 "  Actual:   %s",
                 elf_arch_str(exe_info.machine));
        return -ENOEXEC;
    }

    if (exe_info.rpath[0]) {
        char *save = NULL;
        char *dup = strdup(exe_info.rpath);
        char *dir = strtok_r(dup, ":", &save);
        while (dir) {
            vlr_add_search_path(dir);
            dir = strtok_r(NULL, ":", &save);
        }
        free(dup);
    }

    if (exe_info.runpath[0]) {
        char *save = NULL;
        char *dup = strdup(exe_info.runpath);
        char *dir = strtok_r(dup, ":", &save);
        while (dir) {
            vlr_add_search_path(dir);
            dir = strtok_r(NULL, ":", &save);
        }
        free(dup);
    }

    char executable_dir[VLR_PATH_LEN];
    strncpy(executable_dir, executable_path, VLR_PATH_LEN - 1);
    executable_dir[VLR_PATH_LEN - 1] = '\0';
    char *sl = strrchr(executable_dir, '/');
    if (sl) {
        *sl = '\0';
        vlr_add_search_path(executable_dir);
    }

    char visited[EA_MAX_NEEDED * VLR_MAX_DEPTH][EA_MAX_SONAME];
    size_t visited_count = 0;

    for (size_t i = 0; i < exe_info.needed_count; i++) {
        ret = resolve_recursive(exe_info.needed[i], executable_path,
                                result, visited, &visited_count, 0);
        if (ret < 0 && ret != -ELOOP) {
            snprintf(result->error, sizeof(result->error),
                     "Failed to resolve %s", exe_info.needed[i]);
            return ret;
        }
    }

    build_ld_library_path(result);

    result->success = 1;
    return 0;
}

const char *vlr_get_search_paths_string(void) {
    static char buf[8192];
    size_t pos = 0;

    const char *ld = getenv("LD_LIBRARY_PATH");
    if (ld && *ld) {
        size_t len = strlen(ld);
        if (pos + len + 2 < sizeof(buf)) {
            memcpy(buf + pos, ld, len);
            pos += len;
            buf[pos++] = ':';
        }
    }

    pthread_mutex_lock(&resolver_mutex);
    for (int i = 0; i < search_path_count; i++) {
        size_t len = strlen(search_paths[i]);
        if (pos + len + 1 < sizeof(buf)) {
            if (pos > 0) buf[pos++] = ':';
            memcpy(buf + pos, search_paths[i], len);
            pos += len;
        }
    }
    pthread_mutex_unlock(&resolver_mutex);

    buf[pos] = '\0';
    return buf;
}
