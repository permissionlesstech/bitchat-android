#!/usr/bin/env bash

set -euo pipefail

ARTIFACT_DIR="${1:?usage: verify-no-host-paths.sh ARTIFACT_DIR}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

archive_index=0
for artifact in "$ARTIFACT_DIR"/*.apk "$ARTIFACT_DIR"/*.aab; do
  [ -f "$artifact" ] || continue
  archive_index=$((archive_index + 1))
  archive_dir="$TEMP_DIR/$archive_index"
  mkdir -p "$archive_dir"
  if command -v unzip >/dev/null 2>&1; then
    unzip -qq "$artifact" 'lib/*/*.so' 'base/lib/*/*.so' -d "$archive_dir" 2>/dev/null || true
  elif command -v jar >/dev/null 2>&1; then
    (
      cd "$archive_dir"
      jar xf "$artifact"
    )
  else
    echo "error: unzip or jar is required to inspect release artifacts" >&2
    exit 1
  fi
done

while IFS= read -r -d '' library; do
  # Read the complete stream: grep -q can hide a match through SIGPIPE under pipefail.
  if strings "$library" 2>/dev/null | grep -E '/Users/|/home/|[A-Za-z]:\\+Users\\+' >/dev/null 2>&1; then
    echo "error: host-specific path detected in a release artifact (${library##*/})" >&2
    exit 1
  else
    native_scan_status=("${PIPESTATUS[@]}")
    if [ "${native_scan_status[0]}" -ne 0 ] || [ "${native_scan_status[1]}" -ne 1 ]; then
      echo "error: native library inspection failed (${library##*/})" >&2
      exit 1
    fi
  fi
done < <(find "$TEMP_DIR" -type f -name '*.so' -print0)

echo "Release artifacts contain no host-specific native paths."
