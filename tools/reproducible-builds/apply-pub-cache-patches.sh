#!/usr/bin/env bash

# Applies the project's patches to third-party Dart packages in PUB_CACHE.
#
# pub has no patch mechanism, so a package that does not build against this
# project's toolchain can only be fixed by editing the extracted cache copy.
# Doing that by hand is how this repository ended up in a state where the build
# worked on one developer machine and nowhere else: the edit lives outside
# version control, is invisible to CI, and disappears on `pub cache clean` or a
# new checkout. Every such edit belongs here instead.
#
# Each patch pins both checksums. An upstream release that changes the file
# fails loudly rather than being silently re-patched, and an already-patched
# cache is left alone, so the script is idempotent.

set -euo pipefail

pub_cache="${PUB_CACHE:-$HOME/.pub-cache}"
if [ ! -d "$pub_cache" ]; then
  echo "error: PUB_CACHE not found at $pub_cache" >&2
  exit 1
fi
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# patch_package_file PACKAGE RELATIVE_PATH PRISTINE_SHA256 PATCHED_SHA256 SED_SCRIPT
patch_package_file() {
  local package="$1" relative_path="$2" pristine="$3" patched="$4" sed_script="$5"
  local target="$pub_cache/hosted/pub.dev/$package/$relative_path"

  if [ ! -f "$target" ]; then
    echo "error: $package is not in PUB_CACHE; run flutter pub get first" >&2
    return 1
  fi

  local actual
  actual="$(sha256sum "$target" | cut -d' ' -f1)"

  if [ "$actual" = "$patched" ]; then
    return 0
  fi
  if [ "$actual" != "$pristine" ]; then
    echo "error: unexpected contents for $package/$relative_path" >&2
    echo "  expected pristine: $pristine" >&2
    echo "  expected patched:  $patched" >&2
    echo "  found:             $actual" >&2
    echo "  The package changed upstream. Re-review the patch and update both" >&2
    echo "  checksums in $(basename "${BASH_SOURCE[0]}")." >&2
    return 1
  fi

  sed "$sed_script" "$target" > "$target.patched"
  actual="$(sha256sum "$target.patched" | cut -d' ' -f1)"
  if [ "$actual" != "$patched" ]; then
    rm -f "$target.patched"
    echo "error: patching $package/$relative_path produced unexpected bytes" >&2
    echo "  expected: $patched" >&2
    echo "  produced: $actual" >&2
    return 1
  fi
  mv -f "$target.patched" "$target"
  echo "patched $package/$relative_path"
}

# flutter_inappwebview_android 1.1.3 is the newest stable release and still
# calls getDefaultProguardFile('proguard-android.txt'). AGP 9 removed that file
# outright because it carries -dontoptimize, and offers no compatibility flag,
# so configuring :flutter_inappwebview_android aborts the whole build. The
# optimize variant is what every current template uses.
#
# These proguardFiles sit in the plugin's own buildTypes block. An Android
# library contributes rules to a consuming app through consumerProguardFiles,
# not through these, so the substitution does not change the rules that reach
# this app's release build.
if [ -d "$pub_cache/hosted/pub.dev/flutter_inappwebview_android-1.1.3" ]; then
  patch_package_file \
    flutter_inappwebview_android-1.1.3 \
    android/build.gradle \
    82b483da0a2885adb5b5a5601bdad16289a0a68631b596ffb1001b6016fc0690 \
    25661c285c7e2204b595849e5b6cc92380765820e7f91567e26bc2a771e9627e \
    "s/getDefaultProguardFile('proguard-android.txt')/getDefaultProguardFile('proguard-android-optimize.txt')/g"
fi

patch_agus_navigation_location() {
  local expected_commit="bf20d1e7460fb08e5702514881d76cfa047caa00"
  local package_dir=""
  local candidate

  for candidate in "$pub_cache"/git/agus-maps-flutter-*; do
    [ -d "$candidate" ] || continue
    local actual_commit
    actual_commit="$(git -C "$candidate" rev-parse HEAD 2>/dev/null || true)"
    if [ "$actual_commit" = "$expected_commit" ]; then
      package_dir="$candidate"
      break
    fi
  done

  if [ -z "$package_dir" ]; then
    echo "error: pinned agus_maps_flutter source was not found in PUB_CACHE" >&2
    echo "  expected commit: $expected_commit" >&2
    return 1
  fi

  local patch_file="$script_dir/patches/agus-navigation-location.patch"
  if git -C "$package_dir" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
    return 0
  fi
  if ! git -C "$package_dir" apply --check "$patch_file"; then
    echo "error: Agus navigation location patch no longer matches upstream" >&2
    return 1
  fi

  git -C "$package_dir" apply "$patch_file"
  echo "patched Agus Maps navigation location bridge"
}

patch_agus_optional_locale() {
  local expected_commit="bf20d1e7460fb08e5702514881d76cfa047caa00"
  local package_dir=""
  local candidate

  for candidate in "$pub_cache"/git/agus-maps-flutter-*; do
    [ -d "$candidate" ] || continue
    local actual_commit
    actual_commit="$(git -C "$candidate" rev-parse HEAD 2>/dev/null || true)"
    if [ "$actual_commit" = "$expected_commit" ]; then
      package_dir="$candidate"
      break
    fi
  done

  if [ -z "$package_dir" ]; then
    echo "error: pinned agus_maps_flutter source was not found in PUB_CACHE" >&2
    echo "  expected commit: $expected_commit" >&2
    return 1
  fi

  local patch_file="$script_dir/patches/agus-optional-locale.patch"
  if git -C "$package_dir" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
    return 0
  fi
  if ! git -C "$package_dir" apply --check "$patch_file"; then
    echo "error: Agus Maps optional locale patch no longer matches upstream" >&2
    return 1
  fi

  git -C "$package_dir" apply "$patch_file"
  echo "patched Agus Maps optional native locale compatibility"
}

patch_agus_navigation_location
patch_agus_optional_locale
