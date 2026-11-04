#include "elf_analysis.h"

#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef EM_AARCH64
#define EM_AARCH64 183
#endif
#ifndef EM_X86_64
#define EM_X86_64 62
#endif
#ifndef EM_ARM
#define EM_ARM 40
#endif
#ifndef EM_386
#define EM_386 3
#endif

static const char *ea_type_strings[] = {
    "NONE", "ET_EXEC", "ET_DYN", "ET_REL"
};

static const char *ea_arch_strings[] = {
    "unknown", "AArch64", "x86_64", "ARM", "x86"
};

const char *elf_type_str(ea_type_t t) {
    if (t >= 0 && t <= EA_TYPE_REL) return ea_type_strings[t];
    return "unknown";
}

const char *elf_arch_str(ea_arch_t a) {
    switch (a) {
        case EA_ARCH_AARCH64: return "AArch64 (arm64-v8a)";
        case EA_ARCH_X86_64:  return "x86_64";
        case EA_ARCH_ARM:     return "ARM (32-bit)";
        case EA_ARCH_X86:     return "x86 (32-bit)";
        default:              return "unknown";
    }
}

int elf_analyze(const char *path, elf_info_t *out) {
    if (!path || !out) return -EINVAL;
    memset(out, 0, sizeof(elf_info_t));

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -errno;

    struct stat st;
    if (fstat(fd, &st) < 0) {
        close(fd);
        return -errno;
    }

    if (st.st_size < (off_t)sizeof(Elf64_Ehdr)) {
        close(fd);
        return -ENOEXEC;
    }

    void *map = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return -errno;

    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)map;

    if (ehdr->e_ident[EI_MAG0] != ELFMAG0 ||
        ehdr->e_ident[EI_MAG1] != ELFMAG1 ||
        ehdr->e_ident[EI_MAG2] != ELFMAG2 ||
        ehdr->e_ident[EI_MAG3] != ELFMAG3) {
        munmap(map, (size_t)st.st_size);
        return -ENOEXEC;
    }

    out->is_64bit = (ehdr->e_ident[EI_CLASS] == ELFCLASS64);
    out->is_little_endian = (ehdr->e_ident[EI_DATA] == ELFDATA2LSB);

    switch (ehdr->e_machine) {
        case EM_AARCH64: out->machine = EA_ARCH_AARCH64; break;
        case EM_X86_64:  out->machine = EA_ARCH_X86_64;  break;
        case EM_ARM:     out->machine = EA_ARCH_ARM;     break;
        case EM_386:     out->machine = EA_ARCH_X86;     break;
        default:         out->machine = EA_ARCH_UNKNOWN;  break;
    }

    switch (ehdr->e_type) {
        case ET_EXEC: out->type = EA_TYPE_EXEC; break;
        case ET_DYN:  out->type = EA_TYPE_DYN;  break;
        case ET_REL:  out->type = EA_TYPE_REL;  break;
        default:      out->type = EA_TYPE_NONE;  break;
    }

    Elf64_Phdr *phdr = (Elf64_Phdr *)((char *)map + ehdr->e_phoff);
    uint16_t phnum = ehdr->e_phnum;

    Elf64_Dyn *dynamic = NULL;
    size_t dynsz = 0;

    for (int i = 0; i < phnum; i++) {
        if (phdr[i].p_type == PT_INTERP) {
            const char *interp = (const char *)map + phdr[i].p_offset;
            size_t interp_len = phdr[i].p_filesz;
            if (interp_len > 0 && interp_len < EA_MAX_PATH) {
                memcpy(out->interp, interp, interp_len);
                if (out->interp[interp_len - 1] == '\0') {
                    /* already null-terminated */
                } else {
                    out->interp[interp_len] = '\0';
                }
            }
        } else if (phdr[i].p_type == PT_DYNAMIC) {
            dynamic = (Elf64_Dyn *)((char *)map + phdr[i].p_offset);
            dynsz = phdr[i].p_filesz;
        } else if (phdr[i].p_type == PT_NOTE) {
            Elf64_Nhdr *note = (Elf64_Nhdr *)((char *)map + phdr[i].p_offset);
            char *note_end = (char *)note + phdr[i].p_filesz;
            while ((char *)note + sizeof(Elf64_Nhdr) <= note_end) {
                if (note->n_type == NT_GNU_BUILD_ID) {
                    char *name = (char *)note + sizeof(Elf64_Nhdr);
                    size_t name_sz = (note->n_namesz + 3) & ~3;
                    char *desc = name + name_sz;
                    size_t descsz = note->n_descsz;
                    if (descsz > 0 && descsz * 2 + 1 <= EA_BUILD_ID_LEN) {
                        for (size_t j = 0; j < descsz; j++) {
                            snprintf(out->build_id + j * 2, 3, "%02x",
                                     (unsigned char)desc[j]);
                        }
                    }
                    break;
                }
                char *next = (char *)note + sizeof(Elf64_Nhdr)
                    + ((note->n_namesz + 3) & ~3)
                    + ((note->n_descsz + 3) & ~3);
                note = (Elf64_Nhdr *)next;
            }
        }
    }

    if (!dynamic) {
        out->valid = 1;
        munmap(map, (size_t)st.st_size);
        return 0;
    }

    const char *strtab = NULL;
    size_t strtab_sz = 0;
    size_t needed_count = 0;

    Elf64_Dyn *dyn = dynamic;
    while ((char *)dyn + sizeof(Elf64_Dyn) <= (char *)dynamic + dynsz) {
        if (dyn->d_tag == DT_STRTAB) {
            strtab = (const char *)map + dyn->d_un.d_ptr;
        } else if (dyn->d_tag == DT_STRSZ) {
            strtab_sz = dyn->d_un.d_val;
        } else if (dyn->d_tag == DT_NEEDED) {
            needed_count++;
        } else if (dyn->d_tag == DT_SONAME) {
            if (strtab) {
                const char *soname = strtab + dyn->d_un.d_val;
                if (soname && *soname) {
                    strncpy(out->soname, soname, EA_MAX_SONAME - 1);
                }
            }
        } else if (dyn->d_tag == DT_RPATH) {
            if (strtab) {
                const char *rpath = strtab + dyn->d_un.d_val;
                if (rpath && *rpath) {
                    strncpy(out->rpath, rpath, EA_MAX_RPATH - 1);
                }
            }
        } else if (dyn->d_tag == DT_RUNPATH) {
            if (strtab) {
                const char *runpath = strtab + dyn->d_un.d_val;
                if (runpath && *runpath) {
                    strncpy(out->runpath, runpath, EA_MAX_RPATH - 1);
                }
            }
        }
        dyn++;
    }

    if (strtab && needed_count > 0 && needed_count <= EA_MAX_NEEDED) {
        dyn = dynamic;
        size_t idx = 0;
        while ((char *)dyn + sizeof(Elf64_Dyn) <= (char *)dynamic + dynsz && idx < needed_count) {
            if (dyn->d_tag == DT_NEEDED) {
                const char *name = strtab + dyn->d_un.d_val;
                if (name && *name) {
                    strncpy(out->needed[idx], name, EA_MAX_SONAME - 1);
                    out->needed[idx][EA_MAX_SONAME - 1] = '\0';
                    idx++;
                }
            }
            dyn++;
        }
        out->needed_count = idx;
    }

    out->valid = 1;
    munmap(map, (size_t)st.st_size);
    return 0;
}

int elf_is_arm64(const char *path) {
    elf_info_t info;
    int ret = elf_analyze(path, &info);
    if (ret < 0) return 0;
    return info.is_64bit && info.machine == EA_ARCH_AARCH64;
}
