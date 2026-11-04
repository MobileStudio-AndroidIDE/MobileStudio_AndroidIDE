#ifndef VIRTUAL_FILESYSTEM_H
#define VIRTUAL_FILESYSTEM_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VFS_MAX_MAPPINGS 128
#define VFS_PATH_LEN     1024

/*
 * A path mapping maps a virtual prefix (as visible to the executed program)
 * onto a real absolute prefix (as visible to the Android host filesystem).
 *
 * Example:
 *   vfs_add_mapping("/sdcard", "/storage/emulated/0");
 *   vfs_resolve_path("/sdcard/foo", out) -> "/storage/emulated/0/foo"
 */
typedef struct {
    char virtual_path[VFS_PATH_LEN];
    char real_path[VFS_PATH_LEN];
} vfs_mapping_t;

typedef struct {
    vfs_mapping_t mappings[VFS_MAX_MAPPINGS];
    int mapping_count;
} vfs_context_t;

/* Global VFS context lifecycle. */
int  vfs_init(void);
void vfs_cleanup(void);

/* Mapping management (thread-safe). */
int vfs_add_mapping(const char *virtual_path, const char *real_path);
int vfs_remove_mapping(const char *virtual_path);

/* Translate a path via the longest matching virtual prefix.
 * Returns 1 if a mapping was applied, 0 if the path was copied unchanged,
 * negative on error. out_path is always populated with a usable string. */
int vfs_resolve_path(const char *path, char *out_path, size_t out_sz);
int vfs_is_virtual_path(const char *path);

/* Path security helpers. vfs_normalize_path collapses ".", "..", duplicate
 * slashes and a trailing slash into a canonical absolute form. */
int vfs_normalize_path(const char *path, char *out, size_t out_sz);

/* Validates a path is safe for exec/file access: non-empty, absolute,
 * no NUL bytes. Returns 0 on success, -EINVAL on failure with a message. */
int vfs_validate_path(const char *path, char *err, size_t err_sz);
int vfs_is_path_secure(const char *path);

/* Ensures a normalized path stays under `root` (no ".." traversal escape).
 * Returns 0 when contained, -1 when it escapes the root. */
int vfs_is_under_root(const char *path, const char *root);

/* Walks each path component under `root`, resolving symlinks, and verifies
 * none of them escape the root. Returns 0 when safe, -1 when a symlink
 * escape is detected (writes the offending component into `symlink_out`
 * when supplied, and the resolved offender path into `target_out`). */
int vfs_check_symlink_escape(const char *path, const char *root,
                             char *symlink_out, size_t symlink_out_sz,
                             char *target_out, size_t target_out_sz);

#ifdef __cplusplus
}
#endif

#endif