# BitChat Android - Comprehensive Codebase Analysis

## Executive Summary

**BitChat Android** is a sophisticated, secure, decentralized messaging application built with modern Android technologies. It implements a Bluetooth mesh network for peer-to-peer communication while maintaining 100% protocol compatibility with the iOS version. The app features end-to-end encryption, Tor integration for privacy, Nostr protocol support for geohash-based location channels, and a polished Jetpack Compose UI.

**Repository:** `git@github.com:Satoshi-NaAkokwa/ikorochat-android.git`
**Note:** Repository name is "ikorochat-android" but contains the "BitChat Android" app (package: `com.bitchat.droid`)

---

## 1. Project Architecture

### 1.1 Technology Stack

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| **Language** | Kotlin | 2.2.0 | Primary development language |
| **UI Framework** | Jetpack Compose | BOM 2025.06.01 | Declarative UI |
| **Design System** | Material Design 3 | Latest | Modern UI components |
| **Build System** | Gradle | 8.13 | Build automation |
| **Android Gradle Plugin** | AGP | 8.10.1 | Android build configuration |
| **Min SDK** | Android | 8.0 (API 26) | Minimum supported version |
| **Target SDK** | Android | 15 (API 35) | Target version |
| **Compile SDK** | Android | 15 (API 35) | Compilation target |

### 1.2 Core Architecture Patterns

1. **MVVM (Model-View-ViewModel)** - Separation of concerns for UI logic
2. **Repository Pattern** - Data layer abstraction
3. **Coordinator Pattern** - Complex navigation flows
4. **Service-Based Architecture** - Background mesh networking
5. **Protocol Layer** - Binary protocol handling

### 1.3 Project Structure

```
app/src/main/
├── java/com/bitchat/android/
│   ├── BitchatApplication.kt           # Application class
│   ├── MainActivity.kt                 # Main activity
│   ├── MainViewModel.kt                # Global state
│   │
│   ├── mesh/                           # Bluetooth mesh networking
│   │   ├── BluetoothMeshService.kt     # Core mesh coordinator
│   │   ├── BluetoothConnectionManager.kt  # Connection lifecycle
│   │   ├── BluetoothGattClientManager.kt  # GATT client role
│   │   ├── BluetoothGattServerManager.kt  # GATT server role
│   │   ├── BluetoothPacketBroadcaster.kt  # Packet transmission
│   │   ├── BluetoothConnectionTracker.kt  # Connection state tracking
│   │   ├── BluetoothPermissionManager.kt  # Permission handling
│   │   ├── PeerManager.kt              # Peer state management
│   │   ├── SecurityManager.kt          # Security & deduplication
│   │   ├── StoreForwardManager.kt      # Offline message caching
│   │   ├── MessageHandler.kt           # Message processing
│   │   ├── PacketProcessor.kt          # Incoming packet routing
│   │   ├── PacketRelayManager.kt       # Relay logic
│   │   ├── PeerFingerprintManager.kt   # Peer identity
│   │   ├── PowerManager.kt             # Battery optimization
│   │   ├── FragmentManager.kt          # Message fragmentation
│   │   └── TransferProgressManager.kt  # File transfer tracking
│   │
│   ├── protocol/                       # Binary protocol
│   │   ├── BinaryProtocol.kt           # Packet encoding/decoding
│   │   ├── CompressionUtil.kt          # LZ4 compression
│   │   └── MessagePadding.kt           # Padding for security
│   │
│   ├── noise/                          # Noise Protocol implementation
│   │   ├── NoiseSession.kt             # Session management
│   │   ├── NoiseSessionManager.kt      # Session lifecycle
│   │   ├── NoiseChannelEncryption.kt   # Channel encryption
│   │   └── NoiseEncryptionService.kt   # Encryption service
│   │
│   ├── crypto/                         # Cryptography
│   │   └── EncryptionService.kt        # X25519/Ed25519 operations
│   │
│   ├── model/                          # Data models
│   │   ├── BitchatMessage.kt           # Message model
│   │   ├── BitchatFilePacket.kt        # File transfer model
│   │   ├── FragmentPayload.kt          # Fragmentation model
│   │   ├── IdentityAnnouncement.kt     # Peer announcement
│   │   ├── NoiseEncrypted.kt           # Encrypted payload
│   │   ├── RequestSyncPacket.kt        # Sync request
│   │   └── RoutedPacket.kt             # Routing wrapper
│   │
│   ├── nostr/                          # Nostr protocol client
│   │   ├── NostrProtocol.kt            # NIP implementation
│   │   ├── NostrEvent.kt               # Event model
│   │   ├── NostrIdentity.kt            # Identity management
│   │   ├── NostrRelayManager.kt        # Relay connections
│   │   ├── NostrTransport.kt           # Network transport
│   │   ├── NostrSubscriptionManager.kt # Subscription management
│   │   ├── NostrRequest.kt             # Request handling
│   │   ├── NostrCrypto.kt              # Cryptographic operations
│   │   ├── NostrDirectMessageHandler.kt  # DM handling
│   │   ├── NostrEmbeddedBitChat.kt     # Embedded BitChat messages
│   │   ├── NostrEventDeduplicator.kt   # Event deduplication
│   │   ├── NostrProofOfWork.kt         # PoW mining
│   │   ├── PoWPreferenceManager.kt     # PoW settings
│   │   ├── GeohashMessageHandler.kt    # Geohash message handling
│   │   ├── GeohashAliasRegistry.kt     # Alias persistence
│   │   ├── GeohashConversationRegistry.kt  # Conversation metadata
│   │   ├── LocationNotesManager.kt     # Location note management
│   │   ├── LocationNotesInitializer.kt # Initialization
│   │   ├── LocationNotesSheetPresenter.kt  # UI presentation
│   │   ├── RelayDirectory.kt           # Relay discovery
│   │   └── Bech32.kt                   # Address encoding
│   │
│   ├── net/                            # Network layer
│   │   ├── ArtiTorManager.kt           # Tor integration (Arti)
│   │   ├── TorMode.kt                  # Tor mode enum
│   │   ├── TorPreferenceManager.kt     # Tor settings
│   │   └── OkHttpProvider.kt           # HTTP client
│   │
│   ├── geohash/                        # Location features
│   │   ├── Geohash.kt                  # Geohash encoding
│   │   ├── LocationChannel.kt          # Channel model
│   │   ├── LocationChannelManager.kt   # Channel management
│   │   ├── FusedLocationProvider.kt    # Google Play Location
│   │   ├── SystemLocationProvider.kt   # System location
│   │   ├── LocationProvider.kt         # Provider interface
│   │   ├── GeocoderProvider.kt         # Geocoding interface
│   │   ├── AndroidGeocoderProvider.kt  # Android geocoder
│   │   ├── OpenStreetMapGeocoderProvider.kt  # OSM geocoder
│   │   ├── GeocoderFactory.kt          # Geocoder selection
│   │   └── GeohashBookmarksStore.kt    # Bookmarks persistence
│   │
│   ├── sync/                           # Sync protocol
│   │   ├── GossipSyncManager.kt        # Gossip-based sync
│   │   ├── GCSFilter.kt                # Counting Bloom filter
│   │   ├── PacketIdUtil.kt             # Packet ID utilities
│   │   └── SyncDefaults.kt             # Default sync settings
│   │
│   ├── services/                       # Background services
│   │   ├── MeshForegroundService.kt    # Foreground service
│   │   ├── MeshServiceHolder.kt        # Service lifecycle
│   │   ├── MeshServicePreferences.kt   # Service settings
│   │   ├── BootCompletedReceiver.kt    # Boot receiver
│   │   ├── AppShutdownCoordinator.kt   # Shutdown coordination
│   │   ├── AppStateStore.kt            # App state persistence
│   │   ├── MessageRetentionService.kt  # Message retention
│   │   ├── MessageRouter.kt            # Message routing
│   │   ├── NicknameProvider.kt         # Nickname resolution
│   │   ├── VerificationService.kt      # Peer verification
│   │   ├── SeenMessageStore.kt         # Seen message tracking
│   │   ├── ConversationAliasResolver.kt  # Alias resolution
│   │   ├── meshgraph/
│   │   │   ├── MeshGraphService.kt     # Topology discovery
│   │   │   ├── RoutePlanner.kt         # Route calculation
│   │   │   └── GossipTLV.kt            # Gossip TLV encoding
│   │   └── favorites/
│   │       └── FavoritesPersistenceService.kt  # Favorites persistence
│   │
│   ├── ui/                             # UI components
│   │   ├── ChatScreen.kt               # Main chat UI
│   │   ├── ChatViewModel.kt            # Chat state management
│   │   ├── ChatState.kt                # Chat state model
│   │   ├── ChatHeader.kt               # Chat header
│   │   ├── ChatUIConstants.kt          # UI constants
│   │   ├── ChatUIUtils.kt              # UI utilities
│   │   ├── ChatViewModelUtils.kt       # ViewModel utilities
│   │   ├── CommandProcessor.kt         # IRC-style commands
│   │   ├── DataManager.kt              # Data management
│   │   ├── MessageManager.kt           # Message management
│   │   ├── MessageSpecialParser.kt     # Special message parsing
│   │   ├── ChannelManager.kt           # Channel management
│   │   ├── PrivateChatManager.kt       # Private chat management
│   │   ├── MeshDelegateHandler.kt      # Mesh delegate
│   │   ├── MeshPeerListSheet.kt        # Peer list sheet
│   │   ├── ChatUserSheet.kt            # User sheet
│   │   ├── AboutSheet.kt               # About sheet
│   │   ├── SecurityVerificationSheet.kt  # Verification sheet
│   │   ├── VerificationSheet.kt        # Verification UI
│   │   ├── VerificationHandler.kt      # Verification logic
│   │   ├── NotificationManager.kt      # Notifications
│   │   ├── NotificationTextUtils.kt   # Notification text
│   │   ├── OrientationAwareActivity.kt  # Orientation handling
│   │   ├── InputComponents.kt          # Input field components
│   │   ├── VoiceInputComponents.kt     # Voice input
│   │   ├── MessageComponents.kt        # Message display
│   │   ├── LocationChannelsSheet.kt    # Location channels
│   │   ├── LocationNotesSheet.kt       # Location notes
│   │   ├── LocationNotesButton.kt      # Location notes button
│   │   ├── GeohashViewModel.kt         # Geohash state
│   │   ├── GeohashPeopleList.kt        # People list
│   │   ├── PoWStatusIndicator.kt       # PoW status
│   │   ├── MediaSendingManager.kt      # Media sending
│   │   ├── FileShareDispatcher.kt      # File share dispatch
│   │   ├── MatrixEncryptionAnimation.kt  # Animation
│   │   ├── LinkPreviewPill.kt          # Link preview
│   │   ├── debug/
│   │   │   ├── DebugSettingsSheet.kt   # Debug settings
│   │   │   ├── DebugSettingsManager.kt  # Debug manager
│   │   │   ├── DebugPreferenceManager.kt  # Debug preferences
│   │   │   └── MeshGraph.kt            # Mesh graph visualization
│   │   └── media/                      # Media components
│   │       ├── ImageMessageItem.kt     # Image display
│   │       ├── FileMessageItem.kt      # File display
│   │       ├── AudioMessageItem.kt     # Audio display
│   │       ├── VoiceNotePlayer.kt      # Voice playback
│   │       ├── VoiceRecorder.kt        # Voice recording
│   │       ├── VoiceVisualizer.kt      # Voice visualization
│   │       ├── Waveform.kt             # Waveform rendering
│   │       ├── WaveformViews.kt        # Waveform views
│   │       ├── RealtimeScrollingWaveform.kt  # Scrolling waveform
│   │       ├── FileViewerDialog.kt     # File viewer
│   │       ├── FullScreenImageViewer.kt  # Image viewer
│   │       ├── BlockRevealImage.kt     # Reveal animation
│   │       ├── ImagePickerButton.kt    # Image picker
│   │       ├── FilePickerButton.kt     # File picker
│   │       ├── FileSendingAnimation.kt  # Send animation
│   │       └── MediaPickerOptions.kt   # Picker options
│   │
│   ├── theme/                          # Theme system
│   │   ├── Theme.kt                    # Theme definitions
│   │   ├── ThemePreference.kt          # Theme preferences
│   │   └── Typography.kt               # Typography
│   │
│   ├── onboarding/                     # Onboarding flow
│   │   ├── OnboardingCoordinator.kt    # Onboarding orchestration
│   │   ├── OnboardingState.kt          # Onboarding state
│   │   ├── PermissionManager.kt        # Permission handling
│   │   ├── PermissionExplanationScreen.kt  # Permission explanations
│   │   ├── BluetoothCheckScreen.kt     # Bluetooth check
│   │   ├── BluetoothStatusManager.kt   # Bluetooth status
│   │   ├── LocationCheckScreen.kt      # Location check
│   │   ├── LocationStatusManager.kt    # Location status
│   │   ├── BatteryOptimizationScreen.kt  # Battery optimization
│   │   ├── BatteryOptimizationManager.kt  # Battery status
│   │   ├── BackgroundLocationPermissionScreen.kt  # Background location
│   │   ├── BackgroundLocationPreferenceManager.kt  # Background location settings
│   │   └── InitializingScreen.kt       # Initialization screen
│   │
│   ├── features/                       # Feature modules
│   │   ├── voice/                      # Voice features
│   │   │   ├── VoiceRecorder.kt        # Voice recording
│   │   │   ├── VoiceVisualizer.kt      # Voice visualization
│   │   │   └── Waveform.kt             # Waveform data
│   │   ├── media/                      # Media features
│   │   │   ├── ImageUtils.kt           # Image utilities
│   │   │   └── FileUtils.kt            # File utilities
│   │   └── file/                       # File features
│   │       └── FileUtils.kt            # File operations
│   │
│   ├── utils/                          # Utilities
│   │   ├── AppConstants.kt             # App constants
│   │   ├── BinaryEncodingUtils.kt      # Binary encoding
│   │   ├── ByteArrayExtensions.kt      # ByteArray extensions
│   │   └── ByteArrayWrapper.kt         # ByteArray wrapper
│   │
│   ├── util/                           # Utilities (legacy)
│   │   ├── DeviceUtils.kt              # Device utilities
│   │   └── NotificationIntervalManager.kt  # Notification intervals
│   │
│   └── identity/                       # Identity management
│       └── SecureIdentityStateManager.kt  # Secure identity state
│
├── res/                                # Resources
│   ├── drawable/                       # Drawables
│   ├── layout/                         # XML layouts (minimal)
│   ├── values/                         # Values (strings, colors, etc.)
│   ├── xml/                            # XML resources (providers, etc.)
│   └── assets/                         # Assets (Nostr relays, etc.)
│
├── jniLibs/                            # Native libraries
│   ├── arm64-v8a/                      # ARM 64-bit (Tor libraries)
│   ├── armeabi-v7a/                    # ARM 32-bit
│   ├── x86/                            # x86 emulator
│   └── x86_64/                         # x86_64 emulator
│
└── AndroidManifest.xml                 # Manifest
```

---

## 2. Protocol Implementation

### 2.1 Binary Protocol (BitChat Protocol)

**Purpose:** Efficient, low-latency messaging over Bluetooth LE

**Version Support:**
- Version 1: Legacy (2-byte payload length)
- Version 2: Current (4-byte payload length + Source-Based Routing)

**Packet Structure (v2):**
```
[Header: 16 bytes] [SenderID: 8B] [RecipientID: 8B] [Route: N*8B] [Payload: Variable] [Signature: 64B]
```

**Header Fields:**
- Version: 1 byte (0x01 or 0x02)
- Type: 1 byte (message type)
- TTL: 1 byte (time-to-live, max 7 hops)
- Timestamp: 8 bytes (UInt64, big-endian)
- Flags: 1 byte (HAS_RECIPIENT, HAS_SIGNATURE, IS_COMPRESSED, HAS_ROUTE)
- Payload Length: 4 bytes (UInt32, big-endian, v2)

**Message Types:**
- `ANNOUNCE (0x01)` - Peer presence announcement
- `MESSAGE (0x02)` - User messages (private and broadcast)
- `LEAVE (0x03)` - Peer departure notification
- `NOISE_HANDSHAKE (0x10)` - Noise protocol handshake
- `NOISE_ENCRYPTED (0x11)` - Encrypted transport message
- `FRAGMENT (0x20)` - Message fragmentation
- `REQUEST_SYNC (0x21)` - GCS-based sync request
- `FILE_TRANSFER (0x22)` - File transfer (images, audio, files)

**Special Features:**
- Fragmentation for messages >150 bytes (BLE MTU limit)
- Compression with LZ4 for messages >100 bytes
- Source-Based Routing (v2) for efficient unicast
- Ed25519 signatures for authenticity
- Message padding for traffic analysis resistance

**Cross-Platform Compatibility:**
- 100% binary protocol compatible with iOS BitChat
- Identical UUIDs for BLE services and characteristics
- Same encryption algorithms and key exchange

### 2.2 Noise Protocol Implementation

**Purpose:** Secure key exchange and encrypted transport

**Pattern:** NK (One-way ephemeral static key pattern)

**Cryptographic Primitives:**
- X25519 for ECDH key agreement
- Ed25519 for digital signatures
- AES-256-GCM for symmetric encryption
- HKDF for key derivation

**Session Management:**
- `NoiseSession` - Individual session state
- `NoiseSessionManager` - Session lifecycle
- `NoiseChannelEncryption` - Channel encryption
- `NoiseEncryptionService` - Encryption operations

**Security Features:**
- Forward secrecy (ephemeral keys)
- Perfect forward secrecy
- Message authentication
- Replay protection

### 2.3 Nostr Protocol Implementation

**Purpose:** Geohash-based location channels over internet

**Implemented NIPs:**
- **NIP-01:** Basic protocol
- **NIP-02:** Contact list
- **NIP-17:** Private direct messages (gift-wrap)
- **NIP-42:** Authentication (challenge-response)

**Event Types:**
- `kind 1` - Geohash-scoped text notes
- `kind 4` - Encrypted direct messages (legacy)
- `kind 13` - Sealed events
- `kind 14` - Private messages (rumor)
- `kind 1059` - Gift-wrapped events
- `kind 20000` - Ephemeral geohash messages
- `kind 20001` - Geohash presence events

**Key Components:**
- `NostrProtocol` - NIP implementations
- `NostrEvent` - Event model
- `NostrIdentity` - Identity management (npub/nsec)
- `NostrRelayManager` - Relay connections
- `NostrTransport` - Network transport (via Tor)
- `NostrSubscriptionManager` - Subscription management

**Security Features:**
- NIP-17 gift-wrap for private messages
- Proof of Work mining (optional, configurable)
- Tor integration for relay connections
- Event deduplication
- Bech32 encoding for addresses

### 2.4 Sync Protocol (Gossip-Based)

**Purpose:** Efficient state synchronization across mesh

**Components:**
- `GossipSyncManager` - Sync coordination
- `GCSFilter` - Counting Bloom filter for deduplication
- `PacketIdUtil` - Packet ID utilities
- `MeshGraphService` - Topology discovery

**Sync Features:**
- Bloom filter-based deduplication
- Configurable capacity and false positive rate
- Gossip-based state propagation
- Two-way edge verification
- Source-based route planning

---

## 3. Cryptography & Security

### 3.1 Cryptographic Libraries

| Library | Version | Purpose |
|---------|---------|---------|
| BouncyCastle | 1.70 | X25519, Ed25519, AES-GCM |
| Google Tink | 1.10.0 | Additional crypto primitives |

### 3.2 Key Management

**Identity Keys:**
- X25519 static key pair (for key exchange)
- Ed25519 signing key pair (for signatures)
- Keypairs generated on first launch
- Stored in `EncryptedSharedPreferences`

**Session Keys:**
- Ephemeral X25519 key pair per session
- Derived via Noise Protocol
- Rotated for forward secrecy

**Nostr Keys:**
- Separate Ed25519 key pair for Nostr
- Npub/nsec encoding for sharing
- Persisted encrypted

### 3.3 Encryption Flow

**Direct Messages:**
1. Noise handshake (NK pattern)
2. Derive shared secret
3. Establish encrypted channel
4. AES-256-GCM encryption for messages

**Channel Messages:**
1. Channel owner sets password
2. Argon2id key derivation
3. AES-256-GCM encryption
4. Password-based decryption

**File Transfers:**
1. Generate random AES-256 key
2. Encrypt file with AES-256-GCM
3. Share key via encrypted channel
4. Decrypt on recipient side

### 3.4 Security Features

- **End-to-End Encryption:** All messages encrypted
- **Forward Secrecy:** Ephemeral session keys
- **Message Signatures:** Ed25519 for authenticity
- **Padding:** Random padding for traffic analysis resistance
- **Deduplication:** Bloom filter prevents replay attacks
- **Tor Integration:** Optional Tor for Nostr relay connections
- **Emergency Wipe:** Triple-tap logo to clear all data

---

## 4. Networking Stack

### 4.1 Bluetooth Mesh Networking

**Architecture:**
- Dual-role BLE (central + peripheral)
- Automatic peer discovery
- Multi-hop message relay (max 7 hops)
- Store-and-forward for offline peers

**Components:**
- `BluetoothConnectionManager` - Connection lifecycle
- `BluetoothGattClientManager` - GATT client operations
- `BluetoothGattServerManager` - GATT server operations
- `BluetoothPacketBroadcaster` - Packet transmission
- `PeerManager` - Peer state management

**Connection Management:**
- Adaptive connection limits based on power mode
- Connection state tracking
- Automatic reconnection
- Connection quality monitoring (RSSI)

**Power Optimization:**
- Adaptive scanning intervals
- Power mode-based connection limits
- Background duty cycling
- Battery-aware operation modes

### 4.2 Tor Integration (Arti)

**Implementation:** Rust-based Tor client (Arti) bridged to Android

**Components:**
- `ArtiTorManager` - Tor lifecycle management
- `TorMode` - Tor mode configuration
- `TorPreferenceManager` - Tor settings
- Native libraries in `jniLibs/`

**Tor Features:**
- SOCKS5 proxy for Nostr relay connections
- Circuit management
- Identity isolation
- Configurable relay selection

**Tor Modes:**
- `Disabled` - No Tor
- `Enabled` - Tor for all Nostr connections
- `OnionOnly` - Tor for .onion relays only

### 4.3 HTTP Client (OkHttp)

**Purpose:** HTTP requests (Nostr relays, geocoding)

**Components:**
- `OkHttpProvider` - OkHttp singleton
- Connection pooling
- Timeout configuration
- Tor proxy support

---

## 5. User Interface

### 5.1 UI Framework

**Jetpack Compose:**
- Declarative UI
- Material Design 3 components
- Compose BOM 2025.06.01
- Custom theming system

**Theme System:**
- Dark/Light theme support
- Terminal-inspired aesthetic
- Material You integration (optional)
- Custom color schemes

### 5.2 Core Screens

**ChatScreen:**
- Message list with timestamps
- Message input with commands
- Peer list sidebar
- Channel management
- Private chat support

**GeohashPickerActivity:**
- Map-based geohash selection
- Location channel browsing
- Bookmark management

**Onboarding Screens:**
- Permission requests
- Bluetooth status check
- Location status check
- Battery optimization
- Initialization sequence

### 5.3 UI Components

**Message Components:**
- Text messages
- Image messages (with preview)
- Audio messages (with waveform)
- File messages (with type icons)
- System messages (with special styling)

**Input Components:**
- Message input field
- Voice recording button
- Image picker button
- File picker button
- Command suggestions

**Sheets:**
- Peer list sheet
- User sheet (peer details)
- Security verification sheet
- About sheet
- Location notes sheet
- Debug settings sheet

### 5.4 Media Features

**Image Handling:**
- Image picker from gallery
- Camera capture
- Image compression
- EXIF orientation handling
- Full-screen image viewer

**Voice Messages:**
- Voice recording with visualizer
- Real-time waveform display
- Playback controls
- Audio file compression

**File Sharing:**
- File picker (images, audio, video, documents)
- File transfer over mesh
- Transfer progress tracking
- File viewer for received files

### 5.5 Commands (IRC-Style)

**Channel Commands:**
- `/j #channel` - Join or create channel
- `/pass [password]` - Set channel password
- `/save` - Toggle message retention
- `/transfer @name` - Transfer ownership

**User Commands:**
- `/m @name message` - Send private message
- `/w` - List online users
- `/block @name` - Block peer
- `/unblock @name` - Unblock peer

**Utility Commands:**
- `/channels` - Show discovered channels
- `/clear` - Clear chat messages

---

## 6. Data Persistence

### 6.1 Storage Mechanisms

**SharedPreferences:**
- User preferences
- Channel settings
- Tor settings
- Debug settings

**EncryptedSharedPreferences:**
- Identity keys
- Nostr keys
- Sensitive preferences

**SQLite Database:**
- Message history (if retention enabled)
- Peer information
- Channel metadata
- Favorites

**File System:**
- Cached files
- Downloaded files
- Temporary files

### 6.2 Persistence Components

**FavoritesPersistenceService:**
- Favorite peer management
- Persistence layer
- Query operations

**GeohashBookmarksStore:**
- Geohash bookmarks
- Channel aliases
- Conversation metadata

**MessageRetentionService:**
- Optional message saving
- Owner-controlled retention
- Persistent storage

---

## 7. Background Services

### 7.1 Foreground Service

**MeshForegroundService:**
- Persistent mesh networking
- BLE scanning in background
- Notification with status
- Auto-start on boot (optional)

**Service Types:**
- `connectedDevice` - BLE operations
- `dataSync` - Message sync
- `location` - Location-based features

### 7.2 Broadcast Receivers

**BootCompletedReceiver:**
- Auto-start mesh service on boot
- Check user preference
- Request necessary permissions

**NotificationPermissionChangedReceiver:**
- Listen for notification permission grants
- Restart notification system

### 7.3 Service Management

**MeshServiceHolder:**
- Service lifecycle management
- Binding to activities
- State coordination

**AppShutdownCoordinator:**
- Graceful shutdown
- Resource cleanup
- State persistence

---

## 8. Dependencies Analysis

### 8.1 Core Dependencies

**AndroidX:**
- `core-ktx:1.16.0` - Core KTX extensions
- `appcompat:1.7.1` - AppCompat compatibility
- `lifecycle-runtime-ktx:2.9.1` - Lifecycle components
- `activity-compose:1.10.1` - Compose activity integration

**Compose:**
- Compose BOM 2025.06.01
- Material Design 3
- Navigation Compose 2.9.1

### 8.2 Networking

**Nordic BLE:**
- `no.nordicsemi.android:ble:2.6.1`
- Reliable BLE operations
- GATT client/server
- Connection management

**OkHttp:**
- `okhttp:4.12.0`
- HTTP client
- Connection pooling
- Tor proxy support

### 8.3 Cryptography

**BouncyCastle:**
- `bcprov-jdk15on:1.70`
- X25519, Ed25519
- AES-GCM
- HKDF

**Google Tink:**
- `tink-android:1.10.0`
- Additional crypto primitives

**Android Security:**
- `security-crypto:1.1.0-beta01`
- EncryptedSharedPreferences

### 8.4 Location & Maps

**Google Play Services:**
- `play-services-location:21.3.0`
- Fused Location Provider
- Geofencing support

### 8.5 Media

**CameraX:**
- `camera-camera2:1.5.2`
- `camera-lifecycle:1.5.2`
- `camera-compose:1.5.2`

**ML Kit:**
- `barcode-scanning:17.3.0`
- QR code scanning

**ZXing:**
- `core:3.5.4`
- QR code generation

### 8.6 JSON & Serialization

**Gson:**
- `gson:2.13.1`
- JSON parsing
- Nostr event serialization

### 8.7 Coroutines

**Kotlin Coroutines:**
- `kotlinx-coroutines-android:1.10.2`
- Asynchronous operations
- Concurrency management

### 8.8 Permissions

**Accompanist:**
- `accompanist-permissions:0.37.3`
- Runtime permission handling

---

## 9. Build Configuration

### 9.1 Build Types

**Debug:**
- Debuggable
- No code shrinking
- Multiple ABIs (including x86 for emulator)
- Signing with debug keystore

**Release:**
- Minified (ProGuard/R8)
- Shrunk resources
- Optimized bytecode
- Multiple APK splits (arm64-v8a, armeabi-v7a, x86_64, universal)

### 9.2 APK Splits

**Architectures:**
- `arm64-v8a` - ARM 64-bit (recommended for most devices)
- `armeabi-v7a` - ARM 32-bit
- `x86_64` - x86_64 emulator
- `x86` - x86 emulator
- `universal` - All architectures (for F-Droid)

**Automatic Splitting:**
- Enabled for `assemble` tasks
- Disabled for `bundle` tasks
- Based on Gradle task names

### 9.3 ProGuard Configuration

**Rules:**
- `proguard-rules.pro`
- Keeps required classes
- Obfuscates implementation
- Shrinks unused code

### 9.4 Gradle Properties

**Key Properties:**
```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.enableJetifier=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

---

## 10. Permissions

### 10.1 Required Permissions

**Networking:**
- `INTERNET` - Nostr relay connections
- `ACCESS_NETWORK_STATE` - Network state

**Bluetooth:**
- `BLUETOOTH_ADVERTISE` - BLE advertising
- `BLUETOOTH_CONNECT` - BLE connections
- `BLUETOOTH_SCAN` - BLE scanning

**Location:**
- `ACCESS_COARSE_LOCATION` - BLE scanning (required)
- `ACCESS_FINE_LOCATION` - Precise location
- `ACCESS_BACKGROUND_LOCATION` - Background location

**Notifications:**
- `POST_NOTIFICATIONS` - Message notifications

**Foreground Services:**
- `FOREGROUND_SERVICE` - Foreground service
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` - BLE operations
- `FOREGROUND_SERVICE_DATA_SYNC` - Message sync
- `FOREGROUND_SERVICE_LOCATION` - Location features
- `RECEIVE_BOOT_COMPLETED` - Auto-start on boot

**Media:**
- `RECORD_AUDIO` - Voice notes
- `CAMERA` - QR scanning
- `READ_MEDIA_IMAGES` - Image access
- `READ_MEDIA_VIDEO` - Video access
- `READ_MEDIA_AUDIO` - Audio access

**Other:**
- `VIBRATE` - Haptic feedback
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` - Battery optimization

### 10.2 Permission Handling

**Runtime Permissions:**
- Comprehensive onboarding flow
- Educational explanations
- Contextual requests
- Graceful degradation

**Bluetooth Permissions (API 31+):**
- `BLUETOOTH_SCAN` with `android:usesPermissionFlags="neverForLocation"` (when not using location)
- Location permission required for BLE scanning

**Background Location:**
- Optional feature
- Explicit user consent
- Educational UI
- Revokable at any time

---

## 11. Testing

### 11.1 Test Structure

**Unit Tests:**
- Located in `app/src/test/`
- JUnit 4.13.2
- Mockito 4.1.0
- Kotlinx Coroutines Test

**Instrumented Tests:**
- Located in `app/src/androidTest/`
- Espresso 3.6.1
- Compose Testing

### 11.2 Test Commands

```bash
# Unit tests
./gradlew test
./gradlew testDebugUnitTest

# Instrumented tests
./gradlew connectedAndroidTest
./gradlew connectedDebugAndroidTest

# Lint
./gradlew lint
./gradlew lintDebug
```

---

## 12. Deployment

### 12.1 Google Play Store

**Requirements:**
- Target API 35 (Android 15)
- AAB format (recommended)
- Privacy policy URL
- Content rating questionnaire
- Permission justifications

**Store Listing:**
- Full description (from README)
- Short description
- Screenshots (minimum 2)
- Icon (512x512)
- Feature graphic (1024x500)

### 12.2 GitHub Releases

**Release Artifacts:**
- Debug APKs (split by architecture)
- Release APKs (split by architecture)
- Universal APK (for F-Droid)
- Changelog (from CHANGELOG.md)

### 12.3 F-Droid

**Requirements:**
- Universal APK
- No proprietary dependencies
- Buildable from source
- Updated metadata

---

## 13. Known Issues & Limitations

### 13.1 Known Issues

- Debug settings sheet crash on some devices (fixed in 1.4.0)
- Battery optimization may stop mesh service
- Background location requires explicit consent
- Tor connection can be slow on initial start

### 13.2 Limitations

- Max 7 hops for message relay
- BLE MTU limits message size (requires fragmentation)
- File transfer limited by mesh bandwidth
- No multi-device sync
- No cloud backup

---

## 14. Future Enhancements

### 14.1 Potential Improvements

- Multi-device sync via Nostr
- Cloud backup (encrypted)
- Enhanced group features
- Voice/video calls (WebRTC over mesh)
- File preview generation
- Message reactions
- Typing indicators
- Read receipts (beyond current implementation)
- Enhanced search
- Message export/import

### 14.2 Technical Debt

- Migrate remaining code to Compose
- Improve test coverage
- Add integration tests
- Refactor large viewmodels
- Improve error handling
- Add crash reporting
- Performance profiling
- Memory leak detection

---

## 15. Security Considerations

### 15.1 Threat Model

**Assumptions:**
- Adversaries can observe network traffic
- Adversaries can inject packets
- Adversaries can capture devices
- Adversaries can compromise relays

**Mitigations:**
- End-to-end encryption
- Perfect forward secrecy
- Message signatures
- Traffic padding
- Bloom filter deduplication
- Tor integration

### 15.2 Best Practices

- Never log sensitive data
- Use `EncryptedSharedPreferences` for secrets
- Validate all incoming packets
- Implement proper key rotation
- Use TLS/SSL for Nostr (via Tor)
- Regular security audits
- Dependency updates

---

## 16. Development Guidelines

### 16.1 Code Style

- Kotlin official style guide
- 4-space indentation
- KDoc documentation
- Meaningful variable names
- Single responsibility principle

### 16.2 Git Workflow

- Feature branches
- Pull requests
- Code review required
- CI/CD checks
- Semantic versioning

### 16.3 Testing Strategy

- Unit tests for business logic
- Integration tests for services
- UI tests for critical flows
- Manual testing on devices
- Beta testing with users

---

## 17. Documentation

### 17.1 Internal Documentation

- Code comments (KDoc)
- README.md (overview)
- CHANGELOG.md (release notes)
- docs/ directory (protocol specs)

### 17.2 External Documentation

- User guide (in app)
- Privacy policy
- Terms of service
- GitHub issues/PRs
- Discussions

---

## 18. Performance Optimization

### 18.1 Battery Optimization

- Adaptive scanning intervals
- Power mode-based connection limits
- Background duty cycling
- Efficient BLE operations
- Tor connection pooling

### 18.2 Memory Optimization

- Image compression
- Message pagination
- Efficient data structures
- Memory profiling
- Leak detection

### 18.3 Network Optimization

- Message aggregation
- Compression (LZ4)
- Bloom filter deduplication
- Efficient routing
- Connection pooling

---

## 19. Accessibility

### 19.1 Accessibility Features

- Screen reader support
- Keyboard navigation
- High contrast mode
- Font scaling
- Touch target sizes

### 19.2 Compliance

- Android accessibility guidelines
- WCAG 2.1 (where applicable)
- Material Design accessibility

---

## 20. Internationalization

### 20.1 Current Status

- English only (primary)
- No RTL support currently
- English strings in `res/values/strings.xml`

### 20.2 Future i18n

- Multiple language support
- RTL layout support
- Locale-aware formatting
- Localized date/time

---

## Conclusion

BitChat Android is a sophisticated, secure, decentralized messaging application with a modern architecture. It successfully implements complex networking protocols (Bluetooth mesh, Nostr), cryptography (Noise Protocol, Ed25519), and UI frameworks (Jetpack Compose). The codebase is well-organized, with clear separation of concerns and comprehensive feature coverage.

**Key Strengths:**
- Modern Android development practices
- Strong security and privacy focus
- Cross-platform compatibility
- Rich feature set
- Clean architecture

**Areas for Improvement:**
- Test coverage
- Documentation
- Performance optimization
- Accessibility support
- Internationalization

The project is production-ready and actively maintained, with regular updates and improvements.

---

**Analysis Date:** 2025-04-11
**App Version:** 1.7.2 (versionCode 33)
**Repository:** `git@github.com:Satoshi-NaAkokwa/ikorochat-android.git`