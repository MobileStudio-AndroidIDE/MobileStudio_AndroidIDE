#!/usr/bin/env bash
# Builds line-counter into a installable .msext using the host toolchain.
#
#   javac (extension API stub + android.jar)  ->  d8  ->  classes.dex  ->  .msext
#
# This mirrors, step for step, what Extensions -> Build .msext does on the device.
# The device finds javac/d8/android.jar from the toolchain the user already
# installed for Android projects; nothing extra is downloaded.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
android_jar="$(ls -d "$sdk"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
d8="$(ls "$sdk"/build-tools/*/d8 2>/dev/null | sort -V | tail -1 || true)"

if [ -z "$android_jar" ]; then
    echo "error: android.jar not found under $sdk/platforms (install an SDK platform)" >&2
    exit 1
fi
if [ -z "$d8" ]; then
    echo "error: d8 not found under $sdk/build-tools (install build-tools)" >&2
    exit 1
fi

api_jar="$repo/app/src/main/assets/extensions/extension-api.jar"
if [ ! -f "$api_jar" ]; then
    echo "error: extension API stub missing at $api_jar" >&2
    exit 1
fi

out="$here/build"
rm -rf "$out"
mkdir -p "$out/classes" "$out/dex"

# shellcheck disable=SC2046   # paths are controlled and contain no spaces
javac -nowarn -proc:none -encoding UTF-8 \
    -source 8 -target 8 \
    -d "$out/classes" \
    -classpath "$api_jar:$android_jar" \
    $(find "$here/src" -name '*.java')

"$d8" --release \
    --lib "$android_jar" \
    --classpath "$api_jar" \
    --output "$out/dex" \
    $(find "$out/classes" -name '*.class')

# .msext is a plain zip: extension.json + README.md are mandatory members, and
# classes.dex carries the code. The name matches packMsext's Release convention.
id="$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' "$here/extension.json" | head -1)"
version="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$here/extension.json" | head -1)"
name="$id-v$version.msext"
built="$out/$name"

( cd "$out/dex" && zip -q -X "$built" classes.dex )
( cd "$here" && zip -q -X -g "$built" extension.json README.md )

# Committed so the sample can be installed without building anything first.
mv "$built" "$repo/examples/$name"

echo "built: examples/$name"
sha256sum "$repo/examples/$name"
