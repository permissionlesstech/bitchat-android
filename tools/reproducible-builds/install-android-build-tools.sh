#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${1:?usage: install-android-build-tools.sh SDK_ROOT}"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/TOOLCHAIN.env"

for command in curl sha256sum unzip; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "error: $command is required" >&2
    exit 1
  fi
done

destination="$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION"
if [ -e "$destination" ]; then
  echo "error: Android Build Tools destination already exists: $destination" >&2
  exit 1
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
archive="$TEMP_DIR/$ANDROID_BUILD_TOOLS_ARCHIVE"

curl -fsSL \
  "https://dl.google.com/android/repository/$ANDROID_BUILD_TOOLS_ARCHIVE" \
  -o "$archive"
echo "$ANDROID_BUILD_TOOLS_SHA256  $archive" | sha256sum -c -
unzip -q "$archive" -d "$TEMP_DIR/unpacked"

mkdir -p "$SDK_ROOT/build-tools"
mv "$TEMP_DIR/unpacked/android-$ANDROID_PLATFORM_VERSION" "$destination"
test "$(
  sed -n 's/^Pkg.Revision=//p' "$destination/source.properties" | tr -d '\r'
)" = "$ANDROID_BUILD_TOOLS_VERSION"

echo "Installed checksum-verified Android Build Tools $ANDROID_BUILD_TOOLS_VERSION."
