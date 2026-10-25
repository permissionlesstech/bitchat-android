#!/usr/bin/env bash
set -euo pipefail

ARTIFACT_DIR="${1:?usage: verify-packaging.sh ARTIFACT_DIR}"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
count=0
for artifact in "$ARTIFACT_DIR"/*.apk "$ARTIFACT_DIR"/*.aab; do
    [[ -f "$artifact" ]] || continue
    count=$((count + 1))
    # jar is available in the canonical JDK container; no extra archive tooling.
    jar tf "$artifact" > "$BUILD_DIR/entries"
    awk '/jnidispatch/ { print }' "$BUILD_DIR/entries" | LC_ALL=C sort > "$BUILD_DIR/actual"
    case "${artifact##*/}" in
        *-wear-*) ABIS='' ;;
        *-arm64-*|*-arm64-v8a-*) ABIS='arm64-v8a' ;;
        *-armv7-*|*-armeabi-v7a-*) ABIS='armeabi-v7a' ;;
        *-x86_64-*) ABIS='x86_64' ;;
        *-x86-*) ABIS='x86' ;;
        *) ABIS='arm64-v8a armeabi-v7a x86_64 x86' ;;
    esac
    case "$artifact" in
        *.aab) PREFIX=base/lib ;;
        *) PREFIX=lib ;;
    esac
    for ABI in $ABIS; do
        echo "$PREFIX/$ABI/libjnidispatch.so"
    done | LC_ALL=C sort > "$BUILD_DIR/expected"
    if ! cmp -s "$BUILD_DIR/actual" "$BUILD_DIR/expected"; then
        echo "error: unexpected or missing JNA native libraries (${artifact##*/})" >&2
        exit 1
    fi
done
if [[ $count -eq 0 ]]; then
    echo 'error: no APK/AAB files to inspect' >&2
    exit 1
fi
echo "JNA packaging passed for $count artifacts"
