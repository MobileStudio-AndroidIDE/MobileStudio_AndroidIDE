#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="4.7.2-stable"
APK="Godot_v${VERSION}_android_editor.apk"
URL="https://github.com/godotengine/godot/releases/download/${VERSION}/${APK}"
SHA256="7a69ae5645f1a97ac020e6bed3b4e9ce2451b13127f5727f5fb37f6991bf8b3c"
CACHE="${GODOT_CACHE_DIR:-$ROOT/godot/.cache}"
OUT="$ROOT/third_party/godot/native/arm64-v8a"

mkdir -p "$CACHE" "$OUT"

if [ ! -f "$CACHE/$APK" ]; then
    echo "Downloading $APK"
    curl -L --fail --retry 3 -o "$CACHE/$APK.part" "$URL"
    mv "$CACHE/$APK.part" "$CACHE/$APK"
fi

echo "$SHA256  $CACHE/$APK" | sha256sum -c -

TMP="$CACHE/godot-libs-$$"
mkdir -p "$TMP"
unzip -o -j "$CACHE/$APK" 'lib/arm64-v8a/*.so' -d "$TMP"
cp "$TMP"/*.so "$OUT"/
rm -rf "$TMP"
ls -lh "$OUT"
