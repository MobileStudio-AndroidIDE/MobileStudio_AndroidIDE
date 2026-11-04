#ifndef LINKER_RESOLVER_H
#define LINKER_RESOLVER_H

#include <stddef.h>
#include "elf_analysis.h"
#include "vlib_resolver.h"

#ifdef __cplusplus
extern "C" {
#endif

#define LR_PATH_LEN 1024

/*
 * Linker (PT_INTERP) resolution for the universal ARM64 executor.
 *
 * Android does not ship a traditional libc loader beyond the bionic dynamic
 * linker (/system/bin/linker64). This module determines which interpreter an
 * ELF needs, locates a usable system linker as a fallback, and validates the
 * completeness of a dependency resolution result.
 */
typedef struct {
    char interp[EA_MAX_PATH];        /* PT_INTERP from the ELF, if any   */
    char linker64_path[LR_PATH_LEN]; /* fallback 64-bit system linker    */
    char linker32_path[LR_PATH_LEN]; /* fallback 32-bit system linker    */
    int is_64bit;
    int is_static;                    /* no PT_INTERP and no DT_NEEDED    */
    int has_interp;
} lr_result_t;

/* Populate lr_result_t from a parsed ELF header. */
int lr_resolve_interp(const elf_info_t *info, lr_result_t *out);

/* Locate an accessible system linker for the given ELF class.
 * Searches /system/bin and /apex/com.android.runtime/bin. Returns 0 on
 * success, -ENOENT when no usable linker is found. */
int lr_get_system_linker(int is_64bit, char *out, size_t out_sz);

/* Build the final LD_LIBRARY_PATH by merging (in order): the executable's
 * directory, the executable's RPATH/RUNPATH, and the dirs discovered by
 * vlr_resolve(). `exe_info` supplies RPATH/RUNPATH; `vlr` supplies the
 * recursive resolution dirs. Writes into ld_out (ld_sz bytes). */
int lr_build_ld_library_path(const char *executable_path,
                             const elf_info_t *exe_info,
                             const vlr_result_t *vlr,
                             char *ld_out, size_t ld_sz);

/* Validate a dependency resolution result: reports missing libraries and
 * writes a human-readable summary. Returns 0 when complete, 1 when libraries
 * are missing, negative on error. */
int lr_validate_dependencies(const vlr_result_t *vlr, char *err, size_t err_sz);

#ifdef __cplusplus
}
#endif

#endif