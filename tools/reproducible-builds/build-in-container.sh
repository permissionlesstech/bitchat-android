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
mkdir -p "$PROJECT_ROOT/.reproducible-build/$GRADLE_HOME_NAME"

docker build \
  --platform linux/amd64 \
  --file "$SCRIPT_DIR/Dockerfile" \
  --tag "$IMAGE_NAME" \
  "$PROJECT_ROOT"

container_output="$OUTPUT_DIR"
case "$OUTPUT_DIR" in
  "$PROJECT_ROOT"/*)
    container_output="/workspace/${OUTPUT_DIR#"$PROJECT_ROOT"/}"
    output_mount=()
    ;;
  *)
    mkdir -p "$OUTPUT_DIR"
    OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
    container_output="/output"
    output_mount=(--volume "$OUTPUT_DIR:/output")
    ;;
esac

docker run \
  --rm \
  --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  --env BITCHAT_ALLOW_DIRTY="${BITCHAT_ALLOW_DIRTY:-0}" \
  --env BITCHAT_GRADLE_USER_HOME="/workspace/.reproducible-build/$GRADLE_HOME_NAME" \
  --env BITCHAT_SOURCE_COMMIT="$source_commit" \
  --env BITCHAT_SOURCE_TREE_VERIFIED=1 \
  --env HOME=/workspace/.reproducible-build \
  --env SOURCE_DATE_EPOCH="$source_date_epoch" \
  --volume "$PROJECT_ROOT:/workspace" \
  --mount "type=bind,source=$CONTAINER_LOCAL_PROPERTIES,target=/workspace/local.properties,readonly" \
  "${output_mount[@]}" \
  "$IMAGE_NAME" \
  "$container_output"
