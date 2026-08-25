#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TAG="${1:?usage: verify-github-release.sh TAG [--no-rebuild]}"
MODE="${2:-}"
REPOSITORY="${BITCHAT_GITHUB_REPOSITORY:-permissionlesstech/bitchat-android}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

if ! [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "error: release tag must look like vX.Y.Z" >&2
  exit 1
fi
if [ -n "$MODE" ] && [ "$MODE" != "--no-rebuild" ]; then
  echo "error: unknown option: $MODE" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "error: gh is required" >&2
  exit 1
fi
if command -v sha256sum >/dev/null 2>&1; then
  SHA256=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SHA256=(shasum -a 256)
else
  echo "error: sha256sum or shasum is required" >&2
  exit 1
fi

DOWNLOAD_DIR="$TEMP_DIR/github"
mkdir -p "$DOWNLOAD_DIR"
gh release download "$TAG" \
  --repo "$REPOSITORY" \
  --dir "$DOWNLOAD_DIR" \
  --pattern 'BITCHAT_BUILDINFO.json' \
  --pattern 'BITCHAT_SHA256SUMS' \
  --pattern 'BITCHAT_SHA256SUMS.unsigned' \
  --pattern 'bitchat-android-*.apk' \
  --pattern 'bitchat-android-*.aab'

attested_artifacts=(
  BITCHAT_BUILDINFO.json
  BITCHAT_SHA256SUMS.unsigned
  bitchat-android-arm64-unsigned.apk
  bitchat-android-armv7-unsigned.apk
  bitchat-android-release-unsigned.aab
  bitchat-android-universal-unsigned.apk
  bitchat-android-wear-release-unsigned.aab
  bitchat-android-wear-unsigned.apk
  bitchat-android-x86-unsigned.apk
  bitchat-android-x86_64-unsigned.apk
)
for artifact in "${attested_artifacts[@]}"; do
  if [ ! -f "$DOWNLOAD_DIR/$artifact" ]; then
    echo "error: attested canonical artifact missing: $artifact" >&2
    exit 1
  fi
  gh attestation verify "$DOWNLOAD_DIR/$artifact" --repo "$REPOSITORY" >/dev/null
done
echo "GitHub provenance attestations for the canonical unsigned build verified."

(
  cd "$DOWNLOAD_DIR"
  "${SHA256[@]}" -c BITCHAT_SHA256SUMS
)
echo "GitHub release checksums verified."

mv "$DOWNLOAD_DIR/BITCHAT_BUILDINFO.json" "$DOWNLOAD_DIR/BUILDINFO.json"
mv "$DOWNLOAD_DIR/BITCHAT_SHA256SUMS" "$DOWNLOAD_DIR/SHA256SUMS"
mv "$DOWNLOAD_DIR/BITCHAT_SHA256SUMS.unsigned" "$DOWNLOAD_DIR/SHA256SUMS.unsigned"

if [ "$MODE" = "--no-rebuild" ]; then
  exit 0
fi

release_commit="$(sed -n 's/.*"sourceCommit": *"\([^"]*\)".*/\1/p' "$DOWNLOAD_DIR/BUILDINFO.json")"
local_commit="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
if [ "$release_commit" != "$local_commit" ]; then
  echo "error: check out release commit $release_commit before rebuilding" >&2
  exit 1
fi

LOCAL_DIR="$PROJECT_ROOT/.reproducible-build/verify-$TAG"
if [ -e "$LOCAL_DIR" ]; then
  echo "error: local verification output already exists: $LOCAL_DIR" >&2
  exit 1
fi

"$SCRIPT_DIR/build-in-container.sh" "$LOCAL_DIR"
"$SCRIPT_DIR/compare-release.sh" "$LOCAL_DIR" "$DOWNLOAD_DIR"
echo "GitHub release $TAG was reproduced successfully from source."
