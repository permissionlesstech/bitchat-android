# Cashu Wallet Integration for bitchat Android

This document describes the comprehensive Cashu wallet integration that has been added to the bitchat Android app.

## Overview

A full-featured Cashu ecash wallet has been integrated into bitchat, allowing users to send and receive Bitcoin through Cashu tokens and Lightning Network payments. The wallet follows modern Android architecture patterns and integrates seamlessly with the existing terminal-style aesthetic.

## Architecture

### Component Structure
```
/wallet
├── data/           # Data models and types
├── service/        # CDK FFI integration layer
├── repository/     # Data persistence
├── viewmodel/      # Business logic and state management
└── ui/            # Compose UI components
```

### Key Components

#### 1. Data Models (`data/`)
- **CashuToken**: Represents Cashu token data with amount, mint, and memo
- **MintQuote/MeltQuote**: Lightning invoice quotes for receiving/sending
- **WalletTransaction**: Transaction history with types and status
- **Mint**: Mint information and management
- **WalletBalance**: Balance tracking per mint/unit

#### 2. CDK FFI Integration (`service/CashuService.kt`)
- Wraps the Rust CDK FFI bindings for Android
- Provides high-level wallet operations:
  - Token creation and receiving
  - Lightning invoice generation and payment
  - Mint management and info retrieval
  - Balance checking and transaction history

#### 3. Data Persistence (`repository/WalletRepository.kt`)
- Encrypted SharedPreferences for secure local storage
- Manages mints, transactions, and quotes
- Automatic cleanup of expired data
- Thread-safe operations

#### 4. Business Logic (`viewmodel/WalletViewModel.kt`)
- MVVM architecture with LiveData
- Handles all wallet operations and state
- Background polling for quote updates
- Error handling and user feedback

#### 5. UI Components (`ui/`)
- **WalletScreen**: Main wallet interface with tabs
- **WalletOverview**: Balance and recent transactions
- **MintsScreen**: Mint management
- **SendDialog**: Cashu token creation and Lightning payments
- **ReceiveDialog**: Token receiving and Lightning invoice generation

## Features

### Core Functionality

#### Sending
1. **Cashu Tokens**: Create ecash tokens with optional memo
2. **Lightning Payments**: Pay Lightning invoices with melt quotes

#### Receiving  
1. **Cashu Tokens**: Decode and receive tokens from other users
2. **Lightning Invoices**: Generate invoices with QR codes for receiving

#### Mint Management
- Add multiple mints with custom nicknames
- Switch between active mints
- View mint information and connection status
- Edit mint nicknames

### User Interface

#### Design Language
- Terminal-style aesthetic matching bitchat
- Green/black color scheme with monospace fonts
- Material Design components adapted to app style
- Smooth animations and transitions

#### Navigation
- Bottom navigation between Chat and Wallet
- Tab-based wallet interface (Wallet/Mints)
- Modal dialogs for send/receive operations

### Technical Features

#### Background Operations
- Automatic quote polling for Lightning payments
- Transaction status updates
- Balance synchronization

#### Error Handling
- Network error recovery
- Invalid token detection
- Mint connection issues
- User-friendly error messages

#### Security
- Encrypted local storage
- Secure CDK FFI integration
- No private key exposure
- Transaction signing via CDK

## Dependencies Added

### Build Configuration
```kotlin
// JNA for CDK FFI bindings
implementation("net.java.dev.jna:jna:5.13.0")

// QR Code generation
implementation("com.google.zxing:core:3.5.1")

// Kotlinx Serialization
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
```

### CDK FFI Bindings
- Native library: `libcdk_ffi.so` (ARM64)
- Kotlin bindings: `uniffi.cdk.*` 
- Located in: `/app/src/main/jniLibs/arm64-v8a/`

## Files Added/Modified

### New Wallet Files
```
/wallet/
├── data/CashuToken.kt           # Data models and enums
├── data/Mint.kt                 # Mint-related data classes  
├── service/CashuService.kt      # CDK FFI wrapper service
├── repository/WalletRepository.kt # Data persistence layer
├── viewmodel/WalletViewModel.kt  # Business logic and state
├── ui/WalletScreen.kt           # Main wallet container
├── ui/WalletOverview.kt         # Balance and transactions
├── ui/SendDialog.kt             # Send operations
├── ui/ReceiveDialog.kt          # Receive operations
└── ui/MintsScreen.kt            # Mint management
```

### Modified Core Files
- **MainActivity.kt**: Added MainAppScreen with bottom navigation
- **MainAppScreen.kt**: New tab-based interface
- **build.gradle.kts**: Added wallet dependencies
- **libs.versions.toml**: Version management for new dependencies

## Usage Flow

### First Launch
1. User completes existing onboarding (Bluetooth, permissions)
2. App launches with bottom navigation showing Chat and Wallet tabs
3. Wallet tab shows empty state prompting to add first mint

### Adding a Mint
1. User taps "Add Mint" button
2. Enters mint URL (e.g., https://mint.example.com)
3. App fetches mint info and saves configuration  
4. Mint becomes active for wallet operations

### Sending Cashu Tokens
1. User taps "Send" button in wallet
2. Selects "Cashu" tab in send dialog
3. Enters amount and optional memo
4. App generates token using CDK
5. User copies token to share with recipient

### Receiving Cashu Tokens
1. User taps "Receive" button and selects "Cashu" tab
2. Pastes received token from clipboard
3. App decodes and shows token details
4. User confirms to receive the token

### Lightning Operations  
1. **Sending**: User pastes Lightning invoice, gets quote, confirms payment
2. **Receiving**: User enters amount, generates Lightning invoice and QR code

## Integration Points

### With Existing Chat
- Seamless tab switching between Chat and Wallet
- Shared navigation patterns and UI components
- Consistent terminal aesthetic and color scheme
- Integrated error handling and user feedback

### With bitchat Mesh Network
- Potential for future integration (peer-to-peer token sharing)
- Shared security patterns and local storage
- Consistent logging and debugging approach

## Development Notes

### CDK FFI Integration
The wallet uses the CDK (Cashu Development Kit) Rust library through FFI bindings. This provides:
- Robust Cashu protocol implementation
- Cryptographic operations (X25519, AES-256-GCM)
- Lightning Network integration
- Multi-mint support

### Architecture Benefits
- Clean separation of concerns
- Testable components
- Scalable for future features
- Consistent with Android best practices

### Future Enhancements
- QR code scanning for tokens/invoices  
- Transaction export/import
- Advanced mint features
- Integration with chat for P2P payments
- Backup and recovery options

## Testing

The wallet implementation is ready for testing with:
1. Local Cashu mint for development
2. Public testnet mints  
3. Lightning testnet for invoice testing
4. Real mints with small amounts for production testing

The integration maintains all existing bitchat functionality while adding comprehensive Cashu wallet capabilities in a user-friendly interface.
