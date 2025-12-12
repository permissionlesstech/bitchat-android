#!/usr/bin/env bash
#
# Rebuild Arti native libraries from official source
#
# This script clones the official Arti repository, applies our custom JNI wrapper,
# and builds the native libraries for Android. Use this to:
#   - Verify the pre-built .so files match the source
#   - Update to a new Arti version
#   - Debug or modify the wrapper code
#
# Requirements:
#   - Bash 4+ (macOS default bash is 3.2; install via Homebrew: brew install bash)
#   - Rust toolchain with Android targets:
#       rustup target add aarch64-linux-android x86_64-linux-android
#   - cargo-ndk: cargo install cargo-ndk
#   - Android NDK 25+ (for 16KB page size support)
#
# Usage:
#   ./build-arti.sh              # Build both architectures (debug/emulator)
#   ./build-arti.sh --release    # Build ARM64 only (production)
#   ./build-arti.sh --clean      # Remove cloned Arti repo and rebuild

set -euo pipefail

# ==============================================================================
# Configuration
# ==============================================================================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script and project directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ARTI_SOURCE_DIR="$SCRIPT_DIR/.arti-source"
JNILIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"


detect_default_ndk_home() {
  local candidates=(
    "$HOME/Library/Android/sdk/ndk/27.0.12077973"
    "$HOME/Library/Android/sdk/ndk"
    "$HOME/Library/Android/sdk/ndk-bundle"
    "$HOME/Android/Sdk/ndk/27.0.12077973"
    "$HOME/Android/Sdk/ndk"
    "$HOME/Android/Sdk/ndk-bundle"
  )

  for candidate in "${candidates[@]}"; do
    if [ -d "$candidate" ]; then
      echo "$candidate"
      return
    fi
  done

  local base
  for base in "$HOME/Library/Android/sdk/ndk" "$HOME/Android/Sdk/ndk"; do
    if [ -d "$base" ]; then
      local latest
      latest="$(find "$base" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort | tail -1)"
      if [ -n "$latest" ]; then
        echo "$latest"
        return
      fi
    fi
  done

  echo ""
}

# Read pinned version
if [ ! -f "$SCRIPT_DIR/ARTI_VERSION" ]; then
  echo -e "${RED}Error: ARTI_VERSION file not found${NC}"
  exit 1
fi
VERSION="$(tr -d '[:space:]' < "$SCRIPT_DIR/ARTI_VERSION")"

# Android NDK path
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  AUTO_NDK_HOME="$(detect_default_ndk_home)"
  if [ -n "$AUTO_NDK_HOME" ]; then
    ANDROID_NDK_HOME="$AUTO_NDK_HOME"
  fi
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo -e "${RED}Error: ANDROID_NDK_HOME is not set and automatic detection failed.${NC}"
  echo "Set ANDROID_NDK_HOME to your NDK installation (e.g., ~/Android/Sdk/ndk/<version>)."
  exit 1
fi
export ANDROID_NDK_HOME

# Min SDK version (must match bitchat-android minSdk)
MIN_SDK_VERSION=26

# Parse arguments
RELEASE_ONLY=false
CLEAN_BUILD=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)
      RELEASE_ONLY=true
      shift
      ;;
    --clean)
      CLEAN_BUILD=true
      shift
      ;;
    --help|-h)
      echo "Usage: $0 [--release] [--clean]"
      echo ""
      echo "Options:"
      echo "  --release    Build ARM64 only (smaller, for production)"
      echo "  --clean      Remove cached Arti source and rebuild from scratch"
      echo ""
      exit 0
      ;;
    *)
      echo -e "${RED}Error: Unknown argument: $1${NC}"
      echo "Run: $0 --help"
      exit 1
      ;;
  esac
done

# Architectures to build
if [ "$RELEASE_ONLY" = true ]; then
  TARGETS=("aarch64-linux-android")
else
  TARGETS=("aarch64-linux-android" "x86_64-linux-android")
fi

# Map Rust targets to Android ABI names
declare -A ABI_MAP=(
  ["aarch64-linux-android"]="arm64-v8a"
  ["x86_64-linux-android"]="x86_64"
)

# Toolchain placeholders (set in detect_ndk_host)
NDK_HOST=""
NDK_LLVM_BIN=""
LLVM_STRIP=""
LLVM_NM=""
LLVM_READELF=""

# ==============================================================================
# Functions
# ==============================================================================

print_header() {
  echo -e "${BLUE}=========================================${NC}"
  echo -e "${BLUE}$1${NC}"
  echo -e "${BLUE}=========================================${NC}"
}

print_success() { echo -e "${GREEN}$1${NC}"; }
print_error() { echo -e "${RED}$1${NC}"; }
print_info() { echo -e "${YELLOW}$1${NC}"; }

detect_ndk_host() {
  local uname_s
  uname_s="$(uname -s)"
  local PREBUILT_DIR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
  local HOST_CANDIDATES=()

  case "$uname_s" in
    Darwin)
      HOST_CANDIDATES=("darwin-arm64" "darwin-x86_64")
      ;;
    Linux)
      HOST_CANDIDATES=("linux-x86_64" "linux-arm64" "linux-aarch64")
      ;;
    *)
      print_error "Unsupported host OS: $uname_s"
      exit 1
      ;;
  esac

  for candidate in "${HOST_CANDIDATES[@]}"; do
    if [ -d "$PREBUILT_DIR/$candidate" ]; then
      NDK_HOST="$candidate"
      NDK_LLVM_BIN="$PREBUILT_DIR/$candidate/bin"
      LLVM_STRIP="$NDK_LLVM_BIN/llvm-strip"
      LLVM_NM="$NDK_LLVM_BIN/llvm-nm"
      LLVM_READELF="$NDK_LLVM_BIN/llvm-readelf"
      return 0
    fi
  done

  print_error "No compatible NDK toolchain found under $PREBUILT_DIR"
  print_info "Searched for: ${HOST_CANDIDATES[*]}"
  exit 1
}

check_prerequisites() {
  print_header "Checking Prerequisites"

  # Bash version
  if [ "${BASH_VERSINFO:-0}" -lt 4 ]; then
    print_error "Bash 4+ is required. macOS ships bash 3.2 by default."
    print_info "macOS: brew install bash"
    print_info "Linux: use your distro package manager (e.g., sudo apt install bash)"
    print_info "Then run with the installed bash (e.g., /opt/homebrew/bin/bash ./build-arti.sh)"
    exit 1
  fi
  print_success "Bash found: $BASH_VERSION"

  # Git
  if ! command -v git >/dev/null 2>&1; then
    print_error "git is not installed."
    exit 1
  fi
  print_success "git found: $(git --version)"

  # Rust
  if ! command -v rustc >/dev/null 2>&1; then
    print_error "Rust is not installed. Install from https://rustup.rs/"
    exit 1
  fi
  print_success "Rust found: $(rustc --version)"

  # rustup
  if ! command -v rustup >/dev/null 2>&1; then
    print_error "rustup is required (for managing targets). Install from https://rustup.rs/"
    exit 1
  fi
  print_success "rustup found: $(rustup --version | head -1)"

  # cargo-ndk
  if ! command -v cargo-ndk >/dev/null 2>&1; then
    print_error "cargo-ndk is not installed. Run: cargo install cargo-ndk"
    exit 1
  fi
  print_success "cargo-ndk found: $(cargo-ndk --version 2>/dev/null || echo 'installed')"

  # NDK
  if [ ! -d "$ANDROID_NDK_HOME" ]; then
    print_error "Android NDK not found at: $ANDROID_NDK_HOME"
    print_info "Set ANDROID_NDK_HOME environment variable to your NDK location"
    exit 1
  fi
  print_success "Android NDK found: $ANDROID_NDK_HOME"

  # NDK version (should be 25+)
  NDK_VERSION="$(basename "$ANDROID_NDK_HOME" | cut -d'.' -f1)"
  if ! [[ "$NDK_VERSION" =~ ^[0-9]+$ ]]; then
    print_error "Could not parse NDK version from ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
    exit 1
  fi
  if [ "$NDK_VERSION" -lt 25 ]; then
    print_error "NDK version $NDK_VERSION is too old. NDK 25+ required for 16KB page size support"
    exit 1
  fi
  print_success "NDK version: $NDK_VERSION (supports 16KB page size)"

  detect_ndk_host
  if [ ! -d "$NDK_LLVM_BIN" ]; then
    print_error "NDK LLVM toolchain bin directory not found: $NDK_LLVM_BIN"
    exit 1
  fi
  print_success "NDK host tag: $NDK_HOST"

  if [ ! -x "$LLVM_STRIP" ]; then
    print_info "llvm-strip not found at: $LLVM_STRIP (stripping will be skipped)"
  else
    print_success "llvm-strip found"
  fi

  if [ ! -x "$LLVM_NM" ] && ! command -v nm >/dev/null 2>&1; then
    print_error "Neither llvm-nm nor nm found. Cannot verify JNI symbols."
    exit 1
  fi
  if [ -x "$LLVM_NM" ]; then
    print_success "llvm-nm found"
  else
    print_info "llvm-nm not found, will fall back to system nm"
  fi

  if [ ! -x "$LLVM_READELF" ] && ! command -v readelf >/dev/null 2>&1; then
    print_info "Neither llvm-readelf nor readelf found. Alignment verification may be skipped."
  else
    print_success "readelf capability available"
  fi
  # Android targets
  for TARGET in "${TARGETS[@]}"; do
    if ! rustup target list --installed | grep -qx "$TARGET"; then
      print_error "Rust target $TARGET not installed"
      print_info "Run: rustup target add $TARGET"
      exit 1
    fi
  done
  print_success "All Rust Android targets installed"

  echo ""
}

clone_or_update_arti() {
  print_header "Setting up Arti Source (version: $VERSION)"

  if [ "$CLEAN_BUILD" = true ] && [ -d "$ARTI_SOURCE_DIR" ]; then
    print_info "Cleaning existing Arti source..."
    rm -rf "$ARTI_SOURCE_DIR"
  fi

  if [ ! -d "$ARTI_SOURCE_DIR" ]; then
    print_info "Cloning official Arti repository..."
    git clone https://gitlab.torproject.org/tpo/core/arti.git "$ARTI_SOURCE_DIR"
  else
    print_info "Using cached Arti source at $ARTI_SOURCE_DIR"
  fi

  cd "$ARTI_SOURCE_DIR"

  print_info "Fetching tags..."
  git fetch --tags --quiet

  print_info "Checking out version: $VERSION"
  git checkout "$VERSION" --quiet 2>/dev/null || {
    print_error "Version $VERSION not found. Available versions:"
    git tag | grep "^arti-v" | tail -10
    exit 1
  }

  # Ensure clean working tree to avoid cached modifications influencing builds
  print_info "Resetting repository state (hard) and cleaning untracked files..."
  git reset --hard --quiet
  git clean -ffdqx --quiet

  print_success "Arti source ready at version $VERSION"
  echo ""
}

setup_wrapper() {
  print_header "Setting up JNI Wrapper"

  WRAPPER_DIR="$ARTI_SOURCE_DIR/arti-android-wrapper"

  # Recreate wrapper directory to avoid stale files
  rm -rf "$WRAPPER_DIR"
  mkdir -p "$WRAPPER_DIR/src"

  cp "$SCRIPT_DIR/src/lib.rs" "$WRAPPER_DIR/src/"
  cp "$SCRIPT_DIR/Cargo.toml" "$WRAPPER_DIR/"

  print_success "Wrapper files copied to $WRAPPER_DIR"
  echo ""
}

build_for_target() {
  local TARGET="$1"
  local ABI="${ABI_MAP[$TARGET]}"
  local OUTPUT_PATH="$JNILIBS_DIR/$ABI"

  print_header "Building for $ABI ($TARGET)"

  mkdir -p "$OUTPUT_PATH"

  print_info "Building Arti Android wrapper..."
  cargo ndk \
    -t "$TARGET" \
    --platform "$MIN_SDK_VERSION" \
    -o "$OUTPUT_PATH" \
    build --release \
    --locked \
    --manifest-path "$ARTI_SOURCE_DIR/arti-android-wrapper/Cargo.toml"

  local LIB_NAME="libarti_android.so"
  local NESTED_PATH="$OUTPUT_PATH/$ABI/$LIB_NAME"

  if [ -f "$NESTED_PATH" ]; then
    mv "$NESTED_PATH" "$OUTPUT_PATH/$LIB_NAME"
    rmdir "$OUTPUT_PATH/$ABI" 2>/dev/null || true
  fi

  if [ -f "$OUTPUT_PATH/$LIB_NAME" ]; then
    print_success "Built: $OUTPUT_PATH/$LIB_NAME"

    # Strip debug symbols safely
    print_info "Stripping debug symbols..."
    if [ -x "$LLVM_STRIP" ]; then
      "$LLVM_STRIP" --strip-debug "$OUTPUT_PATH/$LIB_NAME" 2>/dev/null || true
      print_success "Stripped debug symbols"
    else
      print_info "Skipping strip (llvm-strip not available)"
    fi

    local SIZE
    SIZE="$(du -h "$OUTPUT_PATH/$LIB_NAME" | cut -f1)"
    print_success "Final size: $SIZE"

    # Verify 16KB page size alignment (best-effort)
    print_info "Verifying 16KB page alignment..."
    local READELF_TOOL=""
    if [ -x "$LLVM_READELF" ]; then
      READELF_TOOL="$LLVM_READELF"
    elif command -v readelf >/dev/null 2>&1; then
      READELF_TOOL="$(command -v readelf)"
    fi

    if [ -n "$READELF_TOOL" ]; then
      local ALIGNMENT
      ALIGNMENT="$("$READELF_TOOL" -l "$OUTPUT_PATH/$LIB_NAME" 2>/dev/null | grep "LOAD" | head -1 | awk '{print $NF}' || echo "unknown")"
      if [ "$ALIGNMENT" = "0x4000" ] || [ "$ALIGNMENT" = "16384" ]; then
        print_success "16KB page alignment verified: $ALIGNMENT"
      else
        print_info "Page alignment: $ALIGNMENT (NDK handles 16KB at link time)"
      fi
    else
      print_info "Skipping alignment check (readelf not available)"
    fi
  else
    print_error "Build failed: $LIB_NAME not found"
    return 1
  fi

  echo ""
}

verify_jni_symbols_for_lib() {
  local LIB_PATH="$1"

  if [ ! -f "$LIB_PATH" ]; then
    print_error "Library not found: $LIB_PATH"
    return 1
  fi

  local EXPECTED_SYMBOLS=(
    "Java_org_torproject_arti_ArtiNative_getVersion"
    "Java_org_torproject_arti_ArtiNative_setLogCallback"
    "Java_org_torproject_arti_ArtiNative_initialize"
    "Java_org_torproject_arti_ArtiNative_startSocksProxy"
    "Java_org_torproject_arti_ArtiNative_stop"
  )

  local ALL_FOUND=true
  for SYMBOL in "${EXPECTED_SYMBOLS[@]}"; do
    local FOUND=false

    if [ -x "$LLVM_NM" ]; then
      if "$LLVM_NM" -D --defined-only "$LIB_PATH" 2>/dev/null | grep -q "$SYMBOL"; then
        FOUND=true
      fi
    elif command -v nm >/dev/null 2>&1; then
      # Fallback (may be unreliable for ELF on macOS)
      if nm -g "$LIB_PATH" 2>/dev/null | grep -q "$SYMBOL"; then
        FOUND=true
      fi
    fi

    if [ "$FOUND" = true ]; then
      print_success "  Found: $SYMBOL"
    else
      print_error "  Missing: $SYMBOL"
      ALL_FOUND=false
    fi
  done

  if [ "$ALL_FOUND" = true ]; then
    print_success "All JNI symbols verified for: $LIB_PATH"
  else
    print_error "Some JNI symbols are missing for: $LIB_PATH"
    return 1
  fi

  return 0
}

verify_jni_symbols() {
  print_header "Verifying JNI Symbols"
  print_info "Checking exported JNI symbols..."

  local FAILED=false

  for TARGET in "${TARGETS[@]}"; do
    local ABI="${ABI_MAP[$TARGET]}"
    local LIB_PATH="$JNILIBS_DIR/$ABI/libarti_android.so"
    print_info "Verifying $ABI: $LIB_PATH"
    if ! verify_jni_symbols_for_lib "$LIB_PATH"; then
      FAILED=true
    fi
  done

  if [ "$FAILED" = true ]; then
    return 1
  fi

  echo ""
}

show_summary() {
  print_header "Build Complete!"

  echo -e "${GREEN}Built libraries:${NC}"
  for TARGET in "${TARGETS[@]}"; do
    local ABI="${ABI_MAP[$TARGET]}"
    local LIB_PATH="$JNILIBS_DIR/$ABI/libarti_android.so"
    if [ -f "$LIB_PATH" ]; then
      local SIZE
      SIZE="$(du -h "$LIB_PATH" | cut -f1)"
      echo -e "  ${GREEN}$ABI:${NC} $SIZE"
    fi
  done

  echo ""
  echo -e "${GREEN}Arti version:${NC} $VERSION"
  echo -e "${GREEN}Source:${NC} https://gitlab.torproject.org/tpo/core/arti"
  echo ""
  echo -e "${GREEN}Next steps:${NC}"
  echo "  1. Test the build: ./gradlew assembleDebug"
  echo "  2. Commit the .so files: git add app/src/main/jniLibs/"
  echo ""
  echo -e "${GREEN}To update Arti version:${NC}"
  echo "  1. Edit ARTI_VERSION with new version tag (e.g., arti-v1.8.0)"
  echo "  2. Run: ./build-arti.sh --clean"
  echo ""
}

# ==============================================================================
# Main
# ==============================================================================

main() {
  print_header "Arti Android Build Script"
  echo -e "${BLUE}Building Arti for Android with 16KB page size support${NC}"
  echo -e "${BLUE}Version: $VERSION${NC}"
  echo -e "${BLUE}Architectures: ${TARGETS[*]}${NC}"
  echo ""

  check_prerequisites
  clone_or_update_arti
  setup_wrapper
  ensure_wrapper_lockfile

  for TARGET in "${TARGETS[@]}"; do
    build_for_target "$TARGET"
  done

  verify_jni_symbols
  show_summary
}

ensure_wrapper_lockfile() {
  print_header "Ensuring wrapper Cargo.lock exists"

  local WRAPPER_DIR="$ARTI_SOURCE_DIR/arti-android-wrapper"
  local LOCKFILE="$WRAPPER_DIR/Cargo.lock"

  if [ -f "$LOCKFILE" ]; then
    print_success "Cargo.lock already exists"
    echo ""
    return 0
  fi

  print_info "Cargo.lock missing; generating it once (network access may be required)..."
  (cd "$WRAPPER_DIR" && cargo generate-lockfile)

  if [ ! -f "$LOCKFILE" ]; then
    print_error "Failed to generate Cargo.lock at $LOCKFILE"
    return 1
  fi

  print_success "Generated Cargo.lock"
  echo ""
}

main
