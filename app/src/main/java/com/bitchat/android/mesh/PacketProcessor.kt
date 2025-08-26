package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.RoutedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Processes incoming packets and routes them to appropriate handlers
 * 
 * Per-peer packet serialization using Kotlin coroutine actors
 * Prevents race condition where multiple threads process packets
 * from the same peer simultaneously, causing session management conflicts.
 * 
 * Includes comprehensive anti-spam protection with rate limiting,
 * warning system, and persistent mute tracking.
 */
class PacketProcessor(
    private val myPeerID: String,
    private val context: Context
) {
    
    companion object {
        private const val TAG = "PacketProcessor"
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Anti-spam manager for comprehensive spam protection
    private val antiSpamManager = AntiSpamManager(context, object : AntiSpamManagerDelegate {
        override fun onSpamWarningIssued(peerID: String, warningCount: Int, reason: String) {
            delegate?.onSpamWarningIssued(peerID, warningCount, reason)
        }
        
        override fun onPeerMuted(peerID: String, muteUntil: Long, reason: String) {
            delegate?.onPeerMuted(peerID, muteUntil, reason)
        }
        
        override fun onPeerUnmuted(peerID: String) {
            delegate?.onPeerUnmuted(peerID)
        }
        
        override fun onWarningDecayed(peerID: String, remainingWarnings: Int) {
            delegate?.onWarningDecayed(peerID, remainingWarnings)
        }
        
        override fun getMyPeerID(): String = myPeerID
    })
    
    // Helper function to format peer ID with nickname for logging
    private fun formatPeerForLog(peerID: String): String {
        val nickname = delegate?.getPeerNickname(peerID)
        return if (nickname != null) "$peerID ($nickname)" else peerID
    }
    
    // Packet relay manager for centralized relay decisions
    private val packetRelayManager = PacketRelayManager(myPeerID)
    
    // Coroutines
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Per-peer channels to serialize packet processing
    // Each peer gets its own channel that processes packets sequentially
    // This prevents race conditions in session management
    private val peerChannels = mutableMapOf<String, Channel<RoutedPacket>>()
    private val peerJobs = mutableMapOf<String, Job>()
    
    private fun getOrCreateChannelForPeer(peerID: String): Channel<RoutedPacket> {
        return peerChannels.getOrPut(peerID) {
            val channel = Channel<RoutedPacket>(Channel.UNLIMITED)
            val job = processorScope.launch {
                Log.d(TAG, "🎭 Created packet processor for peer: ${formatPeerForLog(peerID)}")
                try {
                    for (packet in channel) {
                        Log.d(TAG, "📦 Processing packet type ${packet.packet.type} from ${formatPeerForLog(peerID)} (serialized)")
                        handleReceivedPacket(packet)
                        Log.d(TAG, "Completed packet type ${packet.packet.type} from ${formatPeerForLog(peerID)}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in packet processor for ${formatPeerForLog(peerID)}: ${e.message}")
                } finally {
                    Log.d(TAG, "🎭 Packet processor for ${formatPeerForLog(peerID)} terminated")
                }
            }
            peerJobs[peerID] = job
            channel
        }
    }
    
    init {
        // Set up the packet relay manager delegate immediately
        setupRelayManager()
    }
    
    /**
     * Process received packet - main entry point for all incoming packets
     * SURGICAL FIX: Route to per-peer actor for serialized processing
     */
    fun processPacket(routed: RoutedPacket) {
        Log.d(TAG, "processPacket ${routed.packet.type}")
        val peerID = routed.peerID

        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping")
            return
        }


        
        // Get or create channel for this peer
        val channel = getOrCreateChannelForPeer(peerID)
        
        // Send packet to peer's dedicated channel for serialized processing
        processorScope.launch {
            try {
                channel.send(routed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send packet to channel for ${formatPeerForLog(peerID)}: ${e.message}")
                // Fallback to direct processing if channel fails
                handleReceivedPacket(routed)
            }
        }
    }
    
    /**
     * Set up the packet relay manager with its delegate
     */
    fun setupRelayManager() {
        packetRelayManager.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int {
                return delegate?.getNetworkSize() ?: 1
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return delegate?.getBroadcastRecipient() ?: ByteArray(0)
            }
            
            override fun broadcastPacket(routed: RoutedPacket) {
                delegate?.relayPacket(routed)
            }
        }
    }
    
    /**
     * Handle received packet - core protocol logic with anti-spam protection
     */
    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Basic validation and security checks
        if (!delegate?.validatePacketSecurity(packet, peerID)!!) {
            Log.d(TAG, "Packet failed security validation from ${formatPeerForLog(peerID)}")
            return
        }
        
        // Anti-spam protection - check if packet should be blocked
        val spamResult = antiSpamManager.checkPacketSpam(packet, peerID, routed.relayAddress)
        when (spamResult) {
            SpamCheckResult.BLOCKED_RATE_LIMIT -> {
                Log.w(TAG, "Packet blocked due to rate limit from ${formatPeerForLog(peerID)}")
                return
            }
            SpamCheckResult.BLOCKED_CONTENT_SPAM -> {
                Log.w(TAG, "Packet blocked due to content spam from ${formatPeerForLog(peerID)}")
                return
            }
            SpamCheckResult.BLOCKED_MUTED -> {
                Log.d(TAG, "Packet blocked from muted peer ${formatPeerForLog(peerID)}")
                return
            }
            SpamCheckResult.BLOCKED_IP_LIMIT -> {
                Log.w(TAG, "Packet blocked due to IP rate limit from ${formatPeerForLog(peerID)}")
                return
            }
            SpamCheckResult.ALLOWED -> {
                // Continue processing
            }
        }

        var validPacket = true
        Log.d(TAG, "Processing packet type ${MessageType.fromValue(packet.type)} from ${formatPeerForLog(peerID)}")
        val messageType = MessageType.fromValue(packet.type)
        
        // Handle public packet types (no address check needed)
        when (messageType) {
            MessageType.ANNOUNCE -> handleAnnounce(routed)
            MessageType.MESSAGE -> handleMessage(routed)
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT -> handleFragment(routed)
            else -> {
                // Handle private packet types (address check required)
                if (packetRelayManager.isPacketAddressedToMe(packet)) {
                    when (messageType) {
                        MessageType.NOISE_HANDSHAKE -> handleNoiseHandshake(routed)
                        MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(routed)
                        else -> {
                            validPacket = false
                            Log.w(TAG, "Unknown message type: ${packet.type}")
                        }
                    }
                } else {
                    Log.d(TAG, "Private packet type ${messageType} not addressed to us (from: ${formatPeerForLog(peerID)} to ${packet.recipientID?.let { it.joinToString("") { b -> "%02x".format(b) } }}), skipping")
                }
            }
        }
        
        // Update last seen timestamp
        if (validPacket) {
            delegate?.updatePeerLastSeen(peerID)
            
            // CENTRALIZED RELAY LOGIC: Handle relay decisions for all packets not addressed to us
            packetRelayManager.handlePacketRelay(routed)
        }
    }
    
    /**
     * Handle Noise handshake message - SIMPLIFIED iOS-compatible version
     */
    private suspend fun handleNoiseHandshake(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise handshake from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseHandshake(routed)
    }
    
    /**
     * Handle Noise encrypted transport message
     */
    private suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise encrypted message from ${formatPeerForLog(peerID)}")
        delegate?.handleNoiseEncrypted(routed)
    }
    
    /**
     * Handle announce message
     */
    private suspend fun handleAnnounce(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing announce from ${formatPeerForLog(peerID)}")
        delegate?.handleAnnounce(routed)
    }
    
    /**
     * Handle regular message
     */
    private suspend fun handleMessage(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing message from ${formatPeerForLog(peerID)}")
        delegate?.handleMessage(routed)
    }
    
    /**
     * Handle leave message
     */
    private suspend fun handleLeave(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing leave from ${formatPeerForLog(peerID)}")
        delegate?.handleLeave(routed)
    }
    
    /**
     * Handle message fragments
     */
    private suspend fun handleFragment(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing fragment from ${formatPeerForLog(peerID)}")
        
        val reassembledPacket = delegate?.handleFragment(routed.packet)
        if (reassembledPacket != null) {
            Log.d(TAG, "Fragment reassembled, processing complete message")
            handleReceivedPacket(RoutedPacket(reassembledPacket, routed.peerID, routed.relayAddress))
        }
        
        // Fragment relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Handle delivery acknowledgment
     */
//    private suspend fun handleDeliveryAck(routed: RoutedPacket) {
//        val peerID = routed.peerID ?: "unknown"
//        Log.d(TAG, "Processing delivery ACK from ${formatPeerForLog(peerID)}")
//        delegate?.handleDeliveryAck(routed)
//    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            appendLine("Active Peer Channels: ${peerChannels.size}")
            appendLine("My Peer ID: $myPeerID")
            
            if (peerChannels.isNotEmpty()) {
                appendLine("Peer Channels:")
                peerChannels.keys.forEach { peerID ->
                    appendLine("  - $peerID")
                }
            }
        }
    }
    
    /**
     * Get anti-spam debug information
     */
    fun getAntiSpamDebugInfo(): String {
        return antiSpamManager.getDebugInfo()
    }
    
    /**
     * Check if a peer is currently muted by anti-spam system
     */
    fun isPeerMuted(peerID: String): Boolean {
        return antiSpamManager.isPeerMuted(peerID)
    }
    
    /**
     * Check if current user can send messages (not muted)
     */
    fun canSendMessage(): Boolean {
        return antiSpamManager.canSendMessage()
    }
    
    /**
     * Check outgoing message for spam before sending
     */
    fun checkOutgoingSpam(packet: BitchatPacket, peerID: String): SpamCheckResult {
        return antiSpamManager.checkPacketSpam(packet, peerID, null)
    }
    
    /**
     * Shutdown the packet processor and clean up resources
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketProcessor")
        
        // Close all channels
        peerChannels.values.forEach { channel ->
            channel.close()
        }
        peerChannels.clear()
        
        // Cancel all jobs
        peerJobs.values.forEach { job ->
            job.cancel()
        }
        peerJobs.clear()
        
        // Cancel the processor scope
        processorScope.cancel()
        
        Log.d(TAG, "PacketProcessor shutdown complete")
    }
}

/**
 * Delegate interface for packet processor callbacks
 */
interface PacketProcessorDelegate {
    // Security validation
    fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean
    
    // Peer management
    fun updatePeerLastSeen(peerID: String)
    fun getPeerNickname(peerID: String): String?
    
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Message type handlers
    fun handleNoiseHandshake(routed: RoutedPacket): Boolean
    fun handleNoiseEncrypted(routed: RoutedPacket)
    fun handleAnnounce(routed: RoutedPacket)
    fun handleMessage(routed: RoutedPacket)
    fun handleLeave(routed: RoutedPacket)
    fun handleFragment(packet: BitchatPacket): BitchatPacket?
    
    // Communication
    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
    
    // Anti-spam callbacks
    /**
     * Called when a spam warning is issued to a peer
     */
    fun onSpamWarningIssued(peerID: String, warningCount: Int, reason: String)
    
    /**
     * Called when a peer is muted for spam
     */
    fun onPeerMuted(peerID: String, muteUntil: Long, reason: String)
    
    /**
     * Called when a peer is unmuted
     */
    fun onPeerUnmuted(peerID: String)
    
    /**
     * Called when a warning decays due to good behavior
     */
    fun onWarningDecayed(peerID: String, remainingWarnings: Int)
}
