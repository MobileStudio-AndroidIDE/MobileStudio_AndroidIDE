#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "elf_parse.h"

#ifndef ELFCLASS64
#define ELFCLASS64 2
#endif

#ifndef EM_AARCH64
#define EM_AARCH64 183
#endif

static int is_elf64_aarch64(const Elf64_Ehdr *ehdr) {
    return ehdr->e_ident[EI_MAG0] == ELFMAG0 &&
           ehdr->e_ident[EI_MAG1] == ELFMAG1 &&
           ehdr->e_ident[EI_MAG2] == ELFMAG2 &&
           ehdr->e_ident[EI_MAG3] == ELFMAG3 &&
           ehdr->e_ident[EI_CLASS] == ELFCLASS64 &&
           ehdr->e_ident[EI_DATA] == ELFDATA2LSB &&
           ehdr->e_type == ET_DYN &&
           ehdr->e_machine == EM_AARCH64;
}

int elf_parse_needed(const char *path, char ***out_needed, size_t *out_count) {
    if (!path || !out_needed || !out_count) return -EINVAL;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -errno;

    struct stat st;
    if (fstat(fd, &st) < 0) {
        close(fd);
        return -errno;
    }

    if (st.st_size < sizeof(Elf64_Ehdr)) {
        close(fd);
        return -ENOEXEC;
    }

    void *map = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return -errno;

    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)map;
    if (!is_elf64_aarch64(ehdr)) {
        munmap(map, st.st_size);
        return -ENOEXEC;
    }

    Elf64_Phdr *phdr = (Elf64_Phdr *)((char *)map + ehdr->e_phoff);
    Elf64_Dyn *dynamic = NULL;
    size_t dynsz = 0;

    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dynamic = (Elf64_Dyn *)((char *)map + phdr[i].p_offset);
            dynsz = phdr[i].p_filesz;
            break;
        }
    }

    if (!dynamic) {
        munmap(map, st.st_size);
        *out_needed = NULL;
        *out_count = 0;
        return 0;
    }

    const char *strtab = NULL;
    size_t strtab_sz = 0;
    size_t needed_count = 0;
    Elf64_Dyn *dyn = dynamic;
    while ((char *)dyn < (char *)dynamic + dynsz) {
        if (dyn->d_tag == DT_STRTAB) {
            strtab = (const char *)map + dyn->d_un.d_ptr;
        } else if (dyn->d_tag == DT_STRSZ) {
            strtab_sz = dyn->d_un.d_val;
        } else if (dyn->d_tag == DT_NEEDED) {
            needed_count++;
        }
        dyn++;
    }

    if (!strtab || needed_count == 0) {
        munmap(map, st.st_size);
        *out_needed = NULL;
        *out_count = 0;
        return 0;
    }

    char **needed = calloc(needed_count + 1, sizeof(char *));
    if (!needed) {
        munmap(map, st.st_size);
        return -ENOMEM;
    }

    size_t idx = 0;
    dyn = dynamic;
    while ((char *)dyn < (char *)dynamic + dynsz) {
        if (dyn->d_tag == DT_NEEDED) {
            const char *name = strtab + dyn->d_un.d_val;
            if (name && *name && (size_t)(name - strtab) < strtab_sz) {
                needed[idx] = strdup(name);
                if (!needed[idx]) {
                    for (size_t j = 0; j < idx; j++) free(needed[j]);
                    free(needed);
                    munmap(map, st.st_size);
                    return -ENOMEM;
                }
                idx++;
            }
        }
        dyn++;
    }

    munmap(map, st.st_size);
    *out_needed = needed;
    *out_count = idx;
    return 0;
}

void elf_free_needed(char **needed, size_t count) {
    if (!needed) return;
    for (size_t i = 0; i < count; i++) {
        free(needed[i]);
    }
    free(needed);
}

int elf_get_build_id(const char *path, char *out_buf, size_t bufsz) {
    if (!path || !out_buf || bufsz < 41) return -EINVAL;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -errno;

    struct stat st;
    if (fstat(fd, &st) < 0) {
        close(fd);
        return -errno;
    }

    void *map = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return -errno;

    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)map;
    if (!is_elf64_aarch64(ehdr)) {
        munmap(map, st.st_size);
        return -ENOEXEC;
    }

    Elf64_Phdr *phdr = (Elf64_Phdr *)((char *)map + ehdr->e_phoff);
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdr[i].p_type == PT_NOTE) {
            Elf64_Nhdr *note = (Elf64_Nhdr *)((char *)map + phdr[i].p_offset);
            char *note_end = (char *)note + phdr[i].p_filesz;
            while ((char *)note < note_end) {
                if (note->n_type == NT_GNU_BUILD_ID) {
                    char *desc = (char *)note + sizeof(Elf64_Nhdr) + ((note->n_namesz + 3) & ~3);
                    size_t descsz = note->n_descsz;
                    if (descsz * 2 + 1 <= bufsz) {
                        for (size_t i = 0; i < descsz; i++) {
                            snprintf(out_buf + i * 2, 3, "%02x", (unsigned char)desc[i]);
                        }
                        out_buf[descsz * 2] = '\0';
                        munmap(map, st.st_size);
                        return 0;
                    }
                }
                char *next = (char *)note + sizeof(Elf64_Nhdr) + ((note->n_namesz + 3) & ~3) + ((note->n_descsz + 3) & ~3);
                note = (Elf64_Nhdr *)next;
            }
        }
    }

    munmap(map, st.st_size);
    return -ENOENT;
}