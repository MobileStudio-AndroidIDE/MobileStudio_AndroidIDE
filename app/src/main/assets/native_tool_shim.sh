#!/system/bin/sh
# MobileStudio native tool shim — funnel for every native build tool exec
# (AGP ExecAction shell proxy, exec_redirect LD_PRELOAD hook, jdk_tool_proxy).
#
# 1. Redirects AGP's SDK cmake/ninja (toolchain/sdk/*/cmake/*/bin/*) to the
#    Android arm64 ports:
#      cmake -> <toolchain>/cmake/cmake-3.22/bin/cmake
#      ninja -> <toolchain>/arm64-tools/cmake-arm64/bin/ninja
#    Only paths under the device SDK tree are touched; the ported binary must
#    exist and be executable, otherwise the original path is kept as-is.
# 2. Rewrites AGP-generated args carrying the SDK cmake/ninja paths
#    (e.g. -DCMAKE_MAKE_PROGRAM=.../sdk/.../bin/ninja).
# Execs the ported binaries via the Android linker — the same wrapper
# structure as cmake_shim.c / Arm64ToolsDownloader.linker64WrapperFor
# (app-data ELFs are not directly executable on Android).
# MOBILESTUDIO_LINKER64 overrides the linker path (host tests use a stub;
# unset on device -> /system/bin/linker64).
# Everything else passes through untouched: exec "$@".

tool="$1"
if [ -z "$tool" ]; then
  exit 127
fi
shift

PORTED_CMAKE="${MOBILESTUDIO_PORTED_CMAKE:-}"
PORTED_NINJA="${MOBILESTUDIO_PORTED_NINJA:-}"
LINKER64="${MOBILESTUDIO_LINKER64:-/system/bin/linker64}"

case "$tool" in
  */toolchain/sdk/*/cmake/*/bin/*)
    # base = <filesDir>/toolchain ; files = <filesDir>
    base="${tool%%/sdk/*}"
    files="${base%/*}"
    if [ -z "$PORTED_CMAKE" ]; then
      # CmakeRuntimeManager installs <toolchain>/cmake/<version>/bin/cmake;
      # Arm64ToolsDownloader uses <toolchain>/arm64-tools/cmake-arm64/bin/cmake.
      for c in "$files/tools/cmake/bin/cmake" \
               "$base"/cmake/*/bin/cmake \
               "$base/cmake/cmake-3.22/bin/cmake" \
               "$base/arm64-tools/cmake-arm64/bin/cmake" \
               "$base"/arm64-tools/*/bin/cmake; do
        if [ -x "$c" ]; then
          PORTED_CMAKE="$c"
          break
        fi
      done
    fi
    if [ -z "$PORTED_NINJA" ]; then
      # Canonical candidates, most-preferred first:
      #   <filesDir>/tools/ninja/bin/ninja         StudioShellTools arm64 (known-good)
      #   <toolchain>/cmake/<ver>/bin/ninja        CmakeRuntimeManager (reinstall-safe)
      #   <toolchain>/arm64-tools/**/bin/ninja     Arm64ToolsDownloader
      # The SDK cmake tree ships a LINUX x86-64 ninja that Android cannot exec
      # (ld-linux-x86-64.so.2); redirecting it is mandatory.
      for n in "$files/tools/ninja/bin/ninja" \
               "$base"/cmake/*/bin/ninja \
               "$base/arm64-tools/cmake-arm64/bin/ninja" \
               "$base"/arm64-tools/*/bin/ninja; do
        if [ -x "$n" ]; then
          PORTED_NINJA="$n"
          break
        fi
      done
    fi
    ;;
esac

redirected=""
case "$tool" in
  */sdk/*/cmake/*/bin/cmake)
    if [ -n "$PORTED_CMAKE" ]; then
      redirected="$PORTED_CMAKE"
    fi
    ;;
  */sdk/*/cmake/*/bin/ninja)
    if [ -n "$PORTED_NINJA" ]; then
      redirected="$PORTED_NINJA"
    fi
    ;;
esac

if [ -z "$redirected" ]; then
  # Passthrough. Inside the app domain (targetSdk >= 29) execve() of
  # app-data files is denied (SELinux W^X, EACCES — the failure mode this
  # shim historically reported as "Permission denied"). Route shell scripts
  # through /system/bin/sh and ELFs through the Android linker; binaries on
  # system partitions exec directly (unchanged behavior).
  case "$tool" in
    /system/*|/apex/*|/vendor/*)
      exec "$tool" "$@"
      ;;
  esac
  if [ ! -e "$tool" ]; then
    exec "$tool" "$@"   # missing / dangling symlink: keep the usual error
  fi
  headbin=/system/bin/head
  if [ ! -x "$headbin" ]; then headbin=head; fi
  hdr="$("$headbin" -c 2 "$tool" 2>/dev/null)"
  if [ "$hdr" = '#!' ]; then
    shbin=/system/bin/sh
    if [ ! -x "$shbin" ]; then shbin=sh; fi
    exec "$shbin" "$tool" "$@"
  fi
  exec "$LINKER64" "$tool" "$@"
fi

echo "[MobileStudio] native tool redirect: $tool -> $redirected" >&2

# Expose the redirected tool's own lib/ (e.g. libc++_shared.so next to a ported
# ninja) before exec'ing it through the linker.
redirbase="${redirected%/bin/*}"
if [ "$redirbase" != "$redirected" ] && [ -d "$redirbase/lib" ]; then
  LD_LIBRARY_PATH="$redirbase/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export LD_LIBRARY_PATH
fi
# Ported cmake/ninja link against libc++_shared.so from the toolchain LLVM tree.
if [ -d "$base/llvm/lib" ]; then
  LD_LIBRARY_PATH="$base/llvm/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  export LD_LIBRARY_PATH
fi

case "$redirected" in
  */bin/cmake)
    cmakebase="${redirected%/bin/cmake}"
    if [ -d "$cmakebase/lib" ]; then
      LD_LIBRARY_PATH="$cmakebase/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
      export LD_LIBRARY_PATH
    fi
    if [ -d "$cmakebase/share/cmake-3.22" ]; then
      CMAKE_ROOT="$cmakebase/share/cmake-3.22"
      export CMAKE_ROOT
    fi
    ;;
esac

# Rewrite AGP-generated args that carry the SDK cmake/ninja paths.
# Bounded rotate: every original arg is processed exactly once, order kept.
count=$#
i=0
while [ $i -lt $count ]; do
  a="$1"
  shift
  case "$a" in
    -D*=*/sdk/*/cmake/*/bin/ninja)
      if [ -n "$PORTED_NINJA" ]; then
        a="${a%%=*}=$PORTED_NINJA"
      fi
      ;;
    -D*=*/sdk/*/cmake/*/bin/cmake)
      if [ -n "$PORTED_CMAKE" ]; then
        a="${a%%=*}=$PORTED_CMAKE"
      fi
      ;;
    */sdk/*/cmake/*/bin/ninja)
      if [ -n "$PORTED_NINJA" ]; then
        a="$PORTED_NINJA"
      fi
      ;;
    */sdk/*/cmake/*/bin/cmake)
      if [ -n "$PORTED_CMAKE" ]; then
        a="$PORTED_CMAKE"
      fi
      ;;
  esac
  set -- "$@" "$a"
  i=$((i + 1))
done

exec "$LINKER64" "$redirected" "$@"
