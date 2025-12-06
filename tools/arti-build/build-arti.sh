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
#   - Rust toolchain with Android targets: rustup target add aarch64-linux-android x86_64-linux-android
#   - cargo-ndk: cargo install cargo-ndk
#   - Android NDK 25+ (for 16KB page size support)
#   - Bash 4+ (for associative arrays)
#
# Usage:
#   ./build-arti.sh              # Build both architectures (debug/emulator)
#   ./build-arti.sh --release    # Build ARM64 only (production)
#   ./build-arti.sh --clean      # Remove cloned Arti repo and rebuild
#

set -e  # Exit on error
set -u  # Exit on undefined variable

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

# Read pinned version
if [ ! -f "$SCRIPT_DIR/ARTI_VERSION" ]; then
    echo -e "${RED}Error: ARTI_VERSION file not found${NC}"
    exit 1
fi
VERSION=$(cat "$SCRIPT_DIR/ARTI_VERSION" | tr -d '[:space:]')

# Android NDK path
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"

# Min SDK version (must match bitchat-android minSdk)
MIN_SDK_VERSION=26

# Parse arguments
RELEASE_ONLY=false
CLEAN_BUILD=false

while [[ $# -gt 0 ]]; do
    case $1 in
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
            shift
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

# ==============================================================================
# Functions
# ==============================================================================

print_header() {
    echo -e "${BLUE}=========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}=========================================${NC}"
}

print_success() {
    echo -e "${GREEN}$1${NC}"
}

print_error() {
    echo -e "${RED}$1${NC}"
}

print_info() {
    echo -e "${YELLOW}$1${NC}"
}

check_prerequisites() {
    print_header "Checking Prerequisites"

    # Check Rust
    if ! command -v rustc &> /dev/null; then
        print_error "Rust is not installed. Install from https://rustup.rs/"
        exit 1
    fi
    print_success "Rust found: $(rustc --version)"

    # Check cargo-ndk
    if ! command -v cargo-ndk &> /dev/null; then
        print_error "cargo-ndk is not installed. Run: cargo install cargo-ndk"
        exit 1
    fi
    print_success "cargo-ndk found: $(cargo-ndk --version 2>/dev/null || echo 'installed')"

    # Check NDK
    if [ ! -d "$ANDROID_NDK_HOME" ]; then
        print_error "Android NDK not found at: $ANDROID_NDK_HOME"
        print_info "Set ANDROID_NDK_HOME environment variable to your NDK location"
        exit 1
    fi
    print_success "Android NDK found: $ANDROID_NDK_HOME"

    # Check NDK version (should be 25+)
    NDK_VERSION=$(basename "$ANDROID_NDK_HOME" | cut -d'.' -f1)
    if [ "$NDK_VERSION" -lt 25 ]; then
        print_error "NDK version $NDK_VERSION is too old. NDK 25+ required for 16KB page size support"
        exit 1
    fi
    print_success "NDK version: $NDK_VERSION (supports 16KB page size)"

    # Check Android targets
    for TARGET in "${TARGETS[@]}"; do
        if ! rustup target list | grep -q "$TARGET (installed)"; then
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

    # Fetch latest tags and checkout pinned version
    print_info "Checking out version: $VERSION"
    git fetch --tags --quiet
    git checkout "$VERSION" --quiet 2>/dev/null || {
        print_error "Version $VERSION not found. Available versions:"
        git tag | grep "^arti-v" | tail -10
        exit 1
    }

    print_success "Arti source ready at version $VERSION"
    echo ""
}

setup_wrapper() {
    print_header "Setting up JNI Wrapper"

    # Create wrapper directory in cloned repo
    WRAPPER_DIR="$ARTI_SOURCE_DIR/arti-android-wrapper"
    mkdir -p "$WRAPPER_DIR/src"

    # Copy our wrapper files
    cp "$SCRIPT_DIR/src/lib.rs" "$WRAPPER_DIR/src/"
    cp "$SCRIPT_DIR/Cargo.toml" "$WRAPPER_DIR/"

    print_success "Wrapper files copied to $WRAPPER_DIR"
    echo ""
}

build_for_target() {
    local TARGET=$1
    local ABI="${ABI_MAP[$TARGET]}"
    local OUTPUT_PATH="$JNILIBS_DIR/$ABI"

    print_header "Building for $ABI ($TARGET)"

    mkdir -p "$OUTPUT_PATH"

    # Build with cargo-ndk
    print_info "Building Arti Android wrapper..."
    cargo ndk \
        -t "$TARGET" \
        --platform "$MIN_SDK_VERSION" \
        -o "$OUTPUT_PATH" \
        build --release \
        --manifest-path "$ARTI_SOURCE_DIR/arti-android-wrapper/Cargo.toml"

    # cargo-ndk creates nested ABI directory, flatten it
    local LIB_NAME="libarti_android.so"
    local NESTED_PATH="$OUTPUT_PATH/$ABI/$LIB_NAME"

    if [ -f "$NESTED_PATH" ]; then
        mv "$NESTED_PATH" "$OUTPUT_PATH/$LIB_NAME"
        rmdir "$OUTPUT_PATH/$ABI" 2>/dev/null || true
    fi

    if [ -f "$OUTPUT_PATH/$LIB_NAME" ]; then
        print_success "Built: $OUTPUT_PATH/$LIB_NAME"

        # Strip debug symbols
        print_info "Stripping debug symbols..."
        local STRIP_TOOL="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
        if [ -f "$STRIP_TOOL" ]; then
            "$STRIP_TOOL" "$OUTPUT_PATH/$LIB_NAME" 2>/dev/null || true
            print_success "Stripped debug symbols"
        fi

        # Show final size
        local SIZE=$(du -h "$OUTPUT_PATH/$LIB_NAME" | cut -f1)
        print_success "Final size: $SIZE"

        # Verify 16KB page size alignment
        print_info "Verifying 16KB page alignment..."
        if command -v readelf &> /dev/null; then
            local ALIGNMENT=$(readelf -l "$OUTPUT_PATH/$LIB_NAME" 2>/dev/null | grep "LOAD" | head -1 | awk '{print $NF}' || echo "unknown")
            if [ "$ALIGNMENT" = "0x4000" ] || [ "$ALIGNMENT" = "16384" ]; then
                print_success "16KB page alignment verified: $ALIGNMENT"
            else
                print_info "Page alignment: $ALIGNMENT (NDK handles 16KB at link time)"
            fi
        fi
    else
        print_error "Build failed: $LIB_NAME not found"
        return 1
    fi

    echo ""
}

verify_jni_symbols() {
    print_header "Verifying JNI Symbols"

    local LIB_PATH="$JNILIBS_DIR/arm64-v8a/libarti_android.so"

    if [ ! -f "$LIB_PATH" ]; then
        print_error "Library not found: $LIB_PATH"
        return 1
    fi

    print_info "Checking exported JNI symbols..."

    local EXPECTED_SYMBOLS=(
        "Java_org_torproject_arti_ArtiNative_getVersion"
        "Java_org_torproject_arti_ArtiNative_setLogCallback"
        "Java_org_torproject_arti_ArtiNative_initialize"
        "Java_org_torproject_arti_ArtiNative_startSocksProxy"
        "Java_org_torproject_arti_ArtiNative_stop"
    )

    local ALL_FOUND=true
    for SYMBOL in "${EXPECTED_SYMBOLS[@]}"; do
        if nm -gU "$LIB_PATH" 2>/dev/null | grep -q "$SYMBOL"; then
            print_success "  Found: $SYMBOL"
        else
            print_error "  Missing: $SYMBOL"
            ALL_FOUND=false
        fi
    done

    if [ "$ALL_FOUND" = true ]; then
        print_success "All JNI symbols verified!"
    else
        print_error "Some JNI symbols are missing!"
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
            local SIZE=$(du -h "$LIB_PATH" | cut -f1)
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

    # Build for each architecture
    for TARGET in "${TARGETS[@]}"; do
        build_for_target "$TARGET"
    done

    verify_jni_symbols
    show_summary
}

# Run main function
main
