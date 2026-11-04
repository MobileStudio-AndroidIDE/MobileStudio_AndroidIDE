#ifndef ELF_PARSE_H
#define ELF_PARSE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

int elf_parse_needed(const char *path, char ***out_needed, size_t *out_count);
void elf_free_needed(char **needed, size_t count);
int elf_get_build_id(const char *path, char *out_buf, size_t bufsz);

#ifdef __cplusplus
}
#endif

#endif