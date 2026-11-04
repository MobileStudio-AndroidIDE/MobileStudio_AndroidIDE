#ifndef ELF_ANALYSIS_H
#define ELF_ANALYSIS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EA_MAX_NEEDED    128
#define EA_MAX_PATH      1024
#define EA_MAX_SONAME    256
#define EA_MAX_RPATH     2048
#define EA_BUILD_ID_LEN  41

typedef enum {
    EA_TYPE_NONE     = 0,
    EA_TYPE_EXEC     = 1,
    EA_TYPE_DYN      = 2,
    EA_TYPE_REL      = 3
} ea_type_t;

typedef enum {
    EA_ARCH_UNKNOWN  = 0,
    EA_ARCH_AARCH64  = 183,
    EA_ARCH_X86_64   = 62,
    EA_ARCH_ARM      = 40,
    EA_ARCH_X86      = 3
} ea_arch_t;

typedef struct {
    char soname[EA_MAX_SONAME];
    char rpath[EA_MAX_RPATH];
    char runpath[EA_MAX_RPATH];
    char interp[EA_MAX_PATH];
    char build_id[EA_BUILD_ID_LEN];
    char needed[EA_MAX_NEEDED][EA_MAX_SONAME];
    size_t needed_count;
    ea_type_t type;
    ea_arch_t machine;
    int is_64bit;
    int is_little_endian;
    int valid;
} elf_info_t;

int elf_analyze(const char *path, elf_info_t *out);
int elf_is_arm64(const char *path);
const char *elf_type_str(ea_type_t t);
const char *elf_arch_str(ea_arch_t a);

#ifdef __cplusplus
}
#endif

#endif
