package com.renchat.android.nostr

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.renchat.android.geohash.ChannelID
import com.renchat.android.geohash.GeohashChannel
import com.renchat.android.geohash.LocationChannelManager
import com.renchat.android.model.RenChatMessage
import kotlinx.coroutines.*
import java.util.*

/**
 * Manages Nostr-based geohash channels
 * Integrates with location channels for local area communication
 */
class NostrChannelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "NostrChannelManager"
        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.snort.social"
        )
    }
    
    private val relayConnections = mutableMapOf<String, NostrRelay>()
    private val activeSubscriptions = mutableMapOf<String, NostrSubscription>()
    private val locationChannelManager = LocationChannelManager.getInstance(context)
    
    private val _messages = MutableLiveData<List<RenChatMessage>>(emptyList())
    val messages: LiveData<List<RenChatMessage>> = _messages
    
    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected
    
    private val _activeChannel = MutableLiveData<ChannelID?>(null)
    val activeChannel: LiveData<ChannelID?> = _activeChannel
    
    private val messageHistory = mutableListOf<RenChatMessage>()
    private val seenEventIds = mutableSetOf<String>()
    
    fun initialize() {
        Log.d(TAG, "Initializing Nostr channel manager")
        
        // Observe location channel changes
        locationChannelManager.selectedChannel.observeForever { channel ->
            when (channel) {
                is ChannelID.Location -> {
                    switchToGeohashChannel(channel.channel)
                }
                is ChannelID.Mesh -> {
                    disconnectFromNostr()
                }
                null -> {
                    disconnectFromNostr()
                }
            }
        }
    }
    
    private fun switchToGeohashChannel(channel: GeohashChannel) {
        Log.d(TAG, "Switching to geohash channel: ${channel.geohash}")
        
        _activeChannel.value = ChannelID.Location(channel)
        
        // Clear previous messages for new channel
        messageHistory.clear()
        _messages.value = emptyList()
        seenEventIds.clear()
        
        // Connect to relays and subscribe to geohash channel
        connectToRelays()
        subscribeToGeohashChannel(channel.geohash)
    }
    
    private fun connectToRelays() {
        Log.d(TAG, "Connecting to Nostr relays")
        
        // Disconnect existing connections
        relayConnections.values.forEach { it.disconnect() }
        relayConnections.clear()
        
        // Connect to default relays
        DEFAULT_RELAYS.forEach { relayUrl ->
            val relay = NostrRelay(
                relayUrl = relayUrl,
                onMessage = ::handleNostrEvent,
                onConnectionChange = ::handleConnectionChange
            )
            
            relayConnections[relayUrl] = relay
            relay.connect()
        }
    }
    
    private fun subscribeToGeohashChannel(geohash: String) {
        Log.d(TAG, "Subscribing to geohash: $geohash")
        
        val subscriptionId = "geohash_$geohash"
        val filter = NostrFilter(
            kinds = listOf(1), // Text note kind
            tags = mapOf("g" to listOf(geohash)),
            limit = 100
        )
        
        val subscription = NostrSubscription(subscriptionId, filter)
        activeSubscriptions[subscriptionId] = subscription
        
        // Subscribe on all connected relays
        relayConnections.values.forEach { relay ->
            relay.subscribe(subscription)
        }
    }
    
    private fun handleNostrEvent(event: NostrEvent) {
        // Deduplicate events
        if (seenEventIds.contains(event.id)) {
            return
        }
        seenEventIds.add(event.id)
        
        Log.d(TAG, "Received Nostr event: ${event.id}")
        
        // Convert Nostr event to RenChat message
        val message = RenChatMessage(
            sender = extractNickname(event) ?: event.pubkey.take(8),
            content = event.content,
            timestamp = Date(event.created_at * 1000),
            isRelay = false,
            senderPeerID = event.pubkey,
            channel = getCurrentGeohash(),
            messageId = event.id
        )
        
        // Add to message history
        messageHistory.add(message)
        
        // Sort messages by timestamp and update live data
        messageHistory.sortBy { it.timestamp }
        _messages.value = messageHistory.toList()
        
        Log.d(TAG, "Added message from ${message.sender}: ${message.content}")
    }
    
    private fun extractNickname(event: NostrEvent): String? {
        // Look for nickname in tags or use a shortened pubkey
        event.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "nick") {
                return tag[1]
            }
        }
        return null
    }
    
    private fun getCurrentGeohash(): String? {
        return when (val channel = _activeChannel.value) {
            is ChannelID.Location -> channel.channel.geohash
            else -> null
        }
    }
    
    private fun handleConnectionChange(isConnected: Boolean) {
        val anyConnected = relayConnections.values.any { it.isConnected.value }
        _isConnected.value = anyConnected
        
        if (anyConnected) {
            Log.d(TAG, "Connected to at least one Nostr relay")
        } else {
            Log.w(TAG, "Disconnected from all Nostr relays")
        }
    }
    
    fun sendMessage(content: String, nickname: String) {
        val currentGeohash = getCurrentGeohash()
        if (currentGeohash == null) {
            Log.w(TAG, "No active geohash channel for sending message")
            return
        }
        
        Log.d(TAG, "Sending message to geohash: $currentGeohash")
        
        // Create Nostr event
        val event = createTextEvent(content, currentGeohash, nickname)
        
        // Publish to all connected relays
        relayConnections.values.forEach { relay ->
            relay.publishEvent(event)
        }
        
        // Add to local message history immediately
        val localMessage = RenChatMessage(
            sender = nickname,
            content = content,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = "local", // Placeholder for local user
            channel = currentGeohash,
            messageId = event.id
        )
        
        messageHistory.add(localMessage)
        messageHistory.sortBy { it.timestamp }
        _messages.value = messageHistory.toList()
    }
    
    private fun createTextEvent(content: String, geohash: String, nickname: String): NostrEvent {
        val now = System.currentTimeMillis() / 1000
        val pubkey = generateTempPubkey() // Simplified for demo
        
        val tags = listOf(
            listOf("g", geohash),
            listOf("nick", nickname)
        )
        
        // Simplified event creation (in production, would need proper signing)
        return NostrEvent(
            id = UUID.randomUUID().toString().replace("-", ""),
            pubkey = pubkey,
            created_at = now,
            kind = 1,
            tags = tags,
            content = content,
            sig = "temp_signature" // Placeholder
        )
    }
    
    private fun generateTempPubkey(): String {
        // Simplified temporary pubkey generation
        return UUID.randomUUID().toString().replace("-", "")
    }
    
    private fun disconnectFromNostr() {
        Log.d(TAG, "Disconnecting from Nostr")
        
        // Unsubscribe from active subscriptions
        activeSubscriptions.clear()
        
        // Disconnect from all relays
        relayConnections.values.forEach { it.disconnect() }
        relayConnections.clear()
        
        _isConnected.value = false
        _activeChannel.value = null
        
        // Clear messages when disconnecting
        messageHistory.clear()
        _messages.value = emptyList()
    }
    
    fun cleanup() {
        Log.d(TAG, "Cleaning up Nostr channel manager")
        disconnectFromNostr()
    }
}