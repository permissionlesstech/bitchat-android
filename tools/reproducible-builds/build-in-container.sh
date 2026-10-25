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

# Export the exact gitlink recorded by the committed parent. Never admit ignored
# host libraries: build the exported source with the existing pinned native toolchain.
NDR_SUBMODULE="vendor/nostr-double-ratchet"
NDR_EXPECTED_REVISION="$(tr -d '[:space:]' < "$staging_root/app/src/main/ndr-ffi/SOURCE_REVISION")"
NDR_GITLINK_REVISION="$(git -C "$PROJECT_ROOT" ls-tree "$source_commit" -- "$NDR_SUBMODULE" | awk '$1 == "160000" { print $3 }')"
if [ "$NDR_GITLINK_REVISION" != "$NDR_EXPECTED_REVISION" ]; then
  echo "error: NDR source revision does not match the committed gitlink" >&2
  exit 1
fi
mkdir -p "$staging_root/$NDR_SUBMODULE"
git -C "$PROJECT_ROOT/$NDR_SUBMODULE" archive --format=tar "$NDR_GITLINK_REVISION" |
  tar -xf - -C "$staging_root/$NDR_SUBMODULE"

NATIVE_IMAGE_NAME="bitchat-android-native-builder:ndr"
docker build \
  --platform linux/amd64 \
  --file "$staging_root/tools/arti-build/Dockerfile" \
  --tag "$NATIVE_IMAGE_NAME" \
  "$staging_root"
docker run \
  --rm \
  --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  --volume "$staging_root:/workspace" \
  --entrypoint /bin/bash \
  "$NATIVE_IMAGE_NAME" \
  tools/jna-build/build-android.sh
docker run \
  --rm \
  --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  --env BITCHAT_SOURCE_TREE_VERIFIED=1 \
  --env BITCHAT_NDR_SOURCE_REVISION="$NDR_GITLINK_REVISION" \
  --env CARGO_TARGET_DIR=/tmp/ndr-target \
  --env CARGO_HOME=/tmp/ndr-cargo \
  --env HOME=/tmp/ndr-home \
  --env SOURCE_DATE_EPOCH="$source_date_epoch" \
  --volume "$staging_root:/workspace" \
  --entrypoint /bin/bash \
  "$NATIVE_IMAGE_NAME" \
  app/src/main/ndr-ffi/build-android.sh --check-bindings

cp "$staging_root/tools/reproducible-builds/container-local.properties" "$staging_root/local.properties"

gradle_home="$PROJECT_ROOT/.reproducible-build/$GRADLE_HOME_NAME"
mkdir -p "$gradle_home"

docker build \
  --platform linux/amd64 \
  --file "$staging_root/tools/reproducible-builds/Dockerfile" \
  --tag "$IMAGE_NAME" \
  "$staging_root"

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
