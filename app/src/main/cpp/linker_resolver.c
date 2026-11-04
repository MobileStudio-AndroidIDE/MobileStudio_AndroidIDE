#include "linker_resolver.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int path_exists(const char *path) {
    return access(path, F_OK) == 0;
}

int lr_get_system_linker(int is_64bit, char *out, size_t out_sz) {
    if (!out || out_sz == 0) return -EINVAL;

    const char *names_64[] = { "linker64", NULL };
    const char *names_32[] = { "linker", NULL };
    const char **names = is_64bit ? names_64 : names_32;

    const char *bases[] = {
        "/system/bin",
        "/apex/com.android.runtime/bin",
        NULL
    };

    for (int i = 0; bases[i]; i++) {
        for (int j = 0; names[j]; j++) {
            char candidate[LR_PATH_LEN];
            snprintf(candidate, sizeof(candidate), "%s/%s", bases[i], names[j]);
            if (path_exists(candidate)) {
                strncpy(out, candidate, out_sz - 1);
                out[out_sz - 1] = '\0';
                return 0;
            }
        }
    }

    return -ENOENT;
}

int lr_resolve_interp(const elf_info_t *info, lr_result_t *out) {
    if (!info || !out) return -EINVAL;
    memset(out, 0, sizeof(lr_result_t));

    out->is_64bit = info->is_64bit;
    out->has_interp = info->interp[0] != '\0';
    if (out->has_interp) {
        strncpy(out->interp, info->interp, EA_MAX_PATH - 1);
        out->interp[EA_MAX_PATH - 1] = '\0';
    }

    /* A statically linked binary has no interpreter and no external deps. */
    out->is_static = (!out->has_interp && info->needed_count == 0);

    lr_get_system_linker(1, out->linker64_path, sizeof(out->linker64_path));
    lr_get_system_linker(0, out->linker32_path, sizeof(out->linker32_path));

    return 0;
}

static void append_dir(char *buf, size_t buf_sz, size_t *pos, const char *dir) {
    if (!dir || !*dir) return;
    size_t len = strlen(dir);
    while (len > 1 && dir[len - 1] == '/') len--;
    if (len == 0) return;

    if (*pos + len + 2 >= buf_sz) return;
    if (*pos > 0) buf[(*pos)++] = ':';
    memcpy(buf + *pos, dir, len);
    *pos += len;
    buf[*pos] = '\0';
}

int lr_build_ld_library_path(const char *executable_path,
                             const elf_info_t *exe_info,
                             const vlr_result_t *vlr,
                             char *ld_out, size_t ld_sz) {
    if (!executable_path || !ld_out || ld_sz == 0) return -EINVAL;
    if (ld_sz > 0) ld_out[0] = '\0';

    size_t pos = 0;

    /* 1. The executable's own directory. */
    char exe_dir[LR_PATH_LEN];
    strncpy(exe_dir, executable_path, sizeof(exe_dir) - 1);
    exe_dir[sizeof(exe_dir) - 1] = '\0';
    char *slash = strrchr(exe_dir, '/');
    if (slash) {
        *slash = '\0';
        append_dir(ld_out, ld_sz, &pos, exe_dir);
    }

    /* 2. RPATH / RUNPATH. */
    if (exe_info) {
        append_dir(ld_out, ld_sz, &pos, exe_info->runpath[0] ? exe_info->runpath : NULL);
        append_dir(ld_out, ld_sz, &pos, exe_info->rpath[0] ? exe_info->rpath : NULL);
    }

    /* 3. Directories discovered by recursive resolution. */
    if (vlr && vlr->ld_library_path[0]) {
        char *dup = strdup(vlr->ld_library_path);
        if (dup) {
            char *save = NULL;
            char *dir = strtok_r(dup, ":", &save);
            while (dir) {
                append_dir(ld_out, ld_sz, &pos, dir);
                dir = strtok_r(NULL, ":", &save);
            }
            free(dup);
        }
    }

    return 0;
}

int lr_validate_dependencies(const vlr_result_t *vlr, char *err, size_t err_sz) {
    if (!vlr) return -EINVAL;

    if (vlr->missing_count == 0) {
        if (err) snprintf(err, err_sz, "All %zu dependencies resolved", vlr->resolved_count);
        return 0;
    }

    size_t pos = 0;
    if (err && err_sz > 0) {
        pos += snprintf(err + pos, err_sz - pos,
                        "%zu missing librar%s:",
                        vlr->missing_count,
                        vlr->missing_count == 1 ? "y" : "ies");
        for (size_t i = 0; i < vlr->missing_count && pos < err_sz; i++) {
            pos += snprintf(err + pos, err_sz - pos,
                            "\n  - %s (required by %s)",
                            vlr->missing[i].lib_name,
                            vlr->missing[i].required_by);
        }
    }
    return 1;
}