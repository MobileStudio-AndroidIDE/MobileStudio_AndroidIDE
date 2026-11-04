#ifndef LIB_RESOLVER_H
#define LIB_RESOLVER_H

#include <stddef.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    char lib_name[64];
    char resolved_path[512];
    char build_id[41];
    time_t cached_at;
    int valid;
} lib_cache_entry_t;

int lib_resolver_init(const char *cache_dir);
void lib_resolver_cleanup(void);

int lib_resolve(const char *lib_name, char *out_path, size_t path_sz, char *out_err, size_t err_sz);
int lib_resolve_with_build_id(const char *lib_name, const char *build_id, char *out_path, size_t path_sz, char *out_err, size_t err_sz);

int lib_cache_add(const char *lib_name, const char *resolved_path, const char *build_id);
int lib_cache_get(const char *lib_name, const char *build_id, char *out_path, size_t path_sz);

const char *lib_resolver_get_search_paths(void);
void lib_resolver_add_search_path(const char *path);

#ifdef __cplusplus
}
#endif

#endif