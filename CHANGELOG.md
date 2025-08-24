# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),

## [0.9.0] - 2025-01-24

### 📌 Added - Message Pinning System

#### WhatsApp-Style Message Pinning with iPhone Dynamic Island Design

- **Pin Message Feature**: Implemented comprehensive message pinning system with permission-based controls
- **Dynamic Island Component**: iPhone-inspired floating notification that appears only when pinned messages exist
- **Visual Pin Indicators**: Added pin icons (`Icons.Filled.PushPin`) to both individual messages and dynamic island
- **Click-to-Scroll Functionality**: Tap dynamic island to instantly navigate to pinned message location
- **Permission System**: Only channel creators and group admins can pin/unpin messages
- **Context Menu Integration**: Long-press messages to access pin/unpin options in dropdown menu
- **Smooth Animations**: Spring-damped animations with bounce effects for modern iOS-like feel
- **Conditional Visibility**: Dynamic island only appears when pinned messages actually exist
- **State Management**: Integrated with existing MVVM architecture using StateFlow for reactive updates
- **Cross-Channel Support**: Works in both regular channels and group chats with proper permission checking

#### Technical Implementation Details:
- **New Components**: 
  - `PinnedMessageIsland.kt` - Dynamic island with animations and styling
  - Enhanced `MessageComponents.kt` with pin icon rendering
  - Extended `ChatViewModel.kt` with pin/unpin logic
- **Database Schema**: Extended `RenChatMessage` model with `isPinned`, `pinnedBy`, `pinnedAt` fields
- **UI/UX**: Material Design 3 theming with proper color schemes and typography
- **Performance**: Memory-efficient conditional rendering with optimized state updates

### 🛡️ Enhanced - Advanced Spam Protection System

#### AI-Powered Spam Detection with 25+ Pattern Types

- **Enhanced Regex Patterns**: Added 15 new sophisticated spam detection patterns:
  - **Modern Financial Scams**: Tesla giveaways, Elon Musk scams, Cash App/Venmo flips
  - **Advanced Crypto Scams**: MEV bots, sandwich attacks, DeFi rugpull schemes
  - **Social Engineering**: Urgency tactics combined with payment requests
  - **AI-Generated Content**: Detection of ChatGPT and AI-generated spam text
  - **Romance/Military Scams**: Advanced catfish and military impersonation patterns
  - **Social Media Manipulation**: Follow-for-follow and engagement pod schemes
  - **Brand Impersonation**: Typosquatting detection (amaz0n, g00gle, payp4l, etc.)
  - **Job Scams**: Work-from-home and MLM recruitment schemes
  - **Investment Fraud**: Advanced trading signal and stock tip scams
  - **Tech Support Scams**: Fake Microsoft, Apple, and antivirus support
  - **MLM/Pyramid Schemes**: Multi-level marketing and business opportunity scams
  - **Health Supplement Scams**: Weight loss and miracle cure detection
  - **Conspiracy Theory Spam**: Fake news and misinformation pattern detection
  - **Gaming Scams**: Free VBucks, Robux generators, and game hack scams
  - **Unicode Homograph Attacks**: Detection of mixed Cyrillic/Latin character abuse

#### Advanced Detection Features:
- **Behavioral Analysis**: Enhanced pattern recognition for suspicious user behavior
- **Trust Scoring System**: Dynamic user reputation with graduated thresholds
- **Context Learning**: AI-like pattern recognition that learns user communication styles
- **Anti-Bypass Protection**: Hardware fingerprinting and sophisticated evasion detection
- **Graduated Penalties**: Fair warning system with escalating consequences
- **Real-time Processing**: Optimized for minimal latency and battery usage

#### Technical Enhancements:
- **Pattern Optimization**: 25+ regex patterns with performance-optimized compilation
- **Severity Classification**: Four-tier system (LOW, MEDIUM, HIGH, CRITICAL) with appropriate responses
- **Device Fingerprinting**: Integration with `AntiBypassStorage` for persistent ban enforcement
- **User-Friendly Thresholds**: Balanced to minimize false positives for normal users
- **Comprehensive Logging**: Detailed spam detection logging for analysis and debugging

### 🔧 Fixed - Build and Compilation Issues

#### Android Build System Improvements

- **Import Resolution**: Fixed all unresolved reference errors across the codebase
- **Conflicting Overloads**: Resolved duplicate function definitions and import conflicts
- **GroupAction Imports**: Fixed missing enum imports in group management components
- **Method Name Corrections**: Updated deprecated method calls to current API standards
- **Dependency Management**: Resolved package manager conflicts and import issues
- **LSP Diagnostics**: Achieved zero compilation errors across all major components

#### Component-Specific Fixes:
- **ChatViewModel.kt**: Fixed `addGroupMessage` to `addChannelMessage` method calls
- **ChatUserSheet.kt**: Resolved `ReportUserDialog` and `UserActionRow` import issues
- **ModerationUIComponents.kt**: Added missing `ReportReason` enum imports
- **AboutSheet.kt**: Removed duplicate `PasswordPromptDialog` function
- **Permission System**: Integrated proper group permission checking with `GroupAction.CHANGE_SETTINGS`

### 📚 Documentation - Comprehensive README Updates

#### Feature Documentation

- **Pin Message Feature**: Added detailed section with usage instructions and technical details
- **Spam Protection System**: Comprehensive documentation of detection capabilities and performance
- **Feature Table Updates**: Added new features to the core features comparison table
- **Technical Implementation**: Detailed architecture and design pattern explanations
- **User Guide**: Step-by-step instructions for using new features

## [0.8.0] - 2025-08-24

### Added
- **Enhanced View Once Message Button**: Redesigned view once toggle with circular icon featuring lock and number "1"
  - Inactive state: Gray lock with white number "1"
  - Active state: Same color as send button (orange for private/channels, green for public) with white number "1"
  - Improved visual consistency with app theme
- **Theme Settings in Header**: Added gear icon in main header to access theme selection
  - Quick access to System, Light, Dark, and Dynamic (Material You) themes
  - Convenient popup dialog for theme switching
- **Password-Protected Channel Indicators**: Golden lock icon displays in channel headers
  - Automatically shows when channel has password protection (set via `/pass [password]`)
  - Clear visual indication of secure channels
  - Enhanced channel security awareness

### Improved
- UI consistency across theme states and chat contexts
- Visual feedback for secure messaging features
- User experience for theme management and channel security

## [0.7.2] - 2025-07-20
### Fixed
- fix: battery optimization screen content scrollable with fixed buttons

## [0.7.1] - 2025-07-19

### Added
-feat(battery): add battery optimization management for background reliability

### Fixed
- fix: center align toolbar item in ChatHeader - passed modifier.fillmaxHeight so the content inside the row can actually be centered
- fix: update sidebar text to use string resources
- fix(chat): cursor location and enhance message input with slash command styling

### Changed
- refactor: remove context attribute at ChatViewModel.kt
- Refactor: Migrate MainViewModel to use StateFlow

### Improved
- Use HorizontalDivider instead of deprecated Divider
- Use contentPadding instead of padding so items remain fully visible


and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7]

### Added
- Location services check during app startup with educational UI
- Message text selection functionality in chat interface
- Enhanced RSSI tracking and unread message indicators
- Major Bluetooth connection architecture refactoring with dedicated managers

### Fixed
- **Critical**: Android-iOS message fragmentation compatibility issues
  - Fixed fragment size (500→150 bytes) and ID generation for cross-platform messaging
  - Ensures Android can properly communicate with iOS devices
- DirectMessage notifications and text copying functionality
- Smart routing optimizations (no relay loops, targeted delivery)
- Build system compilation issues and null pointer exceptions

### Changed
- Comprehensive dependency updates (AGP 8.10.1, Kotlin 2.2.0, Compose 2025.06.01)
- Optimized BLE scan intervals for better battery performance
- Reduced excessive logging output

### Improved
- Cross-platform compatibility with iOS and Rust implementations
- Connection stability through architectural improvements
- Battery performance via scan duty cycling
- User onboarding with location services education

## [0.6]

### Added
- Channel password management with `/pass` command for channel owners
- Monochrome/themed launcher icon for Android 12+ dynamic theming support
- Unit tests package with initial testing infrastructure
- Production build optimization with code minification and shrinking
- Native back gesture/button handling for all app views

### Fixed
- Favorite peer functionality completely restored and improved
  - Enhanced favorite system with fallback mechanism for peers without key exchange
  - Fixed UI state updates for favorite stars in both header and sidebar
  - Improved favorite persistence across app sessions
- `/w` command now displays user nicknames instead of peer IDs
- Button styling and layout improvements across the app
  - Enhanced back button positioning and styling
  - Improved private chat and channel header button layouts
  - Fixed button padding and alignment issues
- Color scheme consistency updates
  - Updated orange color throughout the app to match iOS version
  - Consistent color usage for private messages and UI elements
- App startup reliability improvements
  - Better initialization sequence handling
  - Fixed null pointer exceptions during startup
  - Enhanced error handling and logging
- Input field styling and behavior improvements
- Sidebar user interaction enhancements
- Permission explanation screen layout fixes with proper vertical padding

### Changed
- Updated GitHub organization references in project files
- Improved README documentation with updated clone URLs
- Enhanced logging throughout the application for better debugging

## [0.5.1] - 2025-07-10

### Added
- Bluetooth startup check with user prompt to enable Bluetooth if disabled

### Fixed
- Improved Bluetooth initialization reliability on first app launch

## [0.5] - 2025-07-10

### Added
- New user onboarding screen with permission explanations
- Educational content explaining why each permission is required
- Privacy assurance messaging (no tracking, no servers, local-only data)

### Fixed
- Comprehensive permission validation - ensures all required permissions are granted
- Proper Bluetooth stack initialization on first app load
- Eliminated need for manual app restart after installation
- Enhanced permission request coordination and error handling

### Changed
- Improved first-time user experience with guided setup flow

## [0.4] - 2025-07-10

### Added
- Push notifications for direct messages
- Enhanced notification system with proper click handling and grouping

### Improved
- Direct message (DM) view with better user interface
- Enhanced private messaging experience

### Known Issues
- Favorite peer functionality currently broken

## [0.3] - 2025-07-09

### Added
- Battery-aware scanning policies for improved power management
- Dynamic scan behavior based on device battery state

### Fixed
- Android-to-Android Bluetooth Low Energy connections
- Peer discovery reliability between Android devices
- Connection stability improvements

## [0.2] - 2025-07-09

### Added
- Initial Android implementation of RenChat protocol
- Bluetooth Low Energy mesh networking
- End-to-end encryption for private messages
- Channel-based messaging with password protection
- Store-and-forward message delivery
- IRC-style commands (/msg, /join, /clear, etc.)
- RSSI-based signal quality indicators

### Fixed
- Various Bluetooth handling improvements
- User interface refinements
- Connection reliability enhancements

## [0.1] - 2025-07-08

### Added
- Initial release of RenChat Android client
- Basic mesh networking functionality
- Core messaging features
- Protocol compatibility with iOS RenChat client

[Unreleased]: https://github.com/permissionlesstech/RenChat-android/compare/0.5.1...HEAD
[0.5.1]: https://github.com/permissionlesstech/RenChat-android/compare/0.5...0.5.1
[0.5]: https://github.com/permissionlesstech/RenChat-android/compare/0.4...0.5
[0.4]: https://github.com/permissionlesstech/RenChat-android/compare/0.3...0.4
[0.3]: https://github.com/permissionlesstech/RenChat-android/compare/0.2...0.3
[0.2]: https://github.com/permissionlesstech/RenChat-android/compare/0.1...0.2
[0.1]: https://github.com/permissionlesstech/RenChat-android/releases/tag/0.1
