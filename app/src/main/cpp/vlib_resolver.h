#ifndef VLIB_RESOLVER_H
#define VLIB_RESOLVER_H

#include <stddef.h>
#include "elf_analysis.h"

#ifdef __cplusplus
extern "C" {
#endif

#define VLR_MAX_PATHS   64
#define VLR_MAX_DEPTH   32
#define VLR_MAX_MISSING 256
#define VLR_PATH_LEN    1024

typedef struct {
    char lib_name[EA_MAX_SONAME];
    char resolved_path[VLR_PATH_LEN];
    char required_by[VLR_PATH_LEN];
    int found;
} vlr_resolved_t;

typedef struct {
    char lib_name[EA_MAX_SONAME];
    char required_by[VLR_PATH_LEN];
    char searched_paths[VLR_MAX_MISSING][VLR_PATH_LEN];
    int search_count;
} vlr_missing_t;

typedef struct {
    vlr_resolved_t resolved[EA_MAX_NEEDED * VLR_MAX_DEPTH];
    size_t resolved_count;
    vlr_missing_t missing[VLR_MAX_MISSING];
    size_t missing_count;
    char ld_library_path[8192];
    char preload_path[8192];
    int success;
    char error[2048];
} vlr_result_t;

int vlr_init(void);
void vlr_cleanup(void);

void vlr_add_search_path(const char *path);
void vlr_set_runtime_dir(const char *dir);
void vlr_set_native_lib_dir(const char *dir);

int vlr_resolve(const char *executable_path, vlr_result_t *result);

const char *vlr_get_search_paths_string(void);

#ifdef __cplusplus
}
#endif

#endif
