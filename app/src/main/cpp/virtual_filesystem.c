#include "virtual_filesystem.h"

#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static vfs_context_t g_vfs;
static pthread_mutex_t g_vfs_mutex = PTHREAD_MUTEX_INITIALIZER;

int vfs_init(void) {
    pthread_mutex_lock(&g_vfs_mutex);
    memset(&g_vfs, 0, sizeof(g_vfs));
    pthread_mutex_unlock(&g_vfs_mutex);
    return 0;
}

void vfs_cleanup(void) {
    pthread_mutex_lock(&g_vfs_mutex);
    memset(&g_vfs, 0, sizeof(g_vfs));
    pthread_mutex_unlock(&g_vfs_mutex);
}

static void strip_trailing_slash(char *path) {
    size_t len = strlen(path);
    while (len > 1 && path[len - 1] == '/') {
        path[len - 1] = '\0';
        len--;
    }
}

int vfs_add_mapping(const char *virtual_path, const char *real_path) {
    if (!virtual_path || !real_path || !*virtual_path || !*real_path)
        return -EINVAL;

    char vp[VFS_PATH_LEN];
    char rp[VFS_PATH_LEN];
    strncpy(vp, virtual_path, VFS_PATH_LEN - 1);
    vp[VFS_PATH_LEN - 1] = '\0';
    strncpy(rp, real_path, VFS_PATH_LEN - 1);
    rp[VFS_PATH_LEN - 1] = '\0';
    strip_trailing_slash(vp);
    strip_trailing_slash(rp);

    pthread_mutex_lock(&g_vfs_mutex);

    for (int i = 0; i < g_vfs.mapping_count; i++) {
        if (strcmp(g_vfs.mappings[i].virtual_path, vp) == 0) {
            strncpy(g_vfs.mappings[i].real_path, rp, VFS_PATH_LEN - 1);
            g_vfs.mappings[i].real_path[VFS_PATH_LEN - 1] = '\0';
            pthread_mutex_unlock(&g_vfs_mutex);
            return 0;
        }
    }

    if (g_vfs.mapping_count >= VFS_MAX_MAPPINGS) {
        pthread_mutex_unlock(&g_vfs_mutex);
        return -ENOSPC;
    }

    strncpy(g_vfs.mappings[g_vfs.mapping_count].virtual_path, vp, VFS_PATH_LEN - 1);
    g_vfs.mappings[g_vfs.mapping_count].virtual_path[VFS_PATH_LEN - 1] = '\0';
    strncpy(g_vfs.mappings[g_vfs.mapping_count].real_path, rp, VFS_PATH_LEN - 1);
    g_vfs.mappings[g_vfs.mapping_count].real_path[VFS_PATH_LEN - 1] = '\0';
    g_vfs.mapping_count++;

    pthread_mutex_unlock(&g_vfs_mutex);
    return 0;
}

int vfs_remove_mapping(const char *virtual_path) {
    if (!virtual_path) return -EINVAL;
    char vp[VFS_PATH_LEN];
    strncpy(vp, virtual_path, VFS_PATH_LEN - 1);
    vp[VFS_PATH_LEN - 1] = '\0';
    strip_trailing_slash(vp);

    pthread_mutex_lock(&g_vfs_mutex);
    for (int i = 0; i < g_vfs.mapping_count; i++) {
        if (strcmp(g_vfs.mappings[i].virtual_path, vp) == 0) {
            memmove(&g_vfs.mappings[i], &g_vfs.mappings[i + 1],
                    (size_t)(g_vfs.mapping_count - i - 1) * sizeof(vfs_mapping_t));
            g_vfs.mapping_count--;
            pthread_mutex_unlock(&g_vfs_mutex);
            return 0;
        }
    }
    pthread_mutex_unlock(&g_vfs_mutex);
    return -ENOENT;
}

int vfs_resolve_path(const char *path, char *out_path, size_t out_sz) {
    if (!path || !out_path || out_sz == 0) return -EINVAL;

    int applied = 0;
    int best_len = -1;
    int best_idx = -1;

    pthread_mutex_lock(&g_vfs_mutex);
    for (int i = 0; i < g_vfs.mapping_count; i++) {
        size_t vlen = strlen(g_vfs.mappings[i].virtual_path);
        if (strncmp(path, g_vfs.mappings[i].virtual_path, vlen) == 0) {
            char next = path[vlen];
            if (next == '\0' || next == '/') {
                if ((int)vlen > best_len) {
                    best_len = (int)vlen;
                    best_idx = i;
                }
            }
        }
    }

    if (best_idx >= 0) {
        const char *suffix = path + strlen(g_vfs.mappings[best_idx].virtual_path);
        snprintf(out_path, out_sz, "%s%s", g_vfs.mappings[best_idx].real_path, suffix);
        applied = 1;
    } else {
        snprintf(out_path, out_sz, "%s", path);
    }
    pthread_mutex_unlock(&g_vfs_mutex);

    return applied;
}

int vfs_is_virtual_path(const char *path) {
    if (!path) return 0;
    char resolved[VFS_PATH_LEN];
    return vfs_resolve_path(path, resolved, sizeof(resolved)) == 1;
}

int vfs_normalize_path(const char *path, char *out, size_t out_sz) {
    if (!path || !out || out_sz == 0) return -EINVAL;

    char norm[VFS_PATH_LEN];
    size_t nlen = 0;
    const char *p = path;
    int absolute = (path[0] == '/');

    norm[0] = '\0';
    if (absolute && nlen < sizeof(norm) - 1) norm[nlen++] = '/';

    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;

        const char *start = p;
        while (*p && *p != '/') p++;
        size_t comp_len = (size_t)(p - start);

        if (comp_len == 1 && start[0] == '.') {
            continue;
        }
        if (comp_len == 2 && start[0] == '.' && start[1] == '.') {
            if (nlen > 1) {
                nlen--;
                while (nlen > 1 && norm[nlen - 1] != '/') nlen--;
            }
            continue;
        }

        if (nlen > 1 && norm[nlen - 1] != '/') {
            if (nlen < sizeof(norm) - 1) norm[nlen++] = '/';
        }
        if (nlen + comp_len < sizeof(norm) - 1) {
            memcpy(norm + nlen, start, comp_len);
            nlen += comp_len;
        }
    }

    if (nlen == 0) {
        if (nlen < sizeof(norm) - 1) norm[nlen++] = '/';
    }
    norm[nlen] = '\0';

    if (norm[0] != '/') {
        char prep[VFS_PATH_LEN];
        snprintf(prep, sizeof(prep), "/%s", norm);
        strncpy(norm, prep, sizeof(norm) - 1);
        norm[sizeof(norm) - 1] = '\0';
    }

    strncpy(out, norm, out_sz - 1);
    out[out_sz - 1] = '\0';
    return 0;
}

int vfs_validate_path(const char *path, char *err, size_t err_sz) {
    if (!path) {
        if (err) snprintf(err, err_sz, "path is NULL");
        return -EINVAL;
    }
    if (!*path) {
        if (err) snprintf(err, err_sz, "path is empty");
        return -EINVAL;
    }
    if (strchr(path, '\0') != path + strlen(path)) {
        if (err) snprintf(err, err_sz, "path contains NUL byte");
        return -EINVAL;
    }
    if (strlen(path) >= VFS_PATH_LEN) {
        if (err) snprintf(err, err_sz, "path too long");
        return -ENAMETOOLONG;
    }
    return 0;
}

int vfs_is_path_secure(const char *path) {
    return vfs_validate_path(path, NULL, 0) == 0;
}

int vfs_is_under_root(const char *path, const char *root) {
    if (!path || !root) return 0;
    char npath[VFS_PATH_LEN];
    char nroot[VFS_PATH_LEN];
    vfs_normalize_path(path, npath, sizeof(npath));
    vfs_normalize_path(root, nroot, sizeof(nroot));

    size_t rlen = strlen(nroot);
    if (rlen > 1 && nroot[rlen - 1] == '/') rlen--;

    if (strncmp(npath, nroot, rlen) != 0) return 0;
    char next = npath[rlen];
    return (next == '\0' || next == '/');
}

int vfs_check_symlink_escape(const char *path, const char *root,
                             char *symlink_out, size_t symlink_out_sz,
                             char *target_out, size_t target_out_sz) {
    if (!path || !root) return -EINVAL;

    char resolved[VFS_PATH_LEN];
    vfs_resolve_path(path, resolved, sizeof(resolved));

    char npath[VFS_PATH_LEN];
    char nroot[VFS_PATH_LEN];
    vfs_normalize_path(resolved, npath, sizeof(npath));
    vfs_normalize_path(root, nroot, sizeof(nroot));

    char *cursor = malloc(VFS_PATH_LEN);
    if (!cursor) return -ENOMEM;

    char *walk = npath;
    size_t pos = 0;
    cursor[0] = '\0';

    int escape = 0;
    while (*walk) {
        while (*walk == '/') walk++;
        if (!*walk) break;
        char *start = walk;
        while (*walk && *walk != '/') walk++;
        size_t comp_len = (size_t)(walk - start);

        if (pos + comp_len + 2 >= VFS_PATH_LEN) { free(cursor); return -ENAMETOOLONG; }
        cursor[pos] = '/';
        memcpy(cursor + pos + 1, start, comp_len);
        pos += comp_len + 1;
        cursor[pos] = '\0';

        struct stat st;
        if (lstat(cursor, &st) < 0) break;

        if (S_ISLNK(st.st_mode)) {
            char target[VFS_PATH_LEN];
            ssize_t tlen = readlink(cursor, target, sizeof(target) - 1);
            if (tlen < 0) { escape = 1; break; }
            target[tlen] = '\0';

            char full_target[VFS_PATH_LEN];
            if (target[0] == '/') {
                snprintf(full_target, sizeof(full_target), "%s", target);
            } else {
                char parent[VFS_PATH_LEN];
                snprintf(parent, sizeof(parent), "%s", cursor);
                char *slash = strrchr(parent, '/');
                if (slash) *(slash + 1) = '\0';
                else snprintf(parent, sizeof(parent), "/");
                snprintf(full_target, sizeof(full_target), "%s%s", parent, target);
            }

            char norm_target[VFS_PATH_LEN];
            vfs_normalize_path(full_target, norm_target, sizeof(norm_target));

            if (!vfs_is_under_root(norm_target, nroot)) {
                escape = 1;
                if (symlink_out) {
                    strncpy(symlink_out, cursor, symlink_out_sz - 1);
                    symlink_out[symlink_out_sz - 1] = '\0';
                }
                if (target_out) {
                    strncpy(target_out, norm_target, target_out_sz - 1);
                    target_out[target_out_sz - 1] = '\0';
                }
                break;
            }
        }
    }

    free(cursor);
    return escape ? -1 : 0;
}