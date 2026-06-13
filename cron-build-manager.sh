#!/bin/bash
# BitChat Android - Cron Job Manager for Build Reactivation
# This manages the cron job that monitors and reactivates the build process

PROJECT_DIR="/home/openclaw/.openclaw/workspace/ikorochat-android"
CRON_MARKER="# BITCHAT_BUILD_MONITOR"
BUILD_MONITOR_SCRIPT="$PROJECT_DIR/build-monitor.sh"
CRON_LOG="$PROJECT_DIR/cron-reactivation.log"

setup_cron() {
    echo "[$(date)] Setting up build monitor cron job..."
    
    # Create cron entry that runs every 5 minutes
    CRON_ENTRY="*/5 * * * * $BUILD_MONITOR_SCRIPT >> $CRON_LOG 2>&1 $CRON_MARKER"
    
    # Check if cron already exists
    if crontab -l 2>/dev/null | grep -q "BITCHAT_BUILD_MONITOR"; then
        echo "Cron job already exists, skipping setup"
        return 0
    fi
    
    # Add to crontab
    (crontab -l 2>/dev/null; echo "$CRON_ENTRY") | crontab -
    
    echo "✅ Cron job installed - will check build every 5 minutes"
    echo "Log file: $CRON_LOG"
}

remove_cron() {
    echo "[$(date)] Removing build monitor cron job..."
    
    # Remove our cron entry
    crontab -l 2>/dev/null | grep -v "BITCHAT_BUILD_MONITOR" | crontab -
    
    echo "✅ Cron job removed"
}

check_build_status() {
    STATE_FILE="$PROJECT_DIR/.build-state"
    
    if [ -f "$STATE_FILE" ]; then
        STATE=$(cat "$STATE_FILE")
        echo "Build state: $STATE"
        
        if [ "$STATE" = "COMPLETE" ]; then
            echo "✅ Build is complete!"
            return 0
        fi
    fi
    
    # Check for APKs directly
    RELEASE_APKS=$(find "$PROJECT_DIR/builds" -name "*release*.apk" 2>/dev/null | wc -l)
    if [ "$RELEASE_APKS" -gt 0 ]; then
        echo "✅ Release APKs found!"
        return 0
    fi
    
    echo "Build not complete yet"
    return 1
}

force_build() {
    echo "[$(date)] Force starting build process..."
    "$BUILD_MONITOR_SCRIPT"
}

show_status() {
    echo "=========================================="
    echo "BitChat Android Build Status"
    echo "=========================================="
    echo ""
    
    # Check cron
    if crontab -l 2>/dev/null | grep -q "BITCHAT_BUILD_MONITOR"; then
        echo "Cron Job: ✅ Active"
    else
        echo "Cron Job: ❌ Not active"
    fi
    
    echo ""
    echo "APKs Available:"
    find "$PROJECT_DIR/builds" -name "*.apk" -exec ls -lh {} \; 2>/dev/null || echo "No APKs found"
    
    echo ""
    echo "Recent Log Entries:"
    tail -20 "$PROJECT_DIR/build-monitor.log" 2>/dev/null || echo "No log file"
    
    echo ""
    check_build_status
    echo "=========================================="
}

# Main command handler
case "${1:-status}" in
    setup)
        setup_cron
        ;;
    remove|cleanup)
        remove_cron
        ;;
    status)
        show_status
        ;;
    force)
        force_build
        ;;
    check)
        if check_build_status; then
            echo "Build complete, cleaning up cron..."
            remove_cron
        else
            echo "Build incomplete, cron will continue monitoring"
        fi
        ;;
    *)
        echo "Usage: $0 {setup|remove|status|force|check}"
        echo ""
        echo "Commands:"
        echo "  setup   - Install cron job to monitor build"
        echo "  remove  - Remove cron job"
        echo "  status  - Show current build status"
        echo "  force   - Force start the build now"
        echo "  check   - Check if build complete, cleanup if done"
        ;;
esac