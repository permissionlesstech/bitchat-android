#!/usr/bin/env bash

set -euo pipefail

FIRST_ARCHIVE="${1:?usage: compare-archive-payloads.sh FIRST.apk|aab SECOND.apk|aab}"
SECOND_ARCHIVE="${2:?usage: compare-archive-payloads.sh FIRST.apk|aab SECOND.apk|aab}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

if ! command -v unzip >/dev/null 2>&1; then
  echo "error: unzip is required" >&2
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

write_payload_manifest() {
  local archive="$1"
  local output="$2"

  : > "$output"
  while IFS= read -r entry; do
    case "$entry" in
      */|META-INF/MANIFEST.MF|META-INF/*.SF|META-INF/*.RSA|META-INF/*.DSA|META-INF/*.EC)
        continue
        ;;
    esac
    local digest
    digest="$(unzip -p "$archive" "$entry" | "${SHA256[@]}" | awk '{print $1}')"
    printf '%s  %s\n' "$digest" "$entry" >> "$output"
  done < <(unzip -Z1 "$archive" | LC_ALL=C sort)
}

write_payload_manifest "$FIRST_ARCHIVE" "$TEMP_DIR/first"
write_payload_manifest "$SECOND_ARCHIVE" "$TEMP_DIR/second"

if ! diff -u "$TEMP_DIR/first" "$TEMP_DIR/second"; then
  echo "error: signed/unsigned archive payloads differ" >&2
  exit 1
fi

echo "Archive entry names and uncompressed payload bytes match (signing metadata excluded)."
