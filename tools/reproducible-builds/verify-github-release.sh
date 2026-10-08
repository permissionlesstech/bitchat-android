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
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required" >&2
  exit 1
fi

# Use the same SDK and certificate pin as local release signing.
# shellcheck disable=SC1091
source "$SCRIPT_DIR/TOOLCHAIN.env"
ANDROID_SDK_PATH="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
APKSIGNER="$ANDROID_SDK_PATH/build-tools/$ANDROID_BUILD_TOOLS_VERSION/apksigner"
if [ -z "$ANDROID_SDK_PATH" ] || [ ! -x "$APKSIGNER" ]; then
  echo "error: Android Build Tools $ANDROID_BUILD_TOOLS_VERSION are required" >&2
  exit 1
fi
EXPECTED_CERT_SHA256="$(
  sed -n 's/^BITCHAT_GITHUB_RELEASE_CERT_SHA256=//p' "$PROJECT_ROOT/gradle.properties" |
    tr -d ':\r[:space:]' | tr '[:upper:]' '[:lower:]'
)"
if ! [[ "$EXPECTED_CERT_SHA256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "error: expected GitHub release certificate fingerprint is invalid" >&2
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
  gh attestation verify "$DOWNLOAD_DIR/$artifact" \
    --repo "$REPOSITORY" \
    --signer-workflow "$REPOSITORY/.github/workflows/release.yml" \
    --source-ref "refs/tags/$TAG" \
    --deny-self-hosted-runners >/dev/null
done
echo "GitHub provenance attestations for the canonical unsigned build verified."

python3 "$SCRIPT_DIR/verify_release_assets.py" "$DOWNLOAD_DIR"
echo "Complete GitHub release checksum inventory verified."

for flavor in arm64 universal wear x86_64; do
  signed="$DOWNLOAD_DIR/bitchat-android-$flavor.apk"
  unsigned="$DOWNLOAD_DIR/bitchat-android-$flavor-unsigned.apk"
  certificate_output="$("$APKSIGNER" verify --print-certs "$signed")"
  actual_cert_sha256="$(printf '%s\n' "$certificate_output" |
    sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' |
    tr -d ':\r[:space:]' | tr '[:upper:]' '[:lower:]')"
  # Multiple signers also fail: their concatenated fingerprints cannot match
  # the single release-certificate pin.
  if [ "$actual_cert_sha256" != "$EXPECTED_CERT_SHA256" ]; then
    echo "error: APK release signer mismatch: $flavor" >&2
    exit 1
  fi
  "$SCRIPT_DIR/compare-archive-payloads.sh" "$unsigned" "$signed"
done

# AAB upload certificates are separate from the APK release key. Compare their
# payloads to the attested builds without claiming to verify the Play signer.
"$SCRIPT_DIR/compare-archive-payloads.sh" \
  "$DOWNLOAD_DIR/bitchat-android-release-unsigned.aab" \
  "$DOWNLOAD_DIR/bitchat-android-play-upload.aab"
"$SCRIPT_DIR/compare-archive-payloads.sh" \
  "$DOWNLOAD_DIR/bitchat-android-wear-release-unsigned.aab" \
  "$DOWNLOAD_DIR/bitchat-android-wear-play-upload.aab"

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
