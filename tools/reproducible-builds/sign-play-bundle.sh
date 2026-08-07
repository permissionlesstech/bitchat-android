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

UNSIGNED_AAB="$RELEASE_DIR/bitchat-android-release-unsigned.aab"
SIGNED_AAB="$RELEASE_DIR/bitchat-android-play-upload.aab"
if [ -e "$SIGNED_AAB" ]; then
  echo "error: signed Play upload AAB already exists" >&2
  exit 1
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
STOREPASS_FILE="$TEMP_DIR/storepass"
KEYPASS_FILE="$TEMP_DIR/keypass"
printf '%s\n' "$BITCHAT_PLAY_KEYSTORE_PASSWORD" > "$STOREPASS_FILE"
printf '%s\n' "$BITCHAT_PLAY_KEY_PASSWORD" > "$KEYPASS_FILE"
chmod 600 "$STOREPASS_FILE" "$KEYPASS_FILE"

"$JARSIGNER" \
  -keystore "$KEYSTORE" \
  -storepass:file "$STOREPASS_FILE" \
  -keypass:file "$KEYPASS_FILE" \
  -digestalg SHA-256 \
  -signedjar "$SIGNED_AAB" \
  "$UNSIGNED_AAB" \
  "$BITCHAT_PLAY_UPLOAD_KEY_ALIAS"

"$JARSIGNER" -verify "$SIGNED_AAB" >/dev/null
"$SCRIPT_DIR/compare-archive-payloads.sh" "$UNSIGNED_AAB" "$SIGNED_AAB"

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

echo "Verified unsigned AAB was signed locally with the Play upload key and checksummed."
