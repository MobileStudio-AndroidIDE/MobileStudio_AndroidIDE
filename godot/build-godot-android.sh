#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="4.7.2-stable"
ARCHIVE="godot-${VERSION}.tar.xz"
URL="https://github.com/godotengine/godot/releases/download/${VERSION}/${ARCHIVE}"
SHA256="a18ce0ccec3ecc40b0dd6c4f5132ca934e9fb7c2979717940ff32aee1eb35481"
CACHE="${GODOT_CACHE_DIR:-$ROOT/godot/.cache}"
WORK="${GODOT_WORK_DIR:-$ROOT/godot/.work}"
SRC="$WORK/godot-${VERSION}"
PATCH="$ROOT/godot/patches/0001-mobilestudio-editor-bridge.patch"
OUT="$ROOT/third_party/godot/native/arm64-v8a"

mkdir -p "$CACHE" "$WORK" "$OUT"

if [ ! -f "$CACHE/$ARCHIVE" ]; then
    echo "Downloading $ARCHIVE"
    curl -L --fail --retry 3 -o "$CACHE/$ARCHIVE.part" "$URL"
    mv "$CACHE/$ARCHIVE.part" "$CACHE/$ARCHIVE"
fi

echo "$SHA256  $CACHE/$ARCHIVE" | sha256sum -c -

if [ ! -d "$SRC" ]; then
    echo "Extracting $ARCHIVE"
    tar -xJf "$CACHE/$ARCHIVE" -C "$WORK"
fi

if patch -p1 -d "$SRC" --dry-run < "$PATCH" >/dev/null 2>&1; then
    patch -p1 -d "$SRC" < "$PATCH"
elif patch -p1 -d "$SRC" --dry-run -R < "$PATCH" >/dev/null 2>&1; then
    echo "Patch already applied"
else
    echo "Patch does not apply cleanly" >&2
    exit 1
fi

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "Set ANDROID_NDK_ROOT to an installed NDK (29.0.14206865 recommended)" >&2
    exit 1
fi
export ANDROID_NDK_ROOT="$NDK"
export ANDROID_NDK_HOME="$NDK"

SCONS="${SCONS:-scons}"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

echo "Building Godot editor for Android arm64"
"$SCONS" -C "$SRC" -j"$JOBS" \
    platform=android \
    arch=arm64 \
    target=editor \
    production=yes \
    debug_symbols=no \
    android_api_level=24

BUILD_OUT="$SRC/platform/android/java/lib/libs/tools/release/arm64-v8a"
if [ ! -d "$BUILD_OUT" ]; then
    BUILD_OUT="$(find "$SRC/platform/android/java/lib/libs" -type d -name arm64-v8a -print -quit)"
fi
if [ -z "$BUILD_OUT" ] || [ ! -d "$BUILD_OUT" ]; then
    echo "Godot Android native output not found" >&2
    exit 1
fi

cp "$BUILD_OUT"/*.so "$OUT"/
ls -lh "$OUT"
