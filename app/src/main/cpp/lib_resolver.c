#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "elf_parse.h"
#include "lib_resolver.h"

#define MAX_SEARCH_PATHS 32
#define MAX_CACHE_ENTRIES 256
#define CACHE_FILE_VERSION 1

static char *search_paths[MAX_SEARCH_PATHS];
static int search_path_count = 0;

static lib_cache_entry_t cache[MAX_CACHE_ENTRIES];
static int cache_count = 0;
static pthread_mutex_t cache_mutex = PTHREAD_MUTEX_INITIALIZER;
static char cache_dir[512] = {0};

static const char *default_search_paths[] = {
    "/data/user/0/com.aide.mobilestudio/files/toolchain/runtime-tools/lib",
    "/data/user/0/com.aide.mobilestudio/files/toolchain/llvm/lib",
    "/data/user/0/com.aide.mobilestudio/files/toolchain/llvm/lib64",
    "/data/user/0/com.aide.mobilestudio/files/toolchain/ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android",
    "/system/lib64",
    "/system/lib",
    NULL
};

static int find_in_path(const char *lib_name, char *out_path, size_t path_sz) {
    char path_buf[1024];
    
    const char *ld_path = getenv("LD_LIBRARY_PATH");
    if (ld_path) {
        char *ld_dup = strdup(ld_path);
        if (ld_dup) {
            char *save = NULL;
            char *dir = strtok_r(ld_dup, ":", &save);
            while (dir) {
                snprintf(path_buf, sizeof(path_buf), "%s/%s", dir, lib_name);
                if (access(path_buf, R_OK) == 0) {
                    strncpy(out_path, path_buf, path_sz - 1);
                    out_path[path_sz - 1] = '\0';
                    free(ld_dup);
                    return 0;
                }
                dir = strtok_r(NULL, ":", &save);
            }
            free(ld_dup);
        }
    }

    for (int i = 0; i < search_path_count; i++) {
        snprintf(path_buf, sizeof(path_buf), "%s/%s", search_paths[i], lib_name);
        if (access(path_buf, R_OK) == 0) {
            strncpy(out_path, path_buf, path_sz - 1);
            out_path[path_sz - 1] = '\0';
            return 0;
        }
    }

    for (int i = 0; default_search_paths[i]; i++) {
        snprintf(path_buf, sizeof(path_buf), "%s/%s", default_search_paths[i], lib_name);
        if (access(path_buf, R_OK) == 0) {
            strncpy(out_path, path_buf, path_sz - 1);
            out_path[path_sz - 1] = '\0';
            return 0;
        }
    }

    return -ENOENT;
}

static int cache_index_for(const char *lib_name, const char *build_id) {
    uint32_t hash = 5381;
    for (const char *p = lib_name; *p; p++) {
        hash = ((hash << 5) + hash) + *p;
    }
    if (build_id && *build_id) {
        for (const char *p = build_id; *p; p++) {
            hash = ((hash << 5) + hash) + *p;
        }
    }
    return hash % MAX_CACHE_ENTRIES;
}

int lib_resolver_init(const char *cache_dir_path) {
    if (cache_dir_path) {
        strncpy(cache_dir, cache_dir_path, sizeof(cache_dir) - 1);
        cache_dir[sizeof(cache_dir) - 1] = '\0';
        mkdir(cache_dir, 0700);
    }

    for (int i = 0; default_search_paths[i]; i++) {
        if (search_path_count < MAX_SEARCH_PATHS) {
            search_paths[search_path_count++] = strdup(default_search_paths[i]);
        }
    }

    char cache_file[512];
    snprintf(cache_file, sizeof(cache_file), "%s/lib_cache.bin", cache_dir);
    
    int fd = open(cache_file, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        int version;
        if (read(fd, &version, sizeof(version)) == sizeof(version) && version == CACHE_FILE_VERSION) {
            int count;
            if (read(fd, &count, sizeof(count)) == sizeof(count) && count >= 0 && count < MAX_CACHE_ENTRIES) {
                for (int i = 0; i < count; i++) {
                    lib_cache_entry_t entry;
                    if (read(fd, &entry, sizeof(entry)) == sizeof(entry)) {
                        if (cache_count < MAX_CACHE_ENTRIES) {
                            cache[cache_count++] = entry;
                        }
                    }
                }
            }
        }
        close(fd);
    }

    return 0;
}

void lib_resolver_cleanup(void) {
    pthread_mutex_lock(&cache_mutex);

    if (cache_dir[0]) {
        char cache_file[512];
        snprintf(cache_file, sizeof(cache_file), "%s/lib_cache.bin", cache_dir);
        
        int fd = open(cache_file, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (fd >= 0) {
            int version = CACHE_FILE_VERSION;
            write(fd, &version, sizeof(version));
            write(fd, &cache_count, sizeof(cache_count));
            for (int i = 0; i < cache_count; i++) {
                write(fd, &cache[i], sizeof(cache[i]));
            }
            close(fd);
        }
    }

    for (int i = 0; i < search_path_count; i++) {
        free(search_paths[i]);
        search_paths[i] = NULL;
    }
    search_path_count = 0;

    pthread_mutex_unlock(&cache_mutex);
}

int lib_resolve(const char *lib_name, char *out_path, size_t path_sz, char *out_err, size_t err_sz) {
    if (!lib_name || !out_path || path_sz == 0) {
        if (out_err) snprintf(out_err, err_sz, "Invalid arguments");
        return -EINVAL;
    }

    int ret = find_in_path(lib_name, out_path, path_sz);
    if (ret == 0) return 0;

    if (out_err) {
        snprintf(out_err, err_sz, 
            "Library '%s' not found. Searched: LD_LIBRARY_PATH", lib_name);
        for (int i = 0; i < search_path_count; i++) {
            strncat(out_err, ", ", err_sz - strlen(out_err) - 1);
            strncat(out_err, search_paths[i], err_sz - strlen(out_err) - 1);
        }
        for (int i = 0; default_search_paths[i]; i++) {
            strncat(out_err, ", ", err_sz - strlen(out_err) - 1);
            strncat(out_err, default_search_paths[i], err_sz - strlen(out_err) - 1);
        }
    }
    return ret;
}

int lib_resolve_with_build_id(const char *lib_name, const char *build_id, char *out_path, size_t path_sz, char *out_err, size_t err_sz) {
    if (build_id && *build_id) {
        pthread_mutex_lock(&cache_mutex);
        for (int i = 0; i < cache_count; i++) {
            if (cache[i].valid && strcmp(cache[i].lib_name, lib_name) == 0 &&
                strcmp(cache[i].build_id, build_id) == 0) {
                if (access(cache[i].resolved_path, R_OK) == 0) {
                    strncpy(out_path, cache[i].resolved_path, path_sz - 1);
                    out_path[path_sz - 1] = '\0';
                    pthread_mutex_unlock(&cache_mutex);
                    return 0;
                } else {
                    cache[i].valid = 0;
                }
            }
        }
        pthread_mutex_unlock(&cache_mutex);
    }

    int ret = lib_resolve(lib_name, out_path, path_sz, out_err, err_sz);
    if (ret == 0 && build_id && *build_id) {
        lib_cache_add(lib_name, out_path, build_id);
    }
    return ret;
}

int lib_cache_add(const char *lib_name, const char *resolved_path, const char *build_id) {
    if (!lib_name || !resolved_path) return -EINVAL;

    pthread_mutex_lock(&cache_mutex);

    int idx = cache_index_for(lib_name, build_id ? build_id : "");
    
    for (int i = 0; i < MAX_CACHE_ENTRIES; i++) {
        int check = (idx + i) % MAX_CACHE_ENTRIES;
        if (!cache[check].valid || 
            (strcmp(cache[check].lib_name, lib_name) == 0 && 
             (!build_id || !cache[check].build_id[0] || strcmp(cache[check].build_id, build_id) == 0))) {
            strncpy(cache[check].lib_name, lib_name, sizeof(cache[check].lib_name) - 1);
            strncpy(cache[check].resolved_path, resolved_path, sizeof(cache[check].resolved_path) - 1);
            cache[check].lib_name[sizeof(cache[check].lib_name) - 1] = '\0';
            cache[check].resolved_path[sizeof(cache[check].resolved_path) - 1] = '\0';
            cache[check].cached_at = time(NULL);
            cache[check].valid = 1;
            if (build_id && *build_id) {
                strncpy(cache[check].build_id, build_id, sizeof(cache[check].build_id) - 1);
            } else {
                cache[check].build_id[0] = '\0';
            }
            if (cache_count < MAX_CACHE_ENTRIES) cache_count++;
            pthread_mutex_unlock(&cache_mutex);
            return 0;
        }
    }

    pthread_mutex_unlock(&cache_mutex);
    return -ENOSPC;
}

int lib_cache_get(const char *lib_name, const char *build_id, char *out_path, size_t path_sz) {
    if (!lib_name || !out_path) return -EINVAL;

    pthread_mutex_lock(&cache_mutex);

    for (int i = 0; i < cache_count; i++) {
        if (cache[i].valid && strcmp(cache[i].lib_name, lib_name) == 0 &&
            (!build_id || !build_id[0] || strcmp(cache[i].build_id, build_id) == 0)) {
            if (access(cache[i].resolved_path, R_OK) == 0) {
                strncpy(out_path, cache[i].resolved_path, path_sz - 1);
                out_path[path_sz - 1] = '\0';
                pthread_mutex_unlock(&cache_mutex);
                return 0;
            } else {
                cache[i].valid = 0;
            }
        }
    }

    pthread_mutex_unlock(&cache_mutex);
    return -ENOENT;
}

const char *lib_resolver_get_search_paths(void) {
    static char buf[4096];
    buf[0] = '\0';
    
    const char *ld_path = getenv("LD_LIBRARY_PATH");
    if (ld_path) {
        strncat(buf, "LD_LIBRARY_PATH: ", sizeof(buf) - strlen(buf) - 1);
        strncat(buf, ld_path, sizeof(buf) - strlen(buf) - 1);
        strncat(buf, "; ", sizeof(buf) - strlen(buf) - 1);
    }

    for (int i = 0; i < search_path_count; i++) {
        strncat(buf, search_paths[i], sizeof(buf) - strlen(buf) - 1);
        strncat(buf, "; ", sizeof(buf) - strlen(buf) - 1);
    }
    for (int i = 0; default_search_paths[i]; i++) {
        strncat(buf, default_search_paths[i], sizeof(buf) - strlen(buf) - 1);
        strncat(buf, "; ", sizeof(buf) - strlen(buf) - 1);
    }
    
    return buf;
}

void lib_resolver_add_search_path(const char *path) {
    if (!path || !*path) return;
    
    pthread_mutex_lock(&cache_mutex);
    
    for (int i = 0; i < search_path_count; i++) {
        if (strcmp(search_paths[i], path) == 0) {
            pthread_mutex_unlock(&cache_mutex);
            return;
        }
    }
    
    if (search_path_count < MAX_SEARCH_PATHS) {
        search_paths[search_path_count++] = strdup(path);
    }
    
    pthread_mutex_unlock(&cache_mutex);
}