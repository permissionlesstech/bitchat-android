#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
SOURCE_DIR="${NOSTR_DOUBLE_RATCHET_DIR:-${REPO_ROOT}/vendor/nostr-double-ratchet}"
CRATE_DIR="${SOURCE_DIR}/rust/crates/ndr-pairwise-ffi"
CRATE_MANIFEST="${CRATE_DIR}/Cargo.toml"
# Share the pinned compiler and NDK with the repository's existing native builder.
# shellcheck source=tools/arti-build/TOOLCHAIN.env
source "${REPO_ROOT}/tools/arti-build/TOOLCHAIN.env"
export RUSTUP_TOOLCHAIN="${RUST_VERSION}"
CHECK_BINDINGS=false
case "${1:-}" in
    "") ;;
    --check-bindings) CHECK_BINDINGS=true ;;
    *) echo "Usage: $0 [--check-bindings]" >&2; exit 1 ;;
esac
SOURCE_REVISION="$(tr -d '[:space:]' < "${SCRIPT_DIR}/SOURCE_REVISION")"
JNI_DIR="${REPO_ROOT}/app/src/main/jniLibs"
KOTLIN_DIR="${REPO_ROOT}/app/src/main/java/uniffi/ndr_ffi"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/bitchat-ndr-android.XXXXXX")"

cleanup() {
    rm -rf "${BUILD_DIR}"
}
trap cleanup EXIT

if [[ ! -f "${CRATE_MANIFEST}" ]]; then
    echo "nostr-double-ratchet pairwise FFI source not found at ${CRATE_DIR}" >&2
    echo "Run: git submodule update --init --checkout vendor/nostr-double-ratchet" >&2
    exit 1
fi

if [[ "${BITCHAT_SOURCE_TREE_VERIFIED:-0}" == "1" ]]; then
    # build-in-container.sh exported this tree directly from the parent gitlink.
    if [[ "${BITCHAT_NDR_SOURCE_REVISION:-}" != "${SOURCE_REVISION}" ]] ||
        [[ "${SOURCE_DIR}" != "${REPO_ROOT}/vendor/nostr-double-ratchet" ]]; then
        echo "Unverified NDR source archive" >&2
        exit 1
    fi
else
    if ! git -C "${SOURCE_DIR}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        echo "nostr-double-ratchet source must be the pinned Git submodule at ${SOURCE_DIR}" >&2
        exit 1
    fi
    SOURCE_WORKTREE="$(cd "${SOURCE_DIR}" && pwd -P)"
    SOURCE_GIT_ROOT="$(git -C "${SOURCE_DIR}" rev-parse --show-toplevel)"
    if [[ "${SOURCE_GIT_ROOT}" != "${SOURCE_WORKTREE}" ]]; then
        echo "nostr-double-ratchet Git root is ${SOURCE_GIT_ROOT}; expected ${SOURCE_WORKTREE}" >&2
        exit 1
    fi
    ACTUAL_REVISION="$(git -C "${SOURCE_DIR}" rev-parse HEAD)"
    if [[ "${ACTUAL_REVISION}" != "${SOURCE_REVISION}" ]]; then
        echo "nostr-double-ratchet is at ${ACTUAL_REVISION}; expected ${SOURCE_REVISION}" >&2
        exit 1
    fi
    if [[ -n "$(git -C "${SOURCE_DIR}" status --porcelain --untracked-files=all)" ]]; then
        echo "nostr-double-ratchet source has local changes; refusing an unreproducible build" >&2
        exit 1
    fi
fi

command -v cargo >/dev/null
command -v cargo-ndk >/dev/null
if [[ "$(rustc --version | awk '{print $2}')" != "${RUST_VERSION}" ]] ||
    [[ "$(cargo --version | awk '{print $2}')" != "${RUST_VERSION}" ]] ||
    [[ "$(cargo ndk --version | awk '{print $2}')" != "${CARGO_NDK_VERSION}" ]]; then
    echo "NDR requires Rust/Cargo ${RUST_VERSION} and cargo-ndk ${CARGO_NDK_VERSION}" >&2
    exit 1
fi

EXPECTED_NDK_REVISION="${ANDROID_NDK_VERSION}"
NDR_ANDROID_NDK="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"
if [[ -f "${NDR_ANDROID_NDK}/source.properties" ]] &&
    ! grep -q "^Pkg\\.Revision = ${EXPECTED_NDK_REVISION}$" "${NDR_ANDROID_NDK}/source.properties"; then
    NDR_ANDROID_NDK=""
fi
if [[ ! -f "${NDR_ANDROID_NDK}/source.properties" ]]; then
    NDR_ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -d "${NDR_ANDROID_SDK}/ndk/${EXPECTED_NDK_REVISION}" ]]; then
        NDR_ANDROID_NDK="${NDR_ANDROID_SDK}/ndk/${EXPECTED_NDK_REVISION}"
    fi
fi
if [[ ! -f "${NDR_ANDROID_NDK}/source.properties" ]] ||
    [[ "$(sed -n 's/^Pkg.Revision *= *//p' "${NDR_ANDROID_NDK}/source.properties" | tr -d '\r')" != "${EXPECTED_NDK_REVISION}" ]]; then
    echo "Android NDK ${EXPECTED_NDK_REVISION} not found; install it or set ANDROID_NDK_HOME" >&2
    exit 1
fi
export ANDROID_NDK_HOME="${NDR_ANDROID_NDK}"
export NDK_HOME="${NDR_ANDROID_NDK}"
# Compiler wrappers must not alter the canonical native output.
export RUSTC_WRAPPER=""

NDR_CARGO_BIN_DIR="$(cd "$(dirname "$(command -v cargo)")" && pwd -P)"
NDR_CARGO_HOME="${CARGO_HOME:-$(cd "${NDR_CARGO_BIN_DIR}/.." && pwd -P)}"
NDR_RUSTFLAGS=(
    "-C" "link-arg=-Wl,-z,max-page-size=16384"
    "--remap-path-prefix=${REPO_ROOT}=/usr/src/bitchat-android"
    "--remap-path-prefix=${NDR_CARGO_HOME}=/usr/local/cargo"
)
NDR_RUSTFLAGS_STRING="${NDR_RUSTFLAGS[*]}"

mkdir -p "${BUILD_DIR}/jni" "${BUILD_DIR}/bindings"

(
    cd "${CRATE_DIR}"
    RUSTFLAGS="${NDR_RUSTFLAGS_STRING}" cargo ndk \
        -t arm64-v8a \
        -t armeabi-v7a \
        -t x86_64 \
        -t x86 \
        -o "${BUILD_DIR}/jni" \
        build \
        --locked \
        --lib \
        --release
)

(
    cd "${CRATE_DIR}"
    cargo run \
        --locked \
        --manifest-path "${CRATE_MANIFEST}" \
        --features bindgen \
        --bin uniffi-bindgen \
        -- \
        generate \
        --library "${BUILD_DIR}/jni/arm64-v8a/libndr_ffi.so" \
        --language kotlin \
        --config "${SCRIPT_DIR}/uniffi.toml" \
        --out-dir "${BUILD_DIR}/bindings"
)

GENERATED_KOTLIN="${BUILD_DIR}/bindings/uniffi/ndr_ffi/ndr_ffi.kt"
if [[ ! -f "${GENERATED_KOTLIN}" ]]; then
    echo "UniFFI did not generate ${GENERATED_KOTLIN}" >&2
    exit 1
fi

LLVM_READELF_CANDIDATES=(
    "${NDR_ANDROID_NDK}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf
)
if [[ "${#LLVM_READELF_CANDIDATES[@]}" -ne 1 ]] ||
    [[ ! -x "${LLVM_READELF_CANDIDATES[0]}" ]]; then
    echo "Unable to locate llvm-readelf in Android NDK ${EXPECTED_NDK_REVISION}" >&2
    exit 1
fi
LLVM_READELF="${LLVM_READELF_CANDIDATES[0]}"
for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
    LIBRARY="${BUILD_DIR}/jni/${ABI}/libndr_ffi.so"
    LOAD_ALIGNMENTS="$(
        "${LLVM_READELF}" -lW "${LIBRARY}" |
            awk '$1 == "LOAD" { print $NF }'
    )"
    if [[ -z "${LOAD_ALIGNMENTS}" ]]; then
        echo "No ELF LOAD segments found in ${LIBRARY}" >&2
        exit 1
    fi
    while IFS= read -r ALIGNMENT; do
        if (( ALIGNMENT < 0x4000 )); then
            echo "${LIBRARY} has non-16KiB LOAD alignment ${ALIGNMENT}" >&2
            exit 1
        fi
    done <<< "${LOAD_ALIGNMENTS}"
done

perl -pi -e 's/[ \t]+$//' "${GENERATED_KOTLIN}"
perl -0777 -pi -e 's/\s+\z/\n/' "${GENERATED_KOTLIN}"
if ${CHECK_BINDINGS}; then
    if ! cmp -s "${GENERATED_KOTLIN}" "${KOTLIN_DIR}/ndr_ffi.kt"; then
        echo "Generated NDR Kotlin binding is stale; regenerate it before building" >&2
        exit 1
    fi
else
    mkdir -p "${KOTLIN_DIR}"
    cp "${GENERATED_KOTLIN}" "${KOTLIN_DIR}/ndr_ffi.kt"
fi

for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
    mkdir -p "${JNI_DIR}/${ABI}"
    cp "${BUILD_DIR}/jni/${ABI}/libndr_ffi.so" "${JNI_DIR}/${ABI}/libndr_ffi.so"
done

echo "Built Android pairwise NDR FFI from nostr-double-ratchet ${SOURCE_REVISION}"
