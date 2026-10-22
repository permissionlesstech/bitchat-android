#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUTPUT_DIR="${1:-$PROJECT_ROOT/.reproducible-build/release}"

# shellcheck disable=SC1091
source "$SCRIPT_DIR/TOOLCHAIN.env"

if [ "${BITCHAT_SOURCE_TREE_VERIFIED:-0}" != "1" ] &&
  [ "${BITCHAT_ALLOW_DIRTY:-0}" != "1" ] &&
  [ -n "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)" ]; then
  echo "error: reproducible builds require a clean source tree" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
if [ -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "error: output directory must be empty: $OUTPUT_DIR" >&2
  exit 1
fi

actual_java_version="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.version = //p' | head -1)"
expected_java_version="${JAVA_VERSION%%+*}"
if [ "$actual_java_version" != "$expected_java_version" ]; then
  echo "error: JDK $expected_java_version is required; found $actual_java_version" >&2
  exit 1
fi

"$PROJECT_ROOT/tools/arti-build/verify-checksums.sh"

export GRADLE_USER_HOME="${BITCHAT_GRADLE_USER_HOME:-$PROJECT_ROOT/.reproducible-build/gradle-home}"
export LC_ALL=C.UTF-8
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$PROJECT_ROOT" log -1 --format=%ct)}"
export TZ=UTC
if ! [[ "$SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]]; then
  echo "error: SOURCE_DATE_EPOCH must be an integer" >&2
  exit 1
fi

gradle_args=(
  # R8 otherwise randomizes input traversal and uses multiple compiler threads.
  # The optimized DEX can still match while its embedded ProGuard map differs.
  -Dcom.android.tools.r8.deterministicdebugging=1
  -Pkotlin.compiler.execution.strategy=in-process
  -Pkotlin.incremental=false
  --no-build-cache
  --no-configuration-cache
  --no-daemon
  --no-watch-fs
  --rerun-tasks
)

cd "$PROJECT_ROOT"
./gradlew "${gradle_args[@]}" :app:clean :app:bundleRelease

aab_source="$PROJECT_ROOT/app/build/outputs/bundle/release/app-release.aab"
if [ ! -f "$aab_source" ]; then
  echo "error: expected phone release AAB not found" >&2
  exit 1
fi
cp "$aab_source" "$OUTPUT_DIR/bitchat-android-release-unsigned.aab"

./gradlew "${gradle_args[@]}" :wear:clean :wear:bundleRelease

wear_aab_source="$PROJECT_ROOT/wear/build/outputs/bundle/release/wear-release.aab"
if [ ! -f "$wear_aab_source" ]; then
  echo "error: expected Wear release AAB not found" >&2
  exit 1
fi
cp "$wear_aab_source" "$OUTPUT_DIR/bitchat-android-wear-release-unsigned.aab"

# AGP cannot build split APKs and an app bundle from the same intermediates.
./gradlew "${gradle_args[@]}" :app:clean :app:assembleRelease

declare -A apk_names=(
  ["app-arm64-v8a-release-unsigned.apk"]="bitchat-android-arm64-unsigned.apk"
  ["app-armeabi-v7a-release-unsigned.apk"]="bitchat-android-armv7-unsigned.apk"
  ["app-universal-release-unsigned.apk"]="bitchat-android-universal-unsigned.apk"
  ["app-x86-release-unsigned.apk"]="bitchat-android-x86-unsigned.apk"
  ["app-x86_64-release-unsigned.apk"]="bitchat-android-x86_64-unsigned.apk"
)

for source_name in "${!apk_names[@]}"; do
  source_path="$PROJECT_ROOT/app/build/outputs/apk/release/$source_name"
  if [ ! -f "$source_path" ]; then
    echo "error: expected release APK not found: $source_name" >&2
    exit 1
  fi
  cp "$source_path" "$OUTPUT_DIR/${apk_names[$source_name]}"
done

./gradlew "${gradle_args[@]}" :wear:clean :wear:assembleRelease

wear_apk_source="$PROJECT_ROOT/wear/build/outputs/apk/release/wear-release-unsigned.apk"
if [ ! -f "$wear_apk_source" ]; then
  echo "error: expected Wear release APK not found" >&2
  exit 1
fi
cp "$wear_apk_source" "$OUTPUT_DIR/bitchat-android-wear-unsigned.apk"

source_commit="${BITCHAT_SOURCE_COMMIT:-$(git -C "$PROJECT_ROOT" rev-parse HEAD)}"
if ! [[ "$source_commit" =~ ^([0-9a-f]{40}|[0-9a-f]{64})$ ]]; then
  echo "error: source commit must be a full Git object ID" >&2
  exit 1
fi
native_manifest_sha256="$(sha256sum "$PROJECT_ROOT/tools/arti-build/SHA256SUMS" | awk '{print $1}')"

cat > "$OUTPUT_DIR/BUILDINFO.json" <<EOF
{
  "schemaVersion": 1,
  "sourceCommit": "$source_commit",
  "sourceDateEpoch": $SOURCE_DATE_EPOCH,
  "javaVersion": "$JAVA_VERSION",
  "gradleVersion": "$GRADLE_VERSION",
  "androidCompileSdk": "$ANDROID_COMPILE_SDK",
  "androidBuildToolsVersion": "$ANDROID_BUILD_TOOLS_VERSION",
  "nativeManifestSha256": "$native_manifest_sha256"
}
EOF

"$SCRIPT_DIR/verify-no-host-paths.sh" "$OUTPUT_DIR"

(
  cd "$OUTPUT_DIR"
  {
    sha256sum BUILDINFO.json
    sha256sum bitchat-android-*-unsigned.apk
    sha256sum bitchat-android-release-unsigned.aab
    sha256sum bitchat-android-wear-release-unsigned.aab
  } | sort -k2 > SHA256SUMS.unsigned
)

echo "Reproducible unsigned release written to $OUTPUT_DIR"
