#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IMAGE_NAME="bitchat-android-reproducible-builder:21.0.11"
OUTPUT_DIR="${1:-$PROJECT_ROOT/.reproducible-build/release}"
GRADLE_HOME_NAME="${BITCHAT_CONTAINER_GRADLE_HOME_NAME:-gradle-home-container}"
CONTAINER_LOCAL_PROPERTIES="$SCRIPT_DIR/container-local.properties"

if ! command -v docker >/dev/null 2>&1; then
  echo "error: Docker is required for the canonical container build" >&2
  exit 1
fi
if [ ! -f "$CONTAINER_LOCAL_PROPERTIES" ]; then
  echo "error: missing canonical container local.properties" >&2
  exit 1
fi

if [ "${BITCHAT_ALLOW_DIRTY:-0}" != "1" ] && [ -n "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)" ]; then
  echo "error: reproducible builds require a clean source tree" >&2
  exit 1
fi

source_commit="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
source_date_epoch="$(git -C "$PROJECT_ROOT" log -1 --format=%ct)"

if ! [[ "$GRADLE_HOME_NAME" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "error: BITCHAT_CONTAINER_GRADLE_HOME_NAME must be a simple directory name" >&2
  exit 1
fi

mkdir -p "$PROJECT_ROOT/.reproducible-build"
staging_root="$(mktemp -d "$PROJECT_ROOT/.reproducible-build/source.XXXXXX")"
cleanup() {
  rm -rf -- "$staging_root"
}
trap cleanup EXIT

# Build from the exact committed tree rather than the host checkout. This keeps
# ignored files and Android Studio state out of the canonical build and avoids
# nested bind mounts, which are not portable across Docker runtimes.
git -C "$PROJECT_ROOT" archive --format=tar "$source_commit" |
  tar -xf - -C "$staging_root"

# NDR native libraries are deliberately source-built and ignored by Git. Admit only the four
# expected ABI outputs after the tracked Kotlin binding and pinned submodule have been verified.
NDR_BINDING="app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt"
NDR_SUBMODULE="vendor/nostr-double-ratchet"
NDR_EXPECTED_REVISION="$(tr -d '[:space:]' < "$PROJECT_ROOT/app/src/main/ndr-ffi/SOURCE_REVISION")"
NDR_ACTUAL_REVISION="$(git -C "$PROJECT_ROOT/$NDR_SUBMODULE" rev-parse HEAD)"
if [ "$NDR_ACTUAL_REVISION" != "$NDR_EXPECTED_REVISION" ]; then
  echo "error: NDR source revision does not match the pinned revision" >&2
  exit 1
fi
if [ -n "$(git -C "$PROJECT_ROOT/$NDR_SUBMODULE" status --porcelain --untracked-files=all)" ]; then
  echo "error: NDR source has local changes" >&2
  exit 1
fi
if ! git -C "$PROJECT_ROOT" diff --quiet -- "$NDR_BINDING"; then
  echo "error: generated NDR Kotlin binding is not current" >&2
  exit 1
fi
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  ndr_library="app/src/main/jniLibs/$abi/libndr_ffi.so"
  if [ ! -f "$PROJECT_ROOT/$ndr_library" ]; then
    echo "error: missing source-built NDR library for $abi" >&2
    exit 1
  fi
  mkdir -p "$staging_root/app/src/main/jniLibs/$abi"
  cp "$PROJECT_ROOT/$ndr_library" "$staging_root/$ndr_library"
done

cp "$CONTAINER_LOCAL_PROPERTIES" "$staging_root/local.properties"

gradle_home="$PROJECT_ROOT/.reproducible-build/$GRADLE_HOME_NAME"
mkdir -p "$gradle_home"

docker build \
  --platform linux/amd64 \
  --file "$SCRIPT_DIR/Dockerfile" \
  --tag "$IMAGE_NAME" \
  "$PROJECT_ROOT"

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

docker run \
  --rm \
  --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  --env BITCHAT_ALLOW_DIRTY="${BITCHAT_ALLOW_DIRTY:-0}" \
  --env BITCHAT_GRADLE_USER_HOME=/gradle-home \
  --env BITCHAT_SOURCE_COMMIT="$source_commit" \
  --env BITCHAT_SOURCE_TREE_VERIFIED=1 \
  --env HOME=/tmp/build-home \
  --env SOURCE_DATE_EPOCH="$source_date_epoch" \
  --volume "$staging_root:/workspace" \
  --volume "$gradle_home:/gradle-home" \
  --volume "$OUTPUT_DIR:/output" \
  "$IMAGE_NAME" \
  /output
