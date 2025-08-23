package com.renchat.android.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.renchat.android.mesh.BluetoothMeshDelegate
import com.renchat.android.mesh.BluetoothMeshService
import com.renchat.android.model.RenChatMessage
import com.renchat.android.model.Group
import com.renchat.android.protocol.RenChatPacket
import com.renchat.android.nostr.NostrGeohashService
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.*
import kotlin.random.Random

/**
 * Refactored ChatViewModel - Main coordinator for RenChat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    val meshService: BluetoothMeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // State management
    private val state = ChatState()
    
    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)
    private val groupManager = GroupManager(state, messageManager, dataManager, viewModelScope)
    
    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = meshService.hasEstablishedSession(peerID)
        override fun initiateHandshake(peerID: String) = meshService.initiateNoiseHandshake(peerID) 
        override fun getMyPeerID(): String = meshService.myPeerID
    }
    
    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager, groupManager)
    private val notificationManager = NotificationManager(application.applicationContext)
    val chatLockManager = ChatLockManager(application.applicationContext, dataManager)
    private val nostrChannelManager = com.renchat.android.nostr.NostrChannelManager(application.applicationContext)
    
    // Nostr and Geohash service - initialize singleton
    private val nostrGeohashService = NostrGeohashService.initialize(
        application = application,
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        coroutineScope = viewModelScope,
        dataManager = dataManager,
        notificationManager = notificationManager
    )
    
    // Delegate handler for mesh callbacks
    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMyPeerID = { meshService.myPeerID },
        getMeshService = { meshService }
    )
    
    // Expose state through LiveData (maintaining the same interface)
    val messages: LiveData<List<RenChatMessage>> = state.messages
    val connectedPeers: LiveData<List<String>> = state.connectedPeers
    val nickname: LiveData<String> = state.nickname
    val isConnected: LiveData<Boolean> = state.isConnected
    val privateChats: LiveData<Map<String, List<RenChatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: LiveData<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: LiveData<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: LiveData<Set<String>> = state.joinedChannels
    val currentChannel: LiveData<String?> = state.currentChannel
    val channelMessages: LiveData<Map<String, List<RenChatMessage>>> = state.channelMessages
    val unreadChannelMessages: LiveData<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: LiveData<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: LiveData<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: LiveData<String?> = state.passwordPromptChannel
    val showSidebar: LiveData<Boolean> = state.showSidebar
    val hasUnreadChannels = state.hasUnreadChannels
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val showCommandSuggestions: LiveData<Boolean> = state.showCommandSuggestions
    val commandSuggestions: LiveData<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: LiveData<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: LiveData<List<String>> = state.mentionSuggestions
    val favoritePeers: LiveData<Set<String>> = state.favoritePeers
    val peerSessionStates: LiveData<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: LiveData<Map<String, String>> = state.peerFingerprints
    val peerNicknames: LiveData<Map<String, String>> = state.peerNicknames
    val peerRSSI: LiveData<Map<String, Int>> = state.peerRSSI
    val showAppInfo: LiveData<Boolean> = state.showAppInfo
    val isViewOnceEnabled: LiveData<Boolean> = state.isViewOnceEnabled
    val viewedMessages: LiveData<Set<String>> = state.viewedMessages
    val lockedChats: LiveData<Set<String>> = chatLockManager.lockedChats
    val selectedLocationChannel: LiveData<com.renchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: LiveData<Boolean> = state.isTeleported
    val geohashPeople: LiveData<List<com.renchat.android.nostr.GeoPerson>> = state.geohashPeople
    val teleportedGeo: LiveData<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: LiveData<Map<String, Int>> = state.geohashParticipantCounts
    
    init {
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
    }
    
    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)
        
        // Load data
        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)
        
        // Initialize channel messages
        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }
        
        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()
        
        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()
        
        // Initialize location channel state
        nostrGeohashService.initializeLocationChannelState()
        
        // Initialize favorites persistence service
        com.renchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())
        
        // Initialize Nostr integration
        nostrGeohashService.initializeNostrIntegration()
        dataManager.loadBlockedUsers()
        dataManager.loadLockedChats()
        
        // Initialize Nostr channel manager
        nostrChannelManager.initialize()
        
        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()
        
        // Note: Mesh service is now started by MainActivity
        
        // Show welcome message if no peers after delay
        viewModelScope.launch {
            delay(10000)
            if (state.getConnectedPeersValue().isEmpty() && state.getMessagesValue().isEmpty()) {
                val welcomeMessage = RenChatMessage(
                    sender = "system",
                    content = "get people around you to download RenChat and chat with them here!",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(welcomeMessage)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Note: Mesh service lifecycle is now managed by MainActivity
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        meshService.sendBroadcastAnnounce()
    }
    
    // MARK: - Channel Management (delegated)
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, meshService.myPeerID)
    }
    
    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }
    
    fun leaveChannel(channel: String) {
        // Check if it's a group channel
        if (channel.startsWith("#group:")) {
            val group = groupManager.getGroupByChannel(channel)
            if (group != null) {
                groupManager.leaveGroup(group.id, meshService.myPeerID)
                return
            }
        }
        
        channelManager.leaveChannel(channel)
        meshService.sendMessage("left $channel")
    }
    
    // MARK: - Group Management (delegated)
    
    fun createGroup(name: String, description: String? = null): Group? {
        val myNickname = state.getNicknameValue() ?: meshService.myPeerID
        return groupManager.createGroup(
            name = name,
            description = description,
            creatorPeerID = meshService.myPeerID,
            creatorNickname = myNickname
        )
    }
    
    fun joinGroupByInvite(inviteCode: String): Boolean {
        val myNickname = state.getNicknameValue() ?: meshService.myPeerID
        return groupManager.joinGroupByInvite(
            inviteCode = inviteCode,
            joinerPeerID = meshService.myPeerID,
            joinerNickname = myNickname
        )
    }
    
    fun getUserGroups(): List<Group> {
        return groupManager.getUserGroups(meshService.myPeerID)
    }
    
    fun getCurrentGroup(): Group? {
        val currentChannel = state.getCurrentChannelValue() ?: return null
        return groupManager.getGroupByChannel(currentChannel)
    }
    
    // MARK: - Private Chat Management (delegated)
    
    fun startPrivateChat(peerID: String) {
        val success = privateChatManager.startPrivateChat(peerID, meshService)
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(peerID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(peerID)
        }
    }
    
    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
    }
    
    // MARK: - Message Sending
    
    fun sendMessage(content: String) {
        if (content.isEmpty()) return
        
        // Check for commands
        if (content.startsWith("/")) {
            commandProcessor.processCommand(content, meshService, meshService.myPeerID, { messageContent, mentions, channel ->
                meshService.sendMessage(messageContent, mentions, channel)
            }, this)
            return
        }
        
        val mentions = messageManager.parseMentions(content, meshService.getPeerNicknames().values.toSet(), state.getNicknameValue())
        val channels = messageManager.parseChannels(content)
        
        // Auto-join mentioned channels
        channels.forEach { channel ->
            if (!state.getJoinedChannelsValue().contains(channel)) {
                joinChannel(channel)
            }
        }
        
        val selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // Send private message
            val recipientNickname = meshService.getPeerNicknames()[selectedPeer]
            privateChatManager.sendPrivateMessage(
                content, 
                selectedPeer, 
                recipientNickname,
                state.getNicknameValue(),
                meshService.myPeerID,
                isViewOnce = state.getIsViewOnceEnabledValue()
            ) { messageContent, peerID, recipientNicknameParam, messageId ->
                meshService.sendPrivateMessage(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {
            // Send public/channel message
            val message = RenChatMessage(
                sender = state.getNicknameValue() ?: meshService.myPeerID,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = meshService.myPeerID,
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = currentChannelValue,
                isViewOnce = state.getIsViewOnceEnabledValue()
            )
            
            if (currentChannelValue != null) {
                channelManager.addChannelMessage(currentChannelValue, message, meshService.myPeerID)
                
                // Check if encrypted channel
                if (channelManager.hasChannelKey(currentChannelValue)) {
                    channelManager.sendEncryptedChannelMessage(
                        content, 
                        mentions, 
                        currentChannelValue, 
                        state.getNicknameValue(),
                        meshService.myPeerID,
                        onEncryptedPayload = { encryptedData ->
                            // This would need proper mesh service integration
                            meshService.sendMessage(content, mentions, currentChannelValue)
                        },
                        onFallback = {
                            meshService.sendMessage(content, mentions, currentChannelValue)
                        }
                    )
                } else {
                    meshService.sendMessage(content, mentions, currentChannelValue)
                }
            } else {
                messageManager.addMessage(message)
                meshService.sendMessage(content, mentions, null)
            }
        }
    }
    
    // MARK: - Utility Functions
    
    fun getPeerIDForNickname(nickname: String): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)
        
        // Log current state after toggle
        logCurrentFavoriteState()
    }
    
    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "LiveData favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
            }
        }
    }
    
    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val sessionStates = currentPeers.associateWith { peerID ->
            meshService.getSessionState(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)

        val nicknames = meshService.getPeerNicknames()
        state.setPeerNicknames(nicknames)

        val rssiValues = meshService.getPeerRSSI()
        state.setPeerRSSI(rssiValues)
    }
    
    // MARK: - Debug and Troubleshooting
    
    fun getDebugStatus(): String {
        return meshService.getDebugStatus()
    }
    
    // Note: Mesh service restart is now handled by MainActivity
    // This function is no longer needed
    
    fun setAppBackgroundState(inBackground: Boolean) {
        // Forward to notification manager for notification logic
        notificationManager.setAppBackgroundState(inBackground)
    }
    
    fun setCurrentPrivateChatPeer(peerID: String?) {
        // Update notification manager with current private chat peer
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }
    
    fun clearNotificationsForSender(peerID: String) {
        // Clear notifications when user opens a chat
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, meshService)
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }
    
    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: RenChatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
    
    // MARK: - Emergency Clear
    
    fun panicClearAllData() {
        Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")
        
        // Clear all UI managers
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()
        
        // Clear all mesh service data
        clearAllMeshServiceData()
        
        // Clear all cryptographic data
        clearAllCryptographicData()
        
        // Clear all notifications
        notificationManager.clearAllNotifications()
        
        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        
        Log.w(TAG, "🚨 PANIC MODE COMPLETED - All sensitive data cleared")
        
        // Note: Mesh service restart is now handled by MainActivity
        // This method now only clears data, not mesh service lifecycle
    }
    
    /**
     * Clear all mesh service related data
     */
    private fun clearAllMeshServiceData() {
        try {
            // Request mesh service to clear all its internal data
            meshService.clearAllInternalData()
            
            Log.d(TAG, "✅ Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service data: ${e.message}")
        }
    }
    
    /**
     * Clear all cryptographic data including persistent identity
     */
    private fun clearAllCryptographicData() {
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            meshService.clearAllEncryptionData()
            
            // Clear secure identity state (if used)
            try {
                val identityManager = com.renchat.android.identity.SecureIdentityStateManager(getApplication())
                identityManager.clearIdentityData()
                Log.d(TAG, "✅ Cleared secure identity state")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }
            
            Log.d(TAG, "✅ Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cryptographic data: ${e.message}")
        }
    }
    
    // MARK: - View Once Message Management
    
    fun toggleViewOnceMode() {
        val currentState = state.getIsViewOnceEnabledValue()
        state.setIsViewOnceEnabled(!currentState)
    }
    
    fun markMessageAsViewed(messageId: String) {
        state.addViewedMessage(messageId)
    }
    
    fun isMessageViewable(message: RenChatMessage, currentUserPeerID: String): Boolean {
        if (!message.isViewOnce) return true
        
        // View-once messages are viewable if:
        // 1. User is the sender (can always see their own messages)
        // 2. User hasn't viewed it yet and they're not the sender
        val isOwnMessage = message.senderPeerID == currentUserPeerID
        val hasViewed = state.getViewedMessagesValue().contains(message.id)
        
        return isOwnMessage || !hasViewed
    }
    
    // MARK: - Navigation Management
    
    fun showAppInfo() {
        state.setShowAppInfo(true)
    }
    
    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }
    
    fun showSidebar() {
        state.setShowSidebar(true)
    }
    
    fun hideSidebar() {
        state.setShowSidebar(false)
    }
    
    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when {
            // Close app info dialog
            state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }
            // Close sidebar
            state.getShowSidebarValue() -> {
                hideSidebar()
                true
            }
            // Close password dialog
            state.getShowPasswordPromptValue() -> {
                state.setShowPasswordPrompt(false)
                state.setPasswordPromptChannel(null)
                true
            }
            // Exit private chat
            state.getSelectedPrivateChatPeerValue() != null -> {
                endPrivateChat()
                true
            }
            // Exit channel view
            state.getCurrentChannelValue() != null -> {
                switchToChannel(null)
                true
            }
            // No special navigation state - let system handle (usually exits app)
            else -> false
        }
    }
    
    // MARK: - Geohash Management
    
    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        return nostrGeohashService.geohashParticipantCount(geohash)
    }
    
    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        nostrGeohashService.beginGeohashSampling(geohashes)
    }
    
    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        nostrGeohashService.endGeohashSampling()
    }
    
    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return nostrGeohashService.isPersonTeleported(pubkeyHex)
    }
    
    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        nostrGeohashService.startGeohashDM(pubkeyHex) { convKey ->
            startPrivateChat(convKey)
        }
    }
    
    fun selectLocationChannel(channel: com.renchat.android.geohash.ChannelID) {
        nostrGeohashService.selectLocationChannel(channel)
    }
    
    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        nostrGeohashService.blockUserInGeohash(targetNickname)
    }
}
