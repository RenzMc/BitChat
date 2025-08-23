<p align="center">
    <img src="app/src/main/res/mipmap-hdpi/ic_launcher.png" alt="RenChat Android Logo" width="480">
</p>

> [!WARNING]
> This software has not received external security review and may contain vulnerabilities and may not necessarily meet its stated security goals. Do not use it for sensitive use cases, and do not rely on its security until it has been reviewed. Work in progress.

# RenChat for Android

A secure, decentralized, peer-to-peer messaging app that works over Bluetooth mesh networks. No internet required, no servers, no phone numbers - just pure encrypted communication.

**🆕 Latest Features (v1.0.0 Compatible):**
- **Individual chat locking** with PIN/biometric authentication
- **Enhanced IRC commands** with full validation (/help, /nick, /me, /list, /leave, /topic)
- **Location-based channels** using geohash for local area communication
- **Nostr integration** for location channels with relay support
- **Improved UI/UX** with repositioned controls and enhanced scrolling
- **Enhanced security** with biometric authentication and robust nickname handling

This is the **Android port** of the original [RenChat iOS app](https://github.com/jackjackbits/BitChat), maintaining 100% protocol compatibility for cross-platform communication.

## 📱 **BitChat → RenChat Evolution**

**RenChat** is the modern evolution of BitChat with significant UI/UX improvements while maintaining full protocol compatibility:

### **What Changed:**
- **🎨 Modern UI Design**: WhatsApp-inspired interface replacing terminal-style green theme
- **🖼️ New Branding**: Updated logo, icons, and app identity
- **📱 Enhanced UX**: Smooth animations, better typography, improved responsiveness
- **👥 Advanced Group System**: WhatsApp-like group management with invite URLs, admin roles, and comprehensive controls
- **🔒 Same Security**: Identical encryption and mesh networking protocols

### **What Stayed the Same:**
- **🌐 Full Protocol Compatibility**: Works seamlessly with original iOS BitChat users
- **🔐 End-to-End Encryption**: Same X25519 + AES-256-GCM security
- **📡 Mesh Networking**: Identical Bluetooth LE mesh architecture
- **⚡ Core Features**: All original messaging and channel functionality preserved

**Note**: RenChat users can communicate with BitChat users without any setup - they're the same protocol underneath!

## Install RenChat

You can download the latest version of RenChat for Android from the [GitHub Releases page](https://github.com/permissionlesstech/RenChat-android/releases).

**Instructions:**

1.  **Download the APK:** On your Android device, navigate to the link above and download the latest `.apk` file. Open it.
2.  **Allow Unknown Sources:** On some devices, before you can install the APK, you may need to enable "Install from unknown sources" in your device's settings. This is typically found under **Settings > Security** or **Settings > Apps & notifications > Special app access**.
3.  **Install:** Open the downloaded `.apk` file to begin the installation.

## License

This project is released into the public domain. See the [LICENSE](LICENSE.md) file for details.

## Features

- **✅ Cross-Platform Compatible**: Full protocol compatibility with iOS RenChat
- **✅ Decentralized Mesh Network**: Automatic peer discovery and multi-hop message relay over Bluetooth LE
- **✅ End-to-End Encryption**: X25519 key exchange + AES-256-GCM for private messages
- **✅ Channel-Based Chats**: Topic-based group messaging with optional password protection
- **✅ Advanced Group Management**: WhatsApp-style groups with invite URLs, admin roles, and member controls
- **🔒 View Once Messages**: Disappearing messages that delete after being viewed once
- **🔐 Individual Chat Locking**: PIN/biometric authentication for private conversations
- **🌍 Location Channels**: Geohash-based local area communication
- **📡 Nostr Integration**: Connect to Nostr relays for enhanced location channels
- **✅ Store & Forward**: Messages cached for offline peers and delivered when they reconnected
- **✅ Privacy First**: No accounts, no phone numbers, no persistent identifiers
- **✅ Enhanced IRC Commands**: Full command suite with validation and error handling
- **✅ Message Retention**: Optional channel-wide message saving controlled by channel owners
- **✅ Emergency Wipe**: Triple-tap logo to instantly clear all data
- **✅ Modern Android UI**: Jetpack Compose with Material Design 3
- **✅ Dark/Light Themes**: Terminal-inspired aesthetic matching iOS version
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
   git clone https://github.com/permissionlesstech/RenChat-android.git
   cd RenChat-android
   ```

2. **Open in Android Studio:**
   ```bash
   # Open Android Studio and select "Open an Existing Project"
   # Navigate to the RenChat-android directory
   ```

3. **Build the project:**
   ```bash
   ./gradlew build
   ```

4. **Install on device:**
   ```bash
   ./gradlew installDebug
   ```

### Development Build

For development builds with debugging enabled:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release Build

For production releases:

```bash
./gradlew assembleRelease
```

## Android-Specific Requirements

### Permissions

The app requires the following permissions (automatically requested):

- **Bluetooth**: Core BLE functionality
- **Location**: Required for BLE scanning on Android and location channels
- **Notifications**: Message alerts and background updates
- **Internet**: For Nostr relay connections (location channels only)
- **Biometric**: For individual chat locking (optional)

### Hardware Requirements

- **Bluetooth LE (BLE)**: Required for mesh networking
- **Android 8.0+**: API level 26 minimum
- **RAM**: 2GB recommended for optimal performance

## Usage

### Basic Commands

- `/j #channel` or `/join #channel` - Join or create a channel
- `/l [channel]` or `/leave [channel]` or `/part [channel]` - Leave current or specified channel
- `/m @name message` or `/msg @name message` - Send a private message
- `/me <action>` - Perform an action (e.g., "/me waves")
- `/w` - List online users
- `/channels` - Show all discovered channels
- `/list` - List all available channels
- `/nick <nickname>` - Change your nickname
- `/topic [text]` - View or set channel topic
- `/help` - Show all available commands
- `/block @name` - Block a peer from messaging you
- `/block` - List all blocked peers
- `/unblock @name` - Unblock a peer
- `/clear` - Clear chat messages
- `/hug @name` - Send someone a warm hug
- `/slap @name` - Slap someone with a trout
- `/pass [password]` - Set/change channel password (owner only)
- `/transfer @name` - Transfer channel ownership
- `/save` - Toggle message retention for channel (owner only)

### Group Management Commands

- `/group create [name]` - Create a new group with advanced features
- `/group invite [name]` - Generate invite URL for group
- `/group join [invite-code]` - Join group via invite link
- `/group promote @name` - Promote member to admin (owner/admin only)
- `/group demote @name` - Demote admin to member (owner only)
- `/group kick @name` - Remove member from group (admin+ only)
- `/group ban @name` - Ban user from group (admin+ only)
- `/group unban @name` - Unban user from group (admin+ only)
- `/group members` - List all group members with roles
- `/group admins` - List group administrators
- `/group info` - Show group information and settings
- `/group description [text]` - Set group description (admin+ only)
- `/group settings` - Configure group permissions (owner only)

## 🔒 View Once Messages

RenChat now supports **view-once messages** - a privacy-focused feature that automatically deletes messages after they're viewed once by recipients.

### How It Works

- **Auto-Delete**: Messages disappear after being viewed once by recipients
- **Cross-Platform**: Fully compatible with iOS RenChat via binary protocol
- **Universal**: Works in both private chats and group channels
- **Sender Visibility**: Senders can always see their own view-once messages

### Using View Once Messages

1. **Enable View Once Mode**: 
   - Look for the lock button next to the send button
   - Tap to toggle view-once mode
   - **Gray lock** = View once disabled (normal messages)
   - **Green lock** = View once enabled (disappearing messages)

2. **Send View Once Messages**:
   - Type your message as usual
   - Enable view-once mode (lock button turns green)
   - Send your message - it will show a small lock icon

3. **Viewing View Once Messages**:
   - Recipients see view-once messages with a lock indicator
   - Once viewed, the message disappears from recipient's chat
   - Senders retain visibility of their own view-once messages

### Technical Details

- **Protocol Compatibility**: View-once state transmitted via binary protocol
- **Message Tracking**: App tracks which view-once messages have been viewed
- **Visual Indicators**: Lock icons clearly identify view-once messages
- **State Management**: View-once mode persists during chat session

### Getting Started

1. **Install the app** on your Android device (requires Android 8.0+)
2. **Grant permissions** for Bluetooth and location when prompted
3. **Launch RenChat** - it will auto-start mesh networking
4. **Set your nickname** or use the auto-generated one
5. **Connect automatically** to nearby iOS and Android RenChat users
6. **Join a channel** with `/j #general` or start chatting in public
7. **Messages relay** through the mesh network to reach distant peers

### Android UI Features

- **Jetpack Compose UI**: Modern Material Design 3 interface
- **Dark/Light Themes**: Terminal-inspired aesthetic matching iOS
- **Haptic Feedback**: Vibrations for interactions and notifications
- **Adaptive Layout**: Optimized for various Android screen sizes
- **Message Status**: Real-time delivery and read receipts
- **RSSI Indicators**: Signal strength colors for each peer

## Security & Privacy

### Encryption
- **Private Messages**: X25519 key exchange + AES-256-GCM encryption
- **Channel Messages**: Argon2id password derivation + AES-256-GCM
- **Digital Signatures**: Ed25519 for message authenticity
- **Forward Secrecy**: New key pairs generated each session

### Privacy Features
- **No Registration**: No accounts, emails, or phone numbers required
- **Ephemeral by Default**: Messages exist only in device memory
- **Cover Traffic**: Random delays and dummy messages prevent traffic analysis
- **Emergency Wipe**: Triple-tap logo to instantly clear all data
- **Local-First**: Works completely offline, no servers involved

## Performance & Efficiency

### Message Compression
- **LZ4 Compression**: Automatic compression for messages >100 bytes
- **30-70% bandwidth savings** on typical text messages
- **Smart compression**: Skips already-compressed data

### Battery Optimization
- **Adaptive Power Modes**: Automatically adjusts based on battery level
  - Performance mode: Full features when charging or >60% battery
  - Balanced mode: Default operation (30-60% battery)
  - Power saver: Reduced scanning when <30% battery
  - Ultra-low power: Emergency mode when <10% battery
- **Background efficiency**: Automatic power saving when app backgrounded
- **Configurable scanning**: Duty cycle adapts to battery state

### Network Efficiency
- **Optimized Bloom filters**: Faster duplicate detection with less memory
- **Message aggregation**: Batches small messages to reduce transmissions
- **Adaptive connection limits**: Adjusts peer connections based on power mode

## Technical Architecture

### Binary Protocol
RenChat uses an efficient binary protocol optimized for Bluetooth LE:
- Compact packet format with 1-byte type field
- TTL-based message routing (max 7 hops)
- Automatic fragmentation for large messages
- Message deduplication via unique IDs

### Mesh Networking
- Each device acts as both client and peripheral
- Automatic peer discovery and connection management
- Store-and-forward for offline message delivery
- Adaptive duty cycling for battery optimization

### Android-Specific Optimizations
- **Coroutine Architecture**: Asynchronous operations for mesh networking
- **Kotlin Coroutines**: Thread-safe concurrent mesh operations
- **EncryptedSharedPreferences**: Secure storage for user settings
- **Lifecycle-Aware**: Proper handling of Android app lifecycle
- **Battery Optimization**: Foreground service and adaptive scanning

## Android Technical Architecture

### Core Components

1. **RenChatApplication.kt**: Application-level initialization and dependency injection
2. **MainActivity.kt**: Main activity handling permissions and UI hosting
3. **ChatViewModel.kt**: MVVM pattern managing app state and business logic
4. **BluetoothMeshService.kt**: Core BLE mesh networking (central + peripheral roles)
5. **EncryptionService.kt**: Cryptographic operations using BouncyCastle
6. **BinaryProtocol.kt**: Binary packet encoding/decoding matching iOS format
7. **ChatScreen.kt**: Jetpack Compose UI with Material Design 3

### Dependencies

- **Jetpack Compose**: Modern declarative UI
- **BouncyCastle**: Cryptographic operations (X25519, Ed25519, AES-GCM)
- **Nordic BLE Library**: Reliable Bluetooth LE operations
- **Kotlin Coroutines**: Asynchronous programming
- **LZ4**: Message compression (when enabled)
- **EncryptedSharedPreferences**: Secure local storage

### Binary Protocol Compatibility

The Android implementation maintains 100% binary protocol compatibility with iOS:
- **Header Format**: Identical 13-byte header structure
- **Packet Types**: Same message types and routing logic
- **Encryption**: Identical cryptographic algorithms and key exchange
- **UUIDs**: Same Bluetooth service and characteristic identifiers
- **Fragmentation**: Compatible message fragmentation for large content

## Publishing to Google Play

### Preparation

1. **Update version information:**
   ```kotlin
   // In app/build.gradle.kts
   defaultConfig {
       versionCode = 2  // Increment for each release
       versionName = "1.1.0"  // User-visible version
   }
   ```

2. **Create a signed release build:**
   ```bash
   ./gradlew assembleRelease
   ```

3. **Generate app bundle (recommended for Play Store):**
   ```bash
   ./gradlew bundleRelease
   ```

### Play Store Requirements

- **Target API**: Latest Android API (currently 34)
- **Privacy Policy**: Required for apps requesting sensitive permissions
- **App Permissions**: Justify Bluetooth and location usage
- **Content Rating**: Complete questionnaire for age-appropriate content

### Distribution

- **Google Play Store**: Main distribution channel
- **F-Droid**: For open-source distribution
- **Direct APK**: For testing and development

## Cross-Platform Communication

This Android port enables seamless communication with the original iOS RenChat app:

- **iPhone ↔ Android**: Full bidirectional messaging
- **Mixed Groups**: iOS and Android users in same channels
- **Feature Parity**: All commands and encryption work across platforms
- **Protocol Sync**: Identical message format and routing behavior

**iOS Version**: For iPhone/iPad users, get the original RenChat at [github.com/jackjackbits/RenChat](https://github.com/jackjackbits/RenChat)

## Contributing

Contributions are welcome! Key areas for enhancement:

1. **Performance**: Battery optimization and connection reliability
2. **UI/UX**: Additional Material Design 3 features
3. **Security**: Enhanced cryptographic features
4. **Testing**: Unit and integration test coverage
5. **Documentation**: API documentation and development guides

## Support & Issue 

- **Bug Reports**: [Create an issue](../../issues) with device info and logs
- **Feature Requests**: [Start a discussion](https://github.com/RenzMc/RenChat/discussions)
- **Security Issues**: Email security concerns privately
- **iOS Compatibility**: Cross-reference with [original iOS repo](https://github.com/jackjackbits/RenChat)

For iOS-specific issues, please refer to the [original iOS RenChat repository](https://github.com/jackjackbits/RenChat).
