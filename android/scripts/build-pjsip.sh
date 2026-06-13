#!/usr/bin/env bash
#
# build-pjsip.sh — build PJSIP/pjsua2 for Android and vendor the outputs into
# the :pjsua2 Gradle module.
#
# There is no official PJSIP AAR, so we build pjproject from source with the
# Android NDK, run SWIG to generate the Java (pjsua2) bindings, and copy:
#   - generated Java  -> pjsua2/src/main/java/org/pjsip/pjsua2/
#   - libpjsua2.so    -> pjsua2/src/main/jniLibs/<abi>/
#
# You only need to run this once (and again when bumping the PJSIP version or
# adding an ABI). Day-to-day app builds just consume the committed outputs.
#
# Requirements:
#   - Android NDK r27+ (16 KB page support, current toolchains)
#   - SWIG 4.x        (sudo apt install swig)
#   - make, gcc, git, python3
#
# Usage:
#   ANDROID_NDK_ROOT=/path/to/ndk ./scripts/build-pjsip.sh
#   ANDROID_NDK_ROOT=/path/to/ndk APP_ABIS="arm64-v8a x86_64" PJ_TAG=2.16 ./scripts/build-pjsip.sh
#
set -euo pipefail

# ---- config ---------------------------------------------------------------
PJ_TAG="${PJ_TAG:-2.16}"                 # pjproject release tag to build
# ABIs to build. arm64-v8a covers modern phones; x86_64 covers the emulator.
APP_ABIS="${APP_ABIS:-arm64-v8a x86_64}"
TARGET_API="${TARGET_API:-31}"           # min API; matches the app's minSdk
OPENSSL_TAG="${OPENSSL_TAG:-openssl-3.0.15}"  # OpenSSL release tag (for TLS)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
PJ_DIR="$SCRIPT_DIR/pjproject"
OPENSSL_SRC="$SCRIPT_DIR/openssl"        # OpenSSL source checkout (reused per ABI)
OPENSSL_OUT="$SCRIPT_DIR/openssl-build"  # per-ABI install prefixes live here
MODULE_JAVA="$ANDROID_DIR/pjsua2/src/main/java"
MODULE_JNILIBS="$ANDROID_DIR/pjsua2/src/main/jniLibs"

# ---- preflight ------------------------------------------------------------
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to your Android NDK (r27+) path}"
command -v swig  >/dev/null || { echo "swig not found (apt install swig)"; exit 1; }
command -v make  >/dev/null || { echo "make not found"; exit 1; }
command -v git   >/dev/null || { echo "git not found"; exit 1; }

# SWIG's java target needs a full JDK (jni.h + javac). Auto-detect if JAVA_HOME
# isn't already a JDK.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-/nonexistent}/bin/javac" ]; then
    if command -v javac >/dev/null; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    elif [ -x "$HOME/jdk17/bin/javac" ]; then
        JAVA_HOME="$HOME/jdk17"
    fi
fi
export JAVA_HOME
[ -x "${JAVA_HOME:-/nonexistent}/bin/javac" ] || { echo "No JDK with javac found; set JAVA_HOME"; exit 1; }
echo ">> Using JAVA_HOME: $JAVA_HOME"

echo ">> Using NDK: $ANDROID_NDK_ROOT"
echo ">> Building pjproject $PJ_TAG for ABIs: $APP_ABIS (API $TARGET_API)"

# The NDK's clang toolchain (OpenSSL's android-* targets find the compilers here).
NDK_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$NDK_BIN" ] || { echo "!! NDK toolchain bin not found at $NDK_BIN"; exit 1; }

# ---- fetch pjproject ------------------------------------------------------
if [ ! -d "$PJ_DIR/.git" ]; then
    git clone --depth 1 --branch "$PJ_TAG" https://github.com/pjsip/pjproject.git "$PJ_DIR"
fi

# ---- fetch OpenSSL (for the TLS transport) --------------------------------
if [ ! -d "$OPENSSL_SRC/.git" ]; then
    git clone --depth 1 --branch "$OPENSSL_TAG" \
        https://github.com/openssl/openssl.git "$OPENSSL_SRC"
fi

# Map an Android ABI to OpenSSL's Configure target name.
openssl_target_for_abi() {
    case "$1" in
        arm64-v8a)   echo android-arm64 ;;
        armeabi-v7a) echo android-arm ;;
        x86_64)      echo android-x86_64 ;;
        x86)         echo android-x86 ;;
        *)           echo "" ;;
    esac
}

# Cross-compile static OpenSSL (libssl.a + libcrypto.a) for one ABI into
# $OPENSSL_OUT/<abi>. Static + -fPIC so it links cleanly into libpjsua2.so and
# we don't have to bundle extra .so files. Skips if already built.
build_openssl() {
    local ABI="$1"
    local PREFIX="$OPENSSL_OUT/$ABI"
    if [ -f "$PREFIX/lib/libssl.a" ] && [ -f "$PREFIX/lib/libcrypto.a" ]; then
        echo ">> OpenSSL for $ABI already built ($PREFIX)"; return 0
    fi
    local TARGET; TARGET="$(openssl_target_for_abi "$ABI")"
    [ -n "$TARGET" ] || { echo "!! no OpenSSL Configure target for ABI $ABI"; exit 1; }
    echo ">> ==== Building OpenSSL ($OPENSSL_TAG) for $ABI -> $PREFIX ===="
    (
        cd "$OPENSSL_SRC"
        make distclean >/dev/null 2>&1 || true
        export ANDROID_NDK_ROOT
        export PATH="$NDK_BIN:$PATH"
        ./Configure "$TARGET" -D__ANDROID_API__="$TARGET_API" -fPIC \
            no-shared no-tests no-ui-console no-engine \
            --prefix="$PREFIX" --openssldir="$PREFIX/ssl"
        make -j"$(nproc)"
        make install_sw
    )
    # Some hosts install to lib64; pjproject's --with-ssl expects <prefix>/lib.
    if [ -d "$PREFIX/lib64" ] && [ ! -d "$PREFIX/lib" ]; then
        ln -sfn lib64 "$PREFIX/lib"
    fi
    [ -f "$PREFIX/lib/libssl.a" ] || { echo "!! OpenSSL build for $ABI produced no libssl.a"; exit 1; }
}

# ---- config_site.h: audio-only Android build ------------------------------
# Keep it simple: no video, audio via OpenSL ES (built into the NDK — no
# third_party/oboe submodule needed). SRTP/TLS stay available.
#
# We include config_site_sample.h FIRST (it sets the Android defaults, which in
# 2.16 enable Oboe), then #undef/#define our overrides so they always win and we
# don't get redefinition warnings.
cat > "$PJ_DIR/pjlib/include/pj/config_site.h" <<'EOF'
/* Generated by build-pjsip.sh — audio-only Android config (OpenSL ES) */
#define PJ_CONFIG_ANDROID 1
#include <pj/config_site_sample.h>

/* ---- overrides (after the sample so they take effect) ---- */
#undef  PJMEDIA_HAS_VIDEO
#define PJMEDIA_HAS_VIDEO 0
/* Use OpenSL ES, not Oboe (Oboe needs the third_party/oboe submodule). */
#undef  PJMEDIA_AUDIO_DEV_HAS_OBOE
#define PJMEDIA_AUDIO_DEV_HAS_OBOE 0
#undef  PJMEDIA_AUDIO_DEV_HAS_OPENSL
#define PJMEDIA_AUDIO_DEV_HAS_OPENSL 1
/* TLS transport ON. We cross-compile OpenSSL for Android below and point
   pjproject at it with --with-ssl, so pj_ssl_sock_* resolve at link time.
   SRTP (SDES) stays enabled at the Android default; together they give the
   app an encrypted client leg (TLS signalling + SRTP media). */
#undef  PJSIP_HAS_TLS_TRANSPORT
#define PJSIP_HAS_TLS_TRANSPORT 1
EOF

# ---- build per ABI --------------------------------------------------------
# pjproject's configure-android maps APP_ABI -> the right NDK toolchain.
mkdir -p "$MODULE_JNILIBS"
for ABI in $APP_ABIS; do
    echo ">> ==== Building $ABI ===="
    # TLS needs OpenSSL for this ABI first; pjproject links it via --with-ssl.
    build_openssl "$ABI"
    OPENSSL_PREFIX="$OPENSSL_OUT/$ABI"
    (
        cd "$PJ_DIR"
        make distclean >/dev/null 2>&1 || true
        APP_PLATFORM="android-$TARGET_API" TARGET_ABI="$ABI" \
            ./configure-android --use-ndk-cflags --with-ssl="$OPENSSL_PREFIX"
        make dep
        make
        # Build ONLY the SWIG Java bindings + libpjsua2.so for this ABI
        # (skip the csharp target that the bare `java` LANG list would also run).
        make -C pjsip-apps/src/swig java
    )

    # libpjsua2.so lands here after the swig build:
    SO_SRC="$PJ_DIR/pjsip-apps/src/swig/java/android/pjsua2/src/main/jniLibs/$ABI/libpjsua2.so"
    if [ ! -f "$SO_SRC" ]; then
        # Fallback to the libs/ layout used by some pjproject versions.
        SO_SRC="$(find "$PJ_DIR/pjsip-apps/src/swig" -name libpjsua2.so -path "*$ABI*" | head -1)"
    fi
    [ -f "$SO_SRC" ] || { echo "!! libpjsua2.so not found for $ABI"; exit 1; }
    mkdir -p "$MODULE_JNILIBS/$ABI"
    cp -v "$SO_SRC" "$MODULE_JNILIBS/$ABI/"

    # libpjsua2.so links dynamically against libc++_shared.so, which is NOT a
    # system library — it must be bundled in the APK or the app crashes at load.
    case "$ABI" in
        arm64-v8a)   TRIPLE=aarch64-linux-android ;;
        armeabi-v7a) TRIPLE=arm-linux-androideabi ;;
        x86_64)      TRIPLE=x86_64-linux-android ;;
        x86)         TRIPLE=i686-linux-android ;;
        *)           TRIPLE="" ;;
    esac
    LIBCXX="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$TRIPLE/libc++_shared.so"
    [ -f "$LIBCXX" ] || { echo "!! libc++_shared.so not found at $LIBCXX"; exit 1; }
    cp -v "$LIBCXX" "$MODULE_JNILIBS/$ABI/"
done

# ---- copy generated Java (ABI-independent) --------------------------------
# The SWIG bindings live in the android *pjsua2 module*, NOT the app sample dir
# (the app dir only holds the demo Activities in package org.pjsip.pjsua2.app).
JAVA_SRC="$PJ_DIR/pjsip-apps/src/swig/java/android/pjsua2/src/main/java/org/pjsip/pjsua2"
if [ ! -d "$JAVA_SRC" ] || [ -z "$(ls "$JAVA_SRC"/*.java 2>/dev/null)" ]; then
    echo "!! generated pjsua2 Java bindings not found at $JAVA_SRC"; exit 1
fi
mkdir -p "$MODULE_JAVA/org/pjsip"
rm -rf "$MODULE_JAVA/org/pjsip/pjsua2"
cp -r "$JAVA_SRC" "$MODULE_JAVA/org/pjsip/pjsua2"

echo
echo ">> Done."
echo "   Java   -> $MODULE_JAVA/org/pjsip/pjsua2"
echo "   Native -> $MODULE_JNILIBS/<abi>/libpjsua2.so"
echo "   Now build the app: ./gradlew :app:assembleDebug"
