#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/SOURCE.env"
source "$REPO_ROOT/tools/arti-build/TOOLCHAIN.env"

# Use the same NDK as NDR. Never fall back to the Maven AAR's native binaries.
JNA_NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/$ANDROID_NDK_VERSION}}"
if [[ ! -f "$JNA_NDK/source.properties" ]] ||
    [[ "$(sed -n 's/^Pkg.Revision *= *//p' "$JNA_NDK/source.properties" | tr -d '\r')" != "$ANDROID_NDK_VERSION" ]]; then
    echo "error: JNA requires Android NDK $ANDROID_NDK_VERSION" >&2
    exit 1
fi
for tool in curl sha256sum tar javac make autoreconf zip unzip strings; do
    command -v "$tool" >/dev/null
done
NDK_TOOLCHAINS=("$JNA_NDK"/toolchains/llvm/prebuilt/*)
[[ ${#NDK_TOOLCHAINS[@]} -eq 1 ]]
LLVM_BIN="${NDK_TOOLCHAINS[0]}/bin"
[[ -x "$LLVM_BIN/llvm-readelf" ]]

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bitchat-jna.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
export LC_ALL=C
export TZ=UTC
export SOURCE_DATE_EPOCH="$NATIVE_SOURCE_DATE_EPOCH"
curl --fail --location --silent --show-error \
    "https://codeload.github.com/java-native-access/jna/tar.gz/$JNA_COMMIT" \
    -o "$BUILD_DIR/source.tar.gz"
echo "$JNA_SOURCE_SHA256  $BUILD_DIR/source.tar.gz" | sha256sum --check --status
mkdir "$BUILD_DIR/source"
# Only source and licenses are admitted; the upstream tree also contains prebuilts.
tar -xzf "$BUILD_DIR/source.tar.gz" -C "$BUILD_DIR/source" --strip-components=1 \
    "jna-$JNA_COMMIT/native" "jna-$JNA_COMMIT/src" "jna-$JNA_COMMIT/common.xml" \
    "jna-$JNA_COMMIT/AL2.0" "jna-$JNA_COMMIT/LGPL2.1"
cd "$BUILD_DIR/source"
source_property() {
    sed -n "s/.*name=\"$1\" value=\"\([^\"]*\)\".*/\1/p" common.xml
}
if [[ "$(source_property jna.major).$(source_property jna.minor).$(source_property jna.revision)" != "$JNA_VERSION" ]] ||
    [[ "$(source_property jni.major).$(source_property jni.minor).$(source_property jni.revision)" != "$JNA_JNI_VERSION" ]] ||
    [[ "$(source_property jni.md5)" != "$JNA_JNI_CHECKSUM" ]]; then
    echo 'error: JNA source version or JNI ABI does not match the pin' >&2
    exit 1
fi

# javac generates JNI declarations from the pinned Java API. These classes are
# not packaged: Gradle uses the verified, same-version Maven JAR for Java code.
mkdir -p build/headers build/classes
find src -name '*.java' -print | LC_ALL=C sort > build/java-sources
javac --release 8 -encoding UTF-8 -h build/headers -d build/classes @build/java-sources
grep -hoE 'Java_com_sun_jna_[A-Za-z0-9_]+' build/headers/*.h | LC_ALL=C sort -u > build/required-exports

# Command-line Make variables propagate into libffi's configure and recursive
# make. Its compiler probes must not inherit JNA's shared-library output flags.
cat > build/Android.mk <<'MAKEFILE'
include Makefile
LDFLAGS = -o $@ -shared -Wl,-soname,libjnidispatch.so -Wl,-Bsymbolic,--build-id=sha1,-z,max-page-size=16384,-z,common-page-size=16384
unexport LDFLAGS LD
MAKEFILE

export AR="$LLVM_BIN/llvm-ar"
export RANLIB="$LLVM_BIN/llvm-ranlib"
export READELF="$LLVM_BIN/llvm-readelf"
for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
    case "$ABI" in
        arm64-v8a) ARCH=aarch64; TARGET=aarch64-linux-android; HOST_TARGET=$TARGET ;;
        armeabi-v7a) ARCH=armv7; TARGET=armv7a-linux-androideabi; HOST_TARGET=arm-linux-androideabi ;;
        x86_64) ARCH=x86-64; TARGET=x86_64-linux-android; HOST_TARGET=$TARGET ;;
        x86) ARCH=x86; TARGET=i686-linux-android; HOST_TARGET=$TARGET ;;
    esac
    export CC="$LLVM_BIN/${TARGET}26-clang"
    export CXX="$LLVM_BIN/${TARGET}26-clang++"
    # Override only the upstream Makefile's obsolete GCC/NDK paths. Preserve its
    # Android definitions and bundled libffi implementation; use relative output
    # paths and a neutral SONAME at link time, never post-process embedded paths.
    if ! make -C native -f ../build/Android.mk -j2 "../build/$ABI/libjnidispatch.so" \
        OS=android ARCH="$ARCH" HOST="$HOST_TARGET" BUILD="../build/$ABI" \
        JAVAH=../build/headers CC="$CC" CPP="$CC -E" \
        AR="$AR" RANLIB="$RANLIB" STRIP="$LLVM_BIN/llvm-strip -x" \
        JNA_JNI_VERSION="$JNA_JNI_VERSION" CHECKSUM="$JNA_JNI_CHECKSUM" \
        FFI_CONFIG="--enable-static --disable-shared --with-pic=yes --disable-docs --host=$HOST_TARGET" \
        CINCLUDES='-I../build/headers -I$(FFI_BUILD)/include' \
        COPT="-O2 -fno-omit-frame-pointer -fno-strict-aliasing -fPIC -ffunction-sections -funwind-tables -fno-short-enums -ffile-prefix-map=$BUILD_DIR=/usr/src/jna" \
        LIBS='-lc -ldl -lm'; then
        echo "error: JNA native build failed ($ABI)" >&2
        if [[ -f "build/$ABI/libffi/config.log" ]]; then
            # Only compiler/linker errors, never Autoconf's host/environment dump.
            JNA_DIAGNOSTIC_BUILD="$BUILD_DIR" JNA_DIAGNOSTIC_NDK="$JNA_NDK" \
                perl -ne 'next unless /^(?:clang(?:-\d+)?|ld\.lld): (?:fatal )?error:/;
                    s/\Q$ENV{JNA_DIAGNOSTIC_BUILD}\E/<build>/g;
                    s/\Q$ENV{JNA_DIAGNOSTIC_NDK}\E/<ndk>/g;
                    print if ++$lines <= 12' "build/$ABI/libffi/config.log" >&2
        fi
        exit 1
    fi

    LIBRARY="build/$ABI/libjnidispatch.so"
    case "$ABI" in
        arm64-v8a) MACHINE=AArch64 ;;
        armeabi-v7a) MACHINE=ARM ;;
        x86_64) MACHINE='Advanced Micro Devices X86-64' ;;
        x86) MACHINE='Intel 80386' ;;
    esac
    "$LLVM_BIN/llvm-readelf" -h "$LIBRARY" | grep -E "Machine: +$MACHINE$" >/dev/null
    "$LLVM_BIN/llvm-readelf" -lW "$LIBRARY" > "build/$ABI/segments"
    awk '$1 == "LOAD" { found = 1; if ($NF != "0x4000") exit 1 } END { if (!found) exit 1 }' \
        "build/$ABI/segments"
    "$LLVM_BIN/llvm-readelf" -dW "$LIBRARY" | grep -F '(SONAME)' | grep -F '[libjnidispatch.so]' >/dev/null
    "$LLVM_BIN/llvm-nm" --dynamic --defined-only "$LIBRARY" | awk '{ print $3 }' | LC_ALL=C sort -u > "build/$ABI/exports"
    comm -23 build/required-exports "build/$ABI/exports" > "build/$ABI/missing-exports"
    if [[ -s "build/$ABI/missing-exports" ]]; then
        echo "error: missing JNA JNI exports ($ABI)" >&2
        exit 1
    fi
done

# Apply the unchanged release path gate before publishing any generated library.
mkdir "$BUILD_DIR/check"
mkdir -p "$BUILD_DIR/package/lib"
for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
    mkdir "$BUILD_DIR/package/lib/$ABI"
    cp "build/$ABI/libjnidispatch.so" "$BUILD_DIR/package/lib/$ABI/"
done
(cd "$BUILD_DIR/package" && zip -q -r "$BUILD_DIR/check/jna.apk" lib)
"$REPO_ROOT/tools/reproducible-builds/verify-no-host-paths.sh" "$BUILD_DIR/check"
for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
    DESTINATION="$REPO_ROOT/app/src/main/jniLibs/$ABI"
    mkdir -p "$DESTINATION"
    cp "build/$ABI/libjnidispatch.so" "$DESTINATION/libjnidispatch.so"
done
echo "Built JNA $JNA_VERSION native $JNA_JNI_VERSION from $JNA_COMMIT"
