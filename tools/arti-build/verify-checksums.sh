#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHECKSUM_FILE="$SCRIPT_DIR/SHA256SUMS"

if [ ! -f "$CHECKSUM_FILE" ]; then
  echo "error: missing native checksum manifest: $CHECKSUM_FILE" >&2
  exit 1
fi

cd "$PROJECT_ROOT"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c "$CHECKSUM_FILE"
else
  shasum -a 256 -c "$CHECKSUM_FILE"
fi

for library in app/src/main/jniLibs/*/libarti_android.so; do
  # Read the complete stream: grep -q can hide a match through SIGPIPE under pipefail.
  if strings "$library" 2>/dev/null | grep -E '/Users/|/home/|[A-Za-z]:\\+Users\\+' >/dev/null 2>&1; then
    echo "error: host-specific path detected in a native library (${library##*/})" >&2
    exit 1
  else
    native_scan_status=("${PIPESTATUS[@]}")
    if [ "${native_scan_status[0]}" -ne 0 ] || [ "${native_scan_status[1]}" -ne 1 ]; then
      echo "error: native library inspection failed (${library##*/})" >&2
      exit 1
    fi
  fi
done

echo "Native Arti libraries match the pinned checksums and contain no host paths."
