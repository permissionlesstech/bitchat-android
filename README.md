# Claus

**Developed by Aksis**

A secure, decentralized, peer-to-peer messaging app that works over Bluetooth mesh networks. No internet required for mesh chats, no servers, no phone numbers - just pure encrypted communication.

This project is based on the original [bitchat](https://github.com/permissionlesstech/bitchat-android) by Permissionless Tech, maintaining 100% protocol compatibility for cross-platform communication.

## License

This project is released under the GNU General Public License v3.0. See the [LICENSE.md](LICENSE.md) file for details.

**Credits:** This project builds upon the excellent work of the original bitchat developers. We maintain full compatibility and respect all original contributions.

## Install Claus

You can download the latest version of Claus for Android from the [GitHub Releases page](https://github.com/republicofinternetofficial-blip/claus/releases).

**Instructions:**

1. **Download the APK:** On your Android device, navigate to the link above and download the latest `.apk` file. Open it.
2. **Allow Unknown Sources:** On some devices, before you can install the APK, you may need to enable "Install from unknown sources" in your device's settings.
3. **Install:** Open the downloaded `.apk` file to begin the installation.

## Features

- **✅ Cross-Platform Compatible**: Full protocol compatibility with iOS bitchat
- **✅ Decentralized Mesh Network**: Automatic peer discovery and multi-hop message relay over Bluetooth LE
- **✅ End-to-End Encryption**: X25519 key exchange + AES-256-GCM for private messages
- **✅ Channel-Based Chats**: Topic-based group messaging with optional password protection
- **✅ Store & Forward**: Messages cached for offline peers and delivered when they reconnect
- **✅ Privacy First**: No accounts, no phone numbers, no persistent identifiers
- **✅ IRC-Style Commands**: Familiar `/join`, `/msg`, `/who` style interface
- **✅ Message Retention**: Optional channel-wide message saving controlled by channel owners
- **✅ Emergency Wipe**: Triple-tap logo to instantly clear all data
- **✅ Modern Android UI**: Jetpack Compose with Material Design 3
- **✅ Dark/Light Themes**: Terminal-inspired aesthetic
- **✅ Battery Optimization**: Adaptive scanning and power management

## Android Setup

### Prerequisites

- **Android Studio**: Arctic Fox (2020.3.1) or newer
- **Android SDK**: API level 26 (Android 8.0) or higher
- **Kotlin**: 1.8.0 or newer
- **Gradle**: 7.0 or newer

### Build Instructions

1. **Clone the repository:**
   ```bash
   git clone https://github.com/republicofinternetofficial-blip/claus.git
   cd claus
   ```

2. **Open in Android Studio:**
   ```bash
   # Open Android Studio and select "Open an Existing Project"
   # Navigate to the claus directory
   ```

3. **Build the project:**
   ```bash
   ./gradlew build
   ```

4. **Install on device:**
   ```bash
   ./gradlew installDebug
   ```

## Usage

### Basic Commands

- `/j #channel` - Join or create a channel
- `/m @name message` - Send a private message
- `/w` - List online users
- `/channels` - Show all discovered channels
- `/block @name` - Block a peer from messaging you
- `/clear` - Clear chat messages

### Getting Started

1. **Install the app** on your Android device (requires Android 8.0+)
2. **Grant permissions** for Bluetooth and location when prompted
3. **Launch Claus** - it will auto-start mesh networking
4. **Set your nickname** or use the auto-generated one
5. **Connect automatically** to nearby devices
6. **Join a channel** or start chatting

## Security & Privacy

### Encryption
- **Private Messages**: X25519 key exchange + AES-256-GCM encryption
- **Channel Messages**: Argon2id password derivation + AES-256-GCM
- **Digital Signatures**: Ed25519 for message authenticity
- **Forward Secrecy**: New key pairs generated each session

### Privacy Features
- **No Registration**: No accounts, emails, or phone numbers required
- **Ephemeral by Default**: Messages exist only in device memory
- **Emergency Wipe**: Triple-tap logo to instantly clear all data
- **No Tracking**: Your location and data are never collected

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## Support & Issues

- **Bug Reports**: [Create an issue](https://github.com/republicofinternetofficial-blip/claus/issues)
- **Security Issues**: Please report privately

## Original Project

For iOS version and original project information, visit [bitchat iOS repository](https://github.com/jackjackbits/bitchat)
