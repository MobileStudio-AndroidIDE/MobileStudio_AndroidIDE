#!/bin/bash
# MobileStudio JDK Android ARM64 Universal Build Script
# Usage: bash jdk-build-all.sh [version]
# Example: bash jdk-build-all.sh 11
#          bash jdk-build-all.sh all

set -e

BASEDIR="/mnt/d/MobileStudio-build"
NDK="$BASEDIR/android-ndk-r27d"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TARGET="aarch64-linux-android"
API="29"

# Boot JDKs
BOOT_JDK21="$BASEDIR/boot-jdk"
BOOT_JDK17="$BASEDIR/boot-jdk17"

JDK_VERSION="${1:-all}"

# ============================================================
# Helper: select boot JDK for a given target JDK version
# ============================================================
get_boot_jdk() {
    local ver=$1
    if [ "$ver" -le 17 ]; then
        echo "$BOOT_JDK17"
    else
        echo "$BOOT_JDK21"
    fi
}

# ============================================================
# Helper: get source and build directories
# ============================================================
get_dirs() {
    local ver=$1
    SRC="$BASEDIR/openjdk${ver}"
    BUILDDIR="$BASEDIR/build-jdk${ver}"
}

# ============================================================
# Clone a JDK source tree (shallow, specific tag)
# ============================================================
clone_jdk() {
    local ver=$1
    local tag=$2
    local dir="$BASEDIR/openjdk${ver}"

    if [ -d "$dir/.git" ]; then
        echo "✅ openjdk${ver} source already exists, skipping clone"
        return 0
    fi

    echo "📦 Cloning OpenJDK ${ver} (tag: ${tag})..."
    rm -rf "$dir"
    
    # Try multiple upstream repos
    local repos=(
        "https://github.com/openjdk/jdk${ver}.git"
        "https://github.com/openjdk/jdk${ver}u.git"
        "https://github.com/openjdk/jdk.git"
    )
    
    local cloned=false
    for repo in "${repos[@]}"; do
        echo "   Trying $repo..."
        if git clone --depth 1 --branch "$tag" "$repo" "$dir" 2>/dev/null; then
            cloned=true
            break
        fi
    done
    
    if [ "$cloned" = false ]; then
        # Fallback: clone then checkout tag
        echo "   Shallow tag clone failed, trying full shallow clone..."
        git clone --depth 1 "https://github.com/openjdk/jdk${ver}.git" "$dir" 2>/dev/null || \
        git clone --depth 1 "https://github.com/openjdk/jdk${ver}u.git" "$dir" 2>/dev/null || \
        { echo "❌ Failed to clone OpenJDK ${ver}"; return 1; }
        cd "$dir"
        git fetch --depth 1 origin "tag ${tag}" 2>/dev/null && git checkout "$tag" 2>/dev/null || true
        cd -
    fi
    
    echo "✅ OpenJDK ${ver} source ready"
}

# ============================================================
# Apply Bionic patches to source tree
# ============================================================
apply_patches() {
    local ver=$1
    local src="$BASEDIR/openjdk${ver}"
    
    echo "🔧 Applying Bionic patches to OpenJDK ${ver}..."
    
    # -------------------------------------------------------
    # A. platform.m4 - Bionic detection
    # -------------------------------------------------------
    local PLAT_M4="$src/make/autoconf/platform.m4"
    if [ -f "$PLAT_M4" ] && ! grep -q "VAR_LIBC=bionic" "$PLAT_M4" 2>/dev/null; then
        # LIBC detection - add bionic case
        sed -i '/\*linux\*-musl\*)/{n;s/.*/      VAR_LIBC=musl\n      ;;\n    *linux*-android*|*android*)\n      VAR_LIBC=bionic\n      ;;/}' "$PLAT_M4" 2>/dev/null || true
        
        # More robust: find VAR_LIBC=musl and add bionic after it
        if ! grep -q "VAR_LIBC=bionic" "$PLAT_M4" 2>/dev/null; then
            # Add after the musl case in PLATFORM_EXTRACT_VARS_FROM_LIBC
            sed -i '/VAR_LIBC=musl/a\    *linux*-android*|*android*)\n      VAR_LIBC=bionic\n      ;;' "$PLAT_M4" 2>/dev/null || true
        fi
        
        # ABI detection
        if ! grep -q "VAR_ABI=bionic" "$PLAT_M4" 2>/dev/null; then
            sed -i '/VAR_ABI=musl/a\    *linux*-android*|*android*)\n      VAR_ABI=bionic\n      ;;' "$PLAT_M4" 2>/dev/null || true
        fi
        
        echo "   ✅ platform.m4 patched"
    else
        echo "   ⏭️  platform.m4 already patched or not found"
    fi
    
    # -------------------------------------------------------
    # B. flags-cflags.m4 - BIONIC_LIBC define
    # -------------------------------------------------------
    local CFLAGS_M4="$src/make/autoconf/flags-cflags.m4"
    if [ -f "$CFLAGS_M4" ] && ! grep -q "BIONIC_LIBC" "$CFLAGS_M4" 2>/dev/null; then
        # Find the OS_CFLAGS section and add BIONIC define
        sed -i '/OS_CFLAGS.*\$OS_CFLAGS/i\  if test "x$OPENJDK_TARGET_LIBC" = xbionic; then\n    OS_CFLAGS="$OS_CFLAGS -DBIONIC_LIBC"\n  fi' "$CFLAGS_M4" 2>/dev/null || true
        echo "   ✅ flags-cflags.m4 patched"
    else
        echo "   ⏭️  flags-cflags.m4 already patched or not found"
    fi
    
    # -------------------------------------------------------
    # C. libraries.m4 - Disable desktop libs, libdl, libpthread, librt for Bionic
    # -------------------------------------------------------
    local LIBS_M4="$src/make/autoconf/libraries.m4"
    if [ -f "$LIBS_M4" ] && ! grep -q "OPENJDK_TARGET_LIBC.*bionic" "$LIBS_M4" 2>/dev/null; then
        # X11
        sed -i '/NEEDS_LIB_X11/a\  elif test "x$OPENJDK_TARGET_LIBC" = xbionic; then\n    # No X11 support on Android\n    NEEDS_LIB_X11=false' "$LIBS_M4" 2>/dev/null || true
        # fontconfig
        sed -i '/NEEDS_LIB_FONTCONFIG/a\  elif test "x$OPENJDK_TARGET_LIBC" = xbionic; then\n    # No fontconfig on Android\n    NEEDS_LIB_FONTCONFIG=false' "$LIBS_M4" 2>/dev/null || true
        # CUPS
        sed -i '/NEEDS_LIB_CUPS/a\  elif test "x$OPENJDK_TARGET_LIBC" = xbionic; then\n    # No CUPS on Android\n    NEEDS_LIB_CUPS=false' "$LIBS_M4" 2>/dev/null || true
        # ALSA
        sed -i 's/test "x$OPENJDK_TARGET_OS" = xlinux && test "x$OPENJDK_TARGET_LIBC" != xmusl/test "x$OPENJDK_TARGET_OS" = xlinux \&\& test "x$OPENJDK_TARGET_LIBC" != xmusl \&\& test "x$OPENJDK_TARGET_LIBC" != xbionic/' "$LIBS_M4" 2>/dev/null || true
        # libdl - part of Bionic libc
        sed -i '/BASIC_JVM_LIBS.*LIBDL/{n;s/.*/    if test "x$OPENJDK_TARGET_OS" = xlinux \&\& test "x$OPENJDK_TARGET_LIBC" != xbionic; then\n      BASIC_JVM_LIBS="$BASIC_JVM_LIBS $LIBDL"\n    fi/}' "$LIBS_M4" 2>/dev/null || true
        echo "   ✅ libraries.m4 patched"
    else
        echo "   ⏭️  libraries.m4 already patched or not found"
    fi
    
    # -------------------------------------------------------
    # D. Makefile lpthread guards - filter -lpthread for Bionic
    # -------------------------------------------------------
    # Add SET_LIBS_IF_NOT_BIONIC macro to Utils.gmk
    local UTILS_GMK="$src/make/common/Utils.gmk"
    if [ -f "$UTILS_GMK" ] && ! grep -q "SET_LIBS_IF_NOT_BIONIC" "$UTILS_GMK" 2>/dev/null; then
        echo '' >> "$UTILS_GMK"
        echo '# Filter libraries not available on Bionic (Android)' >> "$UTILS_GMK"
        echo 'SET_LIBS_IF_NOT_BIONIC = $(if $(filter bionic, $(OPENJDK_TARGET_LIBC)),,$1)' >> "$UTILS_GMK"
        echo "   ✅ Utils.gmk patched"
    fi
    
    # Patch -lpthread in various Lib.gmk files
    for gmk in \
        "$src/make/common/modules/LauncherCommon.gmk" \
        "$src/make/modules/java.base/lib/CoreLibraries.gmk" \
        "$src/make/modules/java.base/Lib.gmk" \
        "$src/make/modules/jdk.sctp/Lib.gmk" \
        "$src/make/modules/jdk.jdwp.agent/Lib.gmk"; do
        if [ -f "$gmk" ] && grep -q "\-lpthread" "$gmk" 2>/dev/null && ! grep -q "SET_LIBS_IF_NOT_BIONIC" "$gmk" 2>/dev/null; then
            sed -i 's/-lpthread/$(call SET_LIBS_IF_NOT_BIONIC, -lpthread)/g' "$gmk" 2>/dev/null || true
            echo "   ✅ $(basename "$gmk") patched"
        fi
    done
    
    # -------------------------------------------------------
    # E. Module skip guards for AWT, SA, jpackage
    # -------------------------------------------------------
    local DESKTOP_LIB="$src/make/modules/java.desktop/Lib.gmk"
    if [ -f "$DESKTOP_LIB" ] && ! grep -q "OPENJDK_TARGET_LIBC.*bionic" "$DESKTOP_LIB" 2>/dev/null; then
        # Wrap the include in a bionic guard
        sed -i '1i\ifeq ($(OPENJDK_TARGET_LIBC),bionic)\n# Android (Bionic) does not have X11/ALSA; skip AWT/2D native libraries.\nelse' "$DESKTOP_LIB" 2>/dev/null || true
        echo 'endif' >> "$DESKTOP_LIB"
        echo "   ✅ java.desktop/Lib.gmk patched"
    fi
    
    local SA_LIB="$src/make/modules/jdk.hotspot.agent/Lib.gmk"
    if [ -f "$SA_LIB" ] && ! grep -q "OPENJDK_TARGET_LIBC.*bionic" "$SA_LIB" 2>/dev/null; then
        sed -i '1i\ifeq ($(OPENJDK_TARGET_LIBC),bionic)\n# Android (Bionic) does not have required Linux structures for SA; skip.\nelse' "$SA_LIB" 2>/dev/null || true
        echo 'endif' >> "$SA_LIB"
        echo "   ✅ jdk.hotspot.agent/Lib.gmk patched"
    fi
    
    local JPACKAGE_LIB="$src/make/modules/jdk.jpackage/Lib.gmk"
    if [ -f "$JPACKAGE_LIB" ] && ! grep -q "OPENJDK_TARGET_LIBC.*bionic" "$JPACKAGE_LIB" 2>/dev/null; then
        sed -i '1i\ifeq ($(OPENJDK_TARGET_LIBC),bionic)\n# Android (Bionic) does not support jpackage native launchers; skip.\nelse' "$JPACKAGE_LIB" 2>/dev/null || true
        echo 'endif' >> "$JPACKAGE_LIB"
        echo "   ✅ jdk.jpackage/Lib.gmk patched"
    fi
    
    # -------------------------------------------------------
    # F. HotSpot os_linux.cpp - Bionic stubs
    # -------------------------------------------------------
    local OS_LINUX="$src/src/hotspot/os/linux/os_linux.cpp"
    if [ -f "$OS_LINUX" ] && ! grep -q "__ANDROID__.*Bionic" "$OS_LINUX" 2>/dev/null; then
        # Add Bionic-specific includes and stubs near the top (after existing includes)
        sed -i '/#include.*os_linux.hpp/a\
\
#ifdef __ANDROID__\
/* Bionic compatibility stubs */\
#include <android/log.h>\
#endif' "$OS_LINUX" 2>/dev/null || true
        
        # dlvsym stub - extend musl guard to include Android
        sed -i 's/#if defined(MUSL_LIBC)/#if defined(MUSL_LIBC) || (defined(__ANDROID__) \&\& __ANDROID_API__ < 24)/' "$OS_LINUX" 2>/dev/null || true
        
        # malloc_trim / mallinfo - exclude from Android
        sed -i 's/#if defined(__GLIBC__)/#if defined(__GLIBC__) \&\& !defined(__ANDROID__)/g' "$OS_LINUX" 2>/dev/null || true
        
        echo "   ✅ os_linux.cpp patched"
    else
        echo "   ⏭️  os_linux.cpp already patched or not found"
    fi
    
    # -------------------------------------------------------
    # G. java_props_md.c - CODESET fallback for Android
    # -------------------------------------------------------
    local PROPS_MD="$src/src/java.base/unix/native/libjava/java_props_md.c"
    if [ -f "$PROPS_MD" ] && ! grep -q "__ANDROID__" "$PROPS_MD" 2>/dev/null; then
        # Add Android CODESET fallback
        sed -i '/#define CODESET _NL_CTYPE_CODESET_NAME/i\#ifdef __ANDROID__\n/* Bionic may not expose _NL_CTYPE_CODESET_NAME */\n#define CODESET 0x0e\n#else' "$PROPS_MD" 2>/dev/null || true
        sed -i '/#define CODESET _NL_CTYPE_CODESET_NAME/a\#endif' "$PROPS_MD" 2>/dev/null || true
        
        # Add UTF-8 fallback for nl_langinfo
        sed -i '/p = nl_langinfo(CODESET);/i\#ifdef __ANDROID__\n            /* Bionic lacks nl_langinfo for API < 28 */\n            p = "UTF-8";\n#else' "$PROPS_MD" 2>/dev/null || true
        sed -i '/p = nl_langinfo(CODESET);/a\#endif' "$PROPS_MD" 2>/dev/null || true
        
        echo "   ✅ java_props_md.c patched"
    else
        echo "   ⏭️  java_props_md.c already patched or not found"
    fi
    
    # -------------------------------------------------------
    # H. net_util_md.h - sockaddr_in include
    # -------------------------------------------------------
    local NET_MD="$src/src/java.base/unix/native/libnet/net_util_md.h"
    if [ -f "$NET_MD" ] && ! grep -q "netinet/in.h" "$NET_MD" 2>/dev/null; then
        sed -i '1i\#ifdef __ANDROID__\n#include <netinet/in.h>\n#endif' "$NET_MD" 2>/dev/null || true
        echo "   ✅ net_util_md.h patched"
    else
        echo "   ⏭️  net_util_md.h already patched or not found"
    fi
    
    # -------------------------------------------------------
    # I. elfFile.hpp - ELF_ST_TYPE guard
    # -------------------------------------------------------
    local ELF_HPP="$src/src/hotspot/share/utilities/elfFile.hpp"
    if [ -f "$ELF_HPP" ] && ! grep -q "ELF_ST_TYPE" "$ELF_HPP" 2>/dev/null; then
        sed -i '1i\#ifndef ELF_ST_TYPE\n#define ELF_ST_TYPE ELF64_ST_TYPE\n#endif' "$ELF_HPP" 2>/dev/null || true
        echo "   ✅ elfFile.hpp patched"
    else
        echo "   ⏭️  elfFile.hpp already patched or not found"
    fi
    
    # -------------------------------------------------------
    # J. toolchain.m4 - clang-first lookup
    # -------------------------------------------------------
    local TOOL_M4="$src/make/autoconf/toolchain.m4"
    if [ -f "$TOOL_M4" ] && ! grep -q "llvm-objcopy" "$TOOL_M4" 2>/dev/null; then
        # Add LLVM tools to the search list
        sed -i '/PROG_LIST.*objcopy/a\    llvm-$PROG_NAME' "$TOOL_M4" 2>/dev/null || true
        sed -i '/PROG_LIST.*strip/a\    llvm-$PROG_NAME' "$TOOL_M4" 2>/dev/null || true
        sed -i '/PROG_LIST.*nm/a\    llvm-$PROG_NAME' "$TOOL_M4" 2>/dev/null || true
        echo "   ✅ toolchain.m4 patched"
    else
        echo "   ⏭️  toolchain.m4 already patched or not found"
    fi
    
    # -------------------------------------------------------
    # K. flags-ldflags.m4 - Remove --undefined-version
    # -------------------------------------------------------
    local LDFLAGS_M4="$src/make/autoconf/flags-ldflags.m4"
    if [ -f "$LDFLAGS_M4" ]; then
        sed -i 's/--undefined-version//g' "$LDFLAGS_M4" 2>/dev/null || true
        echo "   ✅ flags-ldflags.m4 patched"
    fi
    
    # -------------------------------------------------------
    # L. JvmMapfile.gmk - --extern-only for nm
    # -------------------------------------------------------
    local JMAPFILE="$src/make/hotspot/lib/JvmMapfile.gmk"
    if [ -f "$JMAPFILE" ] && ! grep -q "extern-only" "$JMAPFILE" 2>/dev/null; then
        sed -i 's/$(NM) -f/$(NM) --extern-only -f/g' "$JMAPFILE" 2>/dev/null || true
        echo "   ✅ JvmMapfile.gmk patched"
    fi
    
    # -------------------------------------------------------
    # M. os_posix.cpp - fork+exec fallback for API < 28
    # -------------------------------------------------------
    local OS_POSIX="$src/src/hotspot/os/posix/os_posix.cpp"
    if [ -f "$OS_POSIX" ] && ! grep -q "__ANDROID__" "$OS_POSIX" 2>/dev/null; then
        sed -i '/posix_spawn/i\#if defined(__ANDROID__) && __ANDROID_API__ < 28\n  /* Bionic posix_spawn may not work; use fork+exec */\n  pid_t pid = fork();\n  if (pid == 0) { execvp(argv[0], const_cast<char**>(argv)); _exit(1); }\n  return pid;\n#else' "$OS_POSIX" 2>/dev/null || true
        echo "   ✅ os_posix.cpp patched"
    fi
    
    echo "✅ All patches applied to OpenJDK ${ver}"
}

# ============================================================
# Generate configure + build
# ============================================================
configure_and_build() {
    local ver=$1
    get_dirs "$ver"
    local boot_jdk=$(get_boot_jdk "$ver")
    
    echo ""
    echo "================================================================"
    echo "  Building OpenJDK ${ver} for Android ARM64 (API ${API})"
    echo "================================================================"
    echo "  Source: $SRC"
    echo "  Build:  $BUILDDIR"
    echo "  Boot:   $boot_jdk"
    echo "  NDK:    $NDK"
    echo "================================================================"
    echo ""
    
    # Generate configure if needed
    if [ ! -f "$SRC/configure" ]; then
        echo "📝 Generating configure script..."
        cd "$SRC"
        bash make/autoconf/autogen.sh 2>&1 | tail -5
        cd -
    fi
    
    # Clean build dir
    echo "🧹 Cleaning build directory..."
    mkdir -p "$BUILDDIR"
    cd "$BUILDDIR"
    
    # Common configure flags
    local COMMON_FLAGS="
      --build=x86_64-linux-gnu
      --with-conf-name=linux-aarch64-android
      --openjdk-target=$TARGET
      --with-sysroot=$TOOLCHAIN/sysroot
      --with-toolchain-type=clang
      --with-boot-jdk=$boot_jdk
      --with-jvm-variants=server
      --with-native-debug-symbols=internal
      --enable-unlimited-crypto
      --enable-dtrace=no
      --disable-warnings-as-errors
      --with-debug-level=release
      --with-target-bits=64
      --with-extra-ldflags='-Wl,--as-needed'
      --with-extra-cflags='-ftls-model=global-dynamic -fPIC -march=armv8-a'
      --with-extra-cxxflags='-ftls-model=global-dynamic -fPIC -march=armv8-a'
      --with-libjpeg=bundled
      --with-giflib=bundled
      --with-libpng=bundled
      --with-zlib=bundled
      --with-lcms=bundled
      --with-freetype=bundled
      --disable-manpages
      --with-java-max-stack-size=256
      --with-vendor-name=MobileStudio
      --with-vendor-url=https://github.com/MobileStudio-AndroidIDE
      --with-vendor-bug-url=https://github.com/MobileStudio-AndroidIDE/issues
      --with-vendor-vm-bug-url=https://github.com/MobileStudio-AndroidIDE/issues
    "
    
    # Version-specific flags
    local VERSION_FLAGS=""
    case $ver in
        11)
            VERSION_FLAGS="
              --with-version-opt=android-api29-universal
              --disable-cds
              --disable-jshell
              --disable-jconsole
              --disable-jdb
              --disable-jdeps
              --disable-jhat
              --disable-jinfo
              --disable-jmap
              --disable-jps
              --disable-jstack
              --disable-jstat
              --disable-jstatd
              --disable-jhsdb
            "
            ;;
        17)
            VERSION_FLAGS="
              --with-jvm-features=zgc,jfr,-cds
              --with-version-opt=android-api29-universal
              --disable-cds
              --disable-jshell
              --disable-jconsole
              --disable-jdb
              --disable-jdeps
              --disable-jhat
              --disable-jinfo
              --disable-jmap
              --disable-jps
              --disable-jstack
              --disable-jstat
              --disable-jstatd
              --disable-jhsdb
            "
            ;;
        21)
            VERSION_FLAGS="
              --with-jvm-features=zgc,jfr
              --with-version-opt=android-api29-universal
              --disable-cds
            "
            ;;
        23|24|25)
            VERSION_FLAGS="
              --with-jvm-features=zgc,jfr
              --with-version-opt=android-api29-universal
              --disable-cds
              --disable-jshell
              --enable-ccache
            "
            ;;
    esac
    
    echo "⚙️ Running configure..."
    eval bash "$SRC/configure" $COMMON_FLAGS $VERSION_FLAGS 2>&1 | tail -20
    
    if [ $? -ne 0 ]; then
        echo "❌ Configure failed for JDK ${ver}"
        return 1
    fi
    
    echo ""
    echo "🔨 Building with make -j5 images..."
    make -j5 images 2>&1 | tee build.log | tail -30
    
    if [ $? -ne 0 ]; then
        echo "❌ Build failed for JDK ${ver}"
        return 1
    fi
    
    # Verify
    local JAVA_BIN="$BUILDDIR/linux-aarch64-android/images/jdk/bin/java"
    if [ -x "$JAVA_BIN" ]; then
        echo ""
        echo "✅ JDK ${ver} build SUCCESSFUL!"
        echo "   Binary: $JAVA_BIN"
        
        # Package
        local TARBALL="$BASEDIR/jdk${ver}-android-api29.tar.gz"
        tar czf "$TARBALL" -C "$BUILDDIR/linux-aarch64-android/images" jdk/
        echo "   Package: $TARBALL ($(du -h "$TARBALL" | cut -f1))"
    else
        echo "❌ JDK binary not found after build"
        return 1
    fi
}

# ============================================================
# Main
# ============================================================
case "$JDK_VERSION" in
    11)
        clone_jdk 11 "jdk-11.0.25+9"
        apply_patches 11
        configure_and_build 11
        ;;
    17)
        apply_patches 17
        configure_and_build 17
        ;;
    21)
        apply_patches 21
        configure_and_build 21
        ;;
    23)
        clone_jdk 23 "jdk-23.0.2-ga"
        apply_patches 23
        configure_and_build 23
        ;;
    24)
        clone_jdk 24 "jdk-24.0.2-ga"
        apply_patches 24
        configure_and_build 24
        ;;
    25)
        apply_patches 25
        configure_and_build 25
        ;;
    all)
        for v in 11 23 24 25; do
            clone_jdk $v
        done
        for v in 11 23 24 25; do
            apply_patches $v
            configure_and_build $v
        done
        ;;
    *)
        echo "Usage: $0 {11|17|21|23|24|25|all}"
        exit 1
        ;;
esac

echo ""
echo "================================================================"
echo "  All requested JDK builds completed!"
echo "================================================================"
