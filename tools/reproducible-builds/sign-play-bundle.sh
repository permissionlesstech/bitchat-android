#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_DIR="${1:?usage: sign-play-bundle.sh RELEASE_DIR}"

: "${BITCHAT_PLAY_UPLOAD_KEYSTORE:?BITCHAT_PLAY_UPLOAD_KEYSTORE is required}"
: "${BITCHAT_PLAY_UPLOAD_KEY_ALIAS:?BITCHAT_PLAY_UPLOAD_KEY_ALIAS is required}"
: "${BITCHAT_PLAY_KEYSTORE_PASSWORD:?BITCHAT_PLAY_KEYSTORE_PASSWORD is required}"
: "${BITCHAT_PLAY_KEY_PASSWORD:?BITCHAT_PLAY_KEY_PASSWORD is required}"

if [ ! -d "$RELEASE_DIR" ]; then
  echo "error: release directory not found" >&2
  exit 1
fi
RELEASE_DIR="$(cd "$RELEASE_DIR" && pwd)"

if [ ! -f "$BITCHAT_PLAY_UPLOAD_KEYSTORE" ]; then
  echo "error: Play upload keystore not found" >&2
  exit 1
fi
KEYSTORE="$(
  cd "$(dirname "$BITCHAT_PLAY_UPLOAD_KEYSTORE")"
  printf '%s/%s\n' "$PWD" "$(basename "$BITCHAT_PLAY_UPLOAD_KEYSTORE")"
)"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jarsigner" ]; then
  JARSIGNER="$JAVA_HOME/bin/jarsigner"
elif command -v jarsigner >/dev/null 2>&1; then
  JARSIGNER="$(command -v jarsigner)"
else
  echo "error: jarsigner is required; set JAVA_HOME to the pinned JDK" >&2
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

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
STOREPASS_FILE="$TEMP_DIR/storepass"
KEYPASS_FILE="$TEMP_DIR/keypass"
printf '%s\n' "$BITCHAT_PLAY_KEYSTORE_PASSWORD" > "$STOREPASS_FILE"
printf '%s\n' "$BITCHAT_PLAY_KEY_PASSWORD" > "$KEYPASS_FILE"
chmod 600 "$STOREPASS_FILE" "$KEYPASS_FILE"

unsigned_names=(
  "bitchat-android-release-unsigned.aab"
  "bitchat-android-wear-release-unsigned.aab"
)
signed_names=(
  "bitchat-android-play-upload.aab"
  "bitchat-android-wear-play-upload.aab"
)

for ((index = 0; index < ${#unsigned_names[@]}; index++)); do
  unsigned_aab="$RELEASE_DIR/${unsigned_names[$index]}"
  signed_aab="$RELEASE_DIR/${signed_names[$index]}"

  if [ ! -f "$unsigned_aab" ]; then
    echo "error: unsigned Play AAB not found: ${unsigned_names[$index]}" >&2
    exit 1
  fi
  if [ -e "$signed_aab" ]; then
    echo "error: signed Play upload AAB already exists: ${signed_names[$index]}" >&2
    exit 1
  fi

  "$JARSIGNER" \
    -keystore "$KEYSTORE" \
    -storepass:file "$STOREPASS_FILE" \
    -keypass:file "$KEYPASS_FILE" \
    -digestalg SHA-256 \
    -signedjar "$signed_aab" \
    "$unsigned_aab" \
    "$BITCHAT_PLAY_UPLOAD_KEY_ALIAS"

  "$JARSIGNER" -verify "$signed_aab" >/dev/null
  "$SCRIPT_DIR/compare-archive-payloads.sh" "$unsigned_aab" "$signed_aab"
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

echo "Verified unsigned phone and Wear AABs were signed locally with the Play upload key and checksummed."
