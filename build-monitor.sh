#!/bin/bash
# BitChat Android - Intelligent Build Monitor and Reactivation System
# This script monitors the build process, reactivates if stopped, and completes the build

set -e

PROJECT_DIR="/home/openclaw/.openclaw/workspace/ikorochat-android"
LOG_FILE="$PROJECT_DIR/build-monitor.log"
STATE_FILE="$PROJECT_DIR/.build-state"
MAX_RETRIES=5
RETRY_COUNT=0

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/Android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

check_build_status() {
    # Check for APKs
    DEBUG_APKS=$(find "$PROJECT_DIR/app/build/outputs/apk/debug" -name "*.apk" 2>/dev/null | wc -l)
    RELEASE_APKS=$(find "$PROJECT_DIR/app/build/outputs/apk/release" -name "*.apk" 2>/dev/null | wc -l)
    
    echo "DEBUG_APKS=$DEBUG_APKS"
    echo "RELEASE_APKS=$RELEASE_APKS"
}

is_gradle_running() {
    pgrep -f "GradleDaemon" > /dev/null 2>&1
    return $?
}

kill_stale_gradle() {
    if is_gradle_running; then
        log "Killing stale Gradle processes..."
        pkill -9 -f "GradleDaemon" || true
        pkill -9 -f "gradlew" || true
        sleep 5
    fi
}

clean_build_cache() {
    log "Cleaning build cache..."
    cd "$PROJECT_DIR"
    rm -rf app/build/.gradle
    rm -rf .gradle/8.13/executionHistory
    rm -rf .gradle/8.13/fileHashes
    rm -rf app/build/intermediates/incremental
}

build_debug() {
    log "Building DEBUG APK..."
    cd "$PROJECT_DIR"
    _JAVA_OPTIONS="-Xmx2g" ./gradlew assembleDebug --no-daemon --max-workers=1 2>&1 | tee -a "$LOG_FILE"
    
    if [ $? -eq 0 ]; then
        log "✅ DEBUG build successful!"
        return 0
    else
        log "❌ DEBUG build failed!"
        return 1
    fi
}

build_release() {
    log "Building RELEASE APK..."
    cd "$PROJECT_DIR"
    
    # Check if keystore exists
    if [ ! -f "$PROJECT_DIR/bitchat-release.keystore" ]; then
        log "Keystore not found, generating..."
        keytool -genkeypair -v -keystore bitchat-release.keystore \
            -alias bitchat -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass "BitchatRelease2025!" -keypass "BitchatRelease2025!" \
            -dname "CN=BitChat Android, OU=Security, O=Permissionless Tech, L=Global, ST=World, C=US"
    fi
    
    _JAVA_OPTIONS="-Xmx2g" ./gradlew assembleRelease --no-daemon --max-workers=1 2>&1 | tee -a "$LOG_FILE"
    
    if [ $? -eq 0 ]; then
        log "✅ RELEASE build successful!"
        return 0
    else
        log "❌ RELEASE build failed!"
        return 1
    fi
}

verify_apk() {
    local apk_path="$1"
    if [ -f "$apk_path" ]; then
        log "Verifying APK: $apk_path"
        $ANDROID_HOME/build-tools/35.0.0/aapt dump badging "$apk_path" | head -5
        return 0
    else
        log "APK not found: $apk_path"
        return 1
    fi
}

sign_apk_manual() {
    # If automatic signing failed, sign manually
    log "Attempting manual APK signing..."
    cd "$PROJECT_DIR"
    
    UNSIGNED_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk"
    SIGNED_APK="$PROJECT_DIR/bitchat-release-arm64.apk"
    
    if [ -f "$UNSIGNED_APK" ]; then
        $ANDROID_HOME/build-tools/35.0.0/zipalign -v 4 "$UNSIGNED_APK" "$PROJECT_DIR/app-aligned.apk"
        $ANDROID_HOME/build-tools/35.0.0/apksigner sign \
            --ks "$PROJECT_DIR/bitchat-release.keystore" \
            --ks-pass pass:"BitchatRelease2025!" \
            --key-pass pass:"BitchatRelease2025!" \
            --ks-key-alias bitchat \
            --out "$SIGNED_APK" \
            "$PROJECT_DIR/app-aligned.apk"
        
        if [ -f "$SIGNED_APK" ]; then
            log "✅ Manual signing successful: $SIGNED_APK"
            return 0
        fi
    fi
    return 1
}

copy_apks_to_workspace() {
    log "Copying APKs to workspace..."
    mkdir -p "$PROJECT_DIR/builds"
    
    # Copy debug APKs
    find "$PROJECT_DIR/app/build/outputs/apk/debug" -name "*.apk" -exec cp {} "$PROJECT_DIR/builds/" \; 2>/dev/null || true
    
    # Copy release APKs
    find "$PROJECT_DIR/app/build/outputs/apk/release" -name "*.apk" -exec cp {} "$PROJECT_DIR/builds/" \; 2>/dev/null || true
    
    # Create easy-access copies
    if [ -f "$PROJECT_DIR/builds/app-arm64-v8a-debug.apk" ]; then
        cp "$PROJECT_DIR/builds/app-arm64-v8a-debug.apk" "$PROJECT_DIR/bitchat-debug-arm64.apk"
    fi
    
    if [ -f "$PROJECT_DIR/builds/app-arm64-v8a-release.apk" ]; then
        cp "$PROJECT_DIR/builds/app-arm64-v8a-release.apk" "$PROJECT_DIR/bitchat-release-arm64.apk"
    fi
}

generate_build_report() {
    log "Generating build report..."
    
    REPORT_FILE="$PROJECT_DIR/BUILD_REPORT.md"
    
    cat > "$REPORT_FILE" << EOF
# BitChat Android Build Report

Generated: $(date)

## Build Status

### Debug APKs
$(find "$PROJECT_DIR/builds" -name "*debug*.apk" -exec ls -lh {} \; 2>/dev/null)

### Release APKs
$(find "$PROJECT_DIR/builds" -name "*release*.apk" -exec ls -lh {} \; 2>/dev/null)

## Keystore Information

**File:** bitchat-release.keystore
**Alias:** bitchat
**Password:** BitchatRelease2025!
**Validity:** 10000 days

## Installation Instructions

1. Download the appropriate APK for your device:
   - \`bitchat-debug-arm64.apk\` - For testing on most modern phones
   - \`bitchat-release-arm64.apk\` - For production use (if available)

2. Enable "Install from unknown sources" in your phone settings

3. Open the APK file to install

4. Grant Bluetooth and Location permissions when prompted

## Build Logs

See: build-monitor.log

EOF

    log "Build report saved to: $REPORT_FILE"
}

# Main execution
main() {
    log "=========================================="
    log "BitChat Android Build Monitor Started"
    log "=========================================="
    
    # Check current state
    STATUS=$(check_build_status)
    log "Current state: $STATUS"
    
    # Parse status
    DEBUG_COUNT=$(echo "$STATUS" | grep "DEBUG_APKS" | cut -d= -f2)
    RELEASE_COUNT=$(echo "$STATUS" | grep "RELEASE_APKS" | cut -d= -f2)
    
    # Build debug if needed
    if [ "$DEBUG_COUNT" -eq 0 ] 2>/dev/null; then
        log "Debug APKs not found, building..."
        kill_stale_gradle
        clean_build_cache
        
        if ! build_debug; then
            log "Debug build failed, retrying with fresh environment..."
            kill_stale_gradle
            sleep 10
            build_debug || log "Debug build failed after retry"
        fi
    else
        log "Debug APKs already exist: $DEBUG_COUNT files"
    fi
    
    # Build release
    log "Building release APK..."
    kill_stale_gradle
    sleep 5
    
    if ! build_release; then
        log "Release build failed, attempting manual signing..."
        sign_apk_manual || log "Manual signing also failed"
    fi
    
    # Copy APKs
    copy_apks_to_workspace
    
    # Generate report
    generate_build_report
    
    # Final status
    log "=========================================="
    log "Final Build Status:"
    find "$PROJECT_DIR/builds" -name "*.apk" -exec ls -lh {} \; 2>/dev/null | tee -a "$LOG_FILE"
    log "=========================================="
    
    # Check if successful
    FINAL_RELEASE=$(find "$PROJECT_DIR/builds" -name "*release*.apk" 2>/dev/null | wc -l)
    if [ "$FINAL_RELEASE" -gt 0 ]; then
        log "✅ BUILD COMPLETE - Release APKs available!"
        echo "COMPLETE" > "$STATE_FILE"
        exit 0
    else
        log "⚠️ Build incomplete - check logs"
        echo "INCOMPLETE" > "$STATE_FILE"
        exit 1
    fi
}

# Run main
main "$@"