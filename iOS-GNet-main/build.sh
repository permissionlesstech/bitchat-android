#!/bin/bash

echo "🔨 Building BitChat..."
echo "====================="

# Ensure we're using full Xcode
if [[ "$(xcode-select --print-path)" != *"/Applications/Xcode.app"* ]]; then
    echo "⚠️  Switching to full Xcode..."
    sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
fi

# Generate project
echo "📝 Generating Xcode project..."
xcodegen generate

# Build iOS
echo "📱 Building iOS version..."
xcodebuild -project bitchat.xcodeproj -scheme "bitchat (iOS)" -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build | tail -5

# Build macOS
echo "💻 Building macOS version..."
xcodebuild -project bitchat.xcodeproj -scheme "bitchat (macOS)" -configuration Debug build | tail -5

echo ""
echo "✅ Build complete!"
echo ""
echo "To run:"
echo "  iOS: Open in Xcode and run on simulator/device"
echo "  macOS: open ~/Library/Developer/Xcode/DerivedData/bitchat-*/Build/Products/Debug/bitchat.app" 