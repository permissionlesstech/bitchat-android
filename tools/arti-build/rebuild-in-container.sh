#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IMAGE_NAME="bitchat-android-arti-builder:1.9.0"
MODE="${1:-}"

if ! command -v docker >/dev/null 2>&1; then
  echo "error: Docker is required to rebuild the native Arti libraries" >&2
  exit 1
fi

mkdir -p "$PROJECT_ROOT/.reproducible-build/cargo-home"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/TOOLCHAIN.env"
if ! [[ "$NATIVE_SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]]; then
  echo "error: NATIVE_SOURCE_DATE_EPOCH must be an integer" >&2
  exit 1
fi

docker build \
  --platform linux/amd64 \
  --file "$SCRIPT_DIR/Dockerfile" \
  --tag "$IMAGE_NAME" \
  "$PROJECT_ROOT"

docker run \
  --rm \
  --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  --env CARGO_HOME=/workspace/.reproducible-build/cargo-home \
  --env HOME=/workspace/.reproducible-build \
  --env SOURCE_DATE_EPOCH="$NATIVE_SOURCE_DATE_EPOCH" \
  --volume "$PROJECT_ROOT:/workspace" \
  "$IMAGE_NAME" \
  ${MODE:+"$MODE"}

if [ "$MODE" = "--update-lockfile" ]; then
  echo "Cargo.lock updated in the pinned native container; review it before rebuilding."
  exit 0
fi
"$SCRIPT_DIR/verify-checksums.sh"
