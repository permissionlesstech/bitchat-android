#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RELEASE_DIR="${1:?usage: sign-release.sh RELEASE_DIR}"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/TOOLCHAIN.env"

: "${BITCHAT_GITHUB_KEYSTORE:?BITCHAT_GITHUB_KEYSTORE is required}"
: "${BITCHAT_GITHUB_KEY_ALIAS:?BITCHAT_GITHUB_KEY_ALIAS is required}"
: "${BITCHAT_GITHUB_KEYSTORE_PASSWORD:?BITCHAT_GITHUB_KEYSTORE_PASSWORD is required}"
: "${BITCHAT_GITHUB_KEY_PASSWORD:?BITCHAT_GITHUB_KEY_PASSWORD is required}"

if [ ! -f "$BITCHAT_GITHUB_KEYSTORE" ]; then
  echo "error: GitHub release keystore not found" >&2
  exit 1
fi
KEYSTORE="$(
  cd "$(dirname "$BITCHAT_GITHUB_KEYSTORE")"
  printf '%s/%s\n' "$PWD" "$(basename "$BITCHAT_GITHUB_KEYSTORE")"
)"

ANDROID_SDK_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$ANDROID_SDK_HOME" ]; then
  echo "error: ANDROID_SDK_ROOT or ANDROID_HOME is required" >&2
  exit 1
fi

APKSIGNER="$ANDROID_SDK_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION/apksigner"
ZIPALIGN="$ANDROID_SDK_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION/zipalign"
if [ ! -x "$APKSIGNER" ] || [ ! -x "$ZIPALIGN" ]; then
  echo "error: Android Build Tools $ANDROID_BUILD_TOOLS_VERSION are required" >&2
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

(
  cd "$RELEASE_DIR"
  "${SHA256[@]}" -c SHA256SUMS.unsigned
)

EXPECTED_CERT_SHA256="$(
  sed -n 's/^BITCHAT_GITHUB_RELEASE_CERT_SHA256=//p' "$PROJECT_ROOT/gradle.properties" |
    tr -d ':\r[:space:]' |
    tr '[:upper:]' '[:lower:]'
)"
if ! [[ "$EXPECTED_CERT_SHA256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "error: expected GitHub release certificate fingerprint is invalid" >&2
  exit 1
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
export BITCHAT_GITHUB_KEYSTORE_PASSWORD
export BITCHAT_GITHUB_KEY_PASSWORD

unsigned_names=(
  "bitchat-android-arm64-unsigned.apk"
  "bitchat-android-universal-unsigned.apk"
  "bitchat-android-wear-unsigned.apk"
  "bitchat-android-x86_64-unsigned.apk"
)
signed_names=(
  "bitchat-android-arm64.apk"
  "bitchat-android-universal.apk"
  "bitchat-android-wear.apk"
  "bitchat-android-x86_64.apk"
)

for ((index = 0; index < ${#unsigned_names[@]}; index++)); do
  unsigned_name="${unsigned_names[$index]}"
  signed_name="${signed_names[$index]}"
  unsigned_apk="$RELEASE_DIR/$unsigned_name"
  signed_apk="$RELEASE_DIR/$signed_name"
  first_signed="$TEMP_DIR/first.apk"
  second_signed="$TEMP_DIR/second.apk"

  if [ -e "$signed_apk" ]; then
    echo "error: signed APK already exists: $signed_name" >&2
    exit 1
  fi
  "$ZIPALIGN" -c -P 16 4 "$unsigned_apk"

  for output_apk in "$first_signed" "$second_signed"; do
    "$APKSIGNER" sign \
      --ks "$KEYSTORE" \
      --ks-key-alias "$BITCHAT_GITHUB_KEY_ALIAS" \
      --ks-pass env:BITCHAT_GITHUB_KEYSTORE_PASSWORD \
      --key-pass env:BITCHAT_GITHUB_KEY_PASSWORD \
      --deterministic-dsa-signing true \
      --min-sdk-version 26 \
      --v1-signing-enabled false \
      --v2-signing-enabled true \
      --v3-signing-enabled true \
      --v4-signing-enabled false \
      --out "$output_apk" \
      "$unsigned_apk"
  done

  if ! cmp -s "$first_signed" "$second_signed"; then
    echo "error: signing is not deterministic for $unsigned_name" >&2
    exit 1
  fi

  mv "$first_signed" "$signed_apk"
  actual_cert_sha256="$(
    "$APKSIGNER" verify --print-certs "$signed_apk" |
      sed -n 's/.*certificate SHA-256 digest: //p' |
      head -1 |
      tr -d ':\r[:space:]' |
      tr '[:upper:]' '[:lower:]'
  )"
  if [ "$actual_cert_sha256" != "$EXPECTED_CERT_SHA256" ]; then
    echo "error: signing certificate fingerprint mismatch" >&2
    exit 1
  fi
  "$APKSIGNER" verify --verbose "$signed_apk" >/dev/null
  rm -f "$second_signed"
done

(
  cd "$RELEASE_DIR"
  {
    for artifact in *; do
      [ "$artifact" = "SHA256SUMS" ] && continue
      [ -f "$artifact" ] || continue
      "${SHA256[@]}" "$artifact"
    done
  } | sort -k2 > SHA256SUMS
)

echo "Verified unsigned APKs were deterministically signed locally and checksummed."
