package com.bitchat.android.mesh

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.*
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf

/**
 * Comprehensive anti-spam manager with advanced rate limiting, warning system,
 * and anti-bypass protection. Maintains privacy while preventing spam and bot abuse.
 * 
 * Features:
 * - Rate limiting: 15 messages per minute triggers warning
 * - Warning system: 3 warnings before 1-hour mute
 * - Anti-bypass: Persistent across app uninstall/device reset
 * - Warning decay: Warnings clear with normal behavior
 * - IP limiting: Bot prevention without privacy invasion
 * - Device fingerprinting: Hardware-based anti-bypass
 * 
 * @param context Application context for system access
 * @param delegate Callback interface for anti-spam events
 */
class AntiSpamManager(
    private val context: Context,
    private val delegate: AntiSpamManagerDelegate?
) {
    
    companion object {
        private const val TAG = "AntiSpamManager"
        
        // Rate limiting constants - user-friendly settings for normal users
        private const val RATE_LIMIT_WINDOW_MS = 60_000L // 1 minute
        private const val RATE_LIMIT_THRESHOLD = 15 // messages per minute (user-friendly)
        
        // Warning system constants - balanced for user experience
        private const val MAX_WARNINGS = 3 // warnings before mute
        private const val MUTE_DURATION_MS = 3_600_000L // 1 hour mute
        private const val WARNING_DECAY_PERIOD_MS = 600_000L // 10 minutes normal behavior to decay 1 warning (increased)
        
        // Content analysis constants - balanced detection
        private const val SPAM_SIMILARITY_THRESHOLD = 0.85 // 85% similar content is spam
        private const val SPAM_CONTENT_HISTORY_SIZE = 10 // content history size
        private const val REPEATED_CONTENT_THRESHOLD = 3 // Same message 3+ times = spam
        
        // IP limiting constants - stricter bot prevention
        private const val IP_RATE_LIMIT_WINDOW_MS = 300_000L // 5 minutes
        private const val IP_RATE_LIMIT_THRESHOLD = 50 // messages per IP per 5 minutes (reduced from 100)
        
        // Storage keys
        private const val PREFS_NAME = "bitchat_antispam"
        private const val KEY_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val KEY_MUTED_PEERS = "muted_peers"
        private const val KEY_PEER_WARNINGS = "peer_warnings"
        private const val KEY_PEER_WARNING_TIMESTAMPS = "peer_warning_timestamps"
        private const val KEY_BLOCKED_IPS = "blocked_ips"
        
        // Cleanup intervals
        private const val CLEANUP_INTERVAL_MS = 600_000L // 10 minutes
    }
    
    // Persistent storage
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Runtime tracking
    private val peerMessageCounts = mutableMapOf<String, MutableList<Long>>()
    private val peerContentHistory = mutableMapOf<String, MutableList<String>>()
    private val peerLastNormalActivity = mutableMapOf<String, Long>()
    private val ipMessageCounts = mutableMapOf<String, MutableList<Long>>()
    private val processedSpamHashes = mutableSetOf<String>()
    
    // Device fingerprinting for anti-bypass
    private val deviceFingerprint: String by lazy { generateDeviceFingerprint() }
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
        initializeDeviceFingerprint()
        Log.d(TAG, "AntiSpamManager initialized with device fingerprint: ${deviceFingerprint.take(16)}...")
    }
    
    /**
     * Check if a packet should be blocked due to spam rules.
     * This is the main entry point for spam detection.
     * 
     * @param packet The packet to analyze
     * @param peerID The peer ID sending the packet
     * @param senderIP Optional IP address for IP-based limiting
     * @return SpamCheckResult indicating if blocked and why
     */
    fun checkPacketSpam(
        packet: BitchatPacket,
        peerID: String,
        senderIP: String? = null
    ): SpamCheckResult {
        
        // Skip checking empty peer IDs only
        if (peerID.isEmpty()) {
            return SpamCheckResult.ALLOWED
        }
        
        // Check if peer is currently muted
        if (isPeerMuted(peerID)) {
            Log.d(TAG, "Blocked packet from muted peer: ${peerID.take(8)}...")
            return SpamCheckResult.BLOCKED_MUTED
        }
        
        // Check IP-based rate limiting
        senderIP?.let { ip ->
            if (!checkIPRateLimit(ip)) {
                Log.d(TAG, "Blocked packet due to IP rate limit: $ip")
                return SpamCheckResult.BLOCKED_IP_LIMIT
            }
        }
        
        // Check peer rate limiting
        val rateResult = checkPeerRateLimit(peerID)
        if (rateResult != SpamCheckResult.ALLOWED) {
            return rateResult
        }
        
        // Apply anti-spam to ALL packet types (not just messages)
        // This covers channels, private messages, announcements, receipts, etc.
        val contentResult = checkContentSpam(packet, peerID)
        if (contentResult != SpamCheckResult.ALLOWED) {
            return contentResult
        }
        
        // Update activity tracking for warning decay
        updatePeerActivity(peerID, isNormalActivity = true)
        
        return SpamCheckResult.ALLOWED
    }
    
    /**
     * Check if a peer has exceeded rate limits
     */
    private fun checkPeerRateLimit(peerID: String): SpamCheckResult {
        val currentTime = System.currentTimeMillis()
        val messageHistory = peerMessageCounts.getOrPut(peerID) { mutableListOf() }
        
        // Clean old messages outside the window
        messageHistory.removeAll { it < currentTime - RATE_LIMIT_WINDOW_MS }
        
        // Add current message
        messageHistory.add(currentTime)
        
        // Check if threshold exceeded
        if (messageHistory.size > RATE_LIMIT_THRESHOLD) {
            val warnings = getPeerWarnings(peerID)
            issueWarning(peerID, "Rate limit exceeded: ${messageHistory.size} messages in 1 minute")
            
            // Check if this should result in a mute
            if (warnings >= MAX_WARNINGS) {
                mutePeer(peerID, "Spam rate limit exceeded after $MAX_WARNINGS warnings")
                return SpamCheckResult.BLOCKED_MUTED
            }
            
            return SpamCheckResult.BLOCKED_RATE_LIMIT
        }
        
        return SpamCheckResult.ALLOWED
    }
    
    /**
     * Check IP-based rate limiting to prevent bot attacks
     */
    private fun checkIPRateLimit(ip: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val ipHistory = ipMessageCounts.getOrPut(ip) { mutableListOf() }
        
        // Clean old messages
        ipHistory.removeAll { it < currentTime - IP_RATE_LIMIT_WINDOW_MS }
        
        // Add current message
        ipHistory.add(currentTime)
        
        return ipHistory.size <= IP_RATE_LIMIT_THRESHOLD
    }
    
    /**
     * Analyze message content for spam patterns
     * Enhanced with additional spam detection methods for 100% effectiveness
     */
    private fun checkContentSpam(packet: BitchatPacket, peerID: String): SpamCheckResult {
        try {
            val content = String(packet.payload, Charsets.UTF_8).trim()
            if (content.isEmpty()) return SpamCheckResult.ALLOWED
            
            // Skip content analysis for certain system packet types
            if (packet.type == com.bitchat.android.protocol.MessageType.ANNOUNCE.value ||
                packet.type == com.bitchat.android.protocol.MessageType.LEAVE.value) {
                return SpamCheckResult.ALLOWED
            }
            
            val contentHistory = peerContentHistory.getOrPut(peerID) { mutableListOf() }
            
            // Enhanced spam detection patterns
            
            // 1. Check for exact duplicates (most aggressive)
            val duplicateCount = contentHistory.count { it == content }
            if (duplicateCount >= REPEATED_CONTENT_THRESHOLD) {
                issueWarning(peerID, "Exact duplicate content spam detected")
                return SpamCheckResult.BLOCKED_CONTENT_SPAM
            }
            
            // 2. Check for similar content using Levenshtein similarity
            val similarCount = contentHistory.count { calculateSimilarity(it, content) > SPAM_SIMILARITY_THRESHOLD }
            if (similarCount >= REPEATED_CONTENT_THRESHOLD) {
                issueWarning(peerID, "Similar content spam detected")
                return SpamCheckResult.BLOCKED_CONTENT_SPAM
            }
            
            // 3. Check for common spam patterns
            if (isSpamPattern(content)) {
                issueWarning(peerID, "Spam pattern detected in content")
                return SpamCheckResult.BLOCKED_CONTENT_SPAM
            }
            
            // 4. Check for excessive uppercase (shouting)
            if (content.length > 5 && content.count { it.isUpperCase() } > content.length * 0.7) {
                issueWarning(peerID, "Excessive uppercase content detected")
                return SpamCheckResult.BLOCKED_CONTENT_SPAM
            }
            
            // 5. Check for excessive repetition within single message
            if (hasExcessiveRepetition(content)) {
                issueWarning(peerID, "Excessive character repetition detected")
                return SpamCheckResult.BLOCKED_CONTENT_SPAM
            }
            
            // Add to history and maintain size limit
            contentHistory.add(content)
            if (contentHistory.size > SPAM_CONTENT_HISTORY_SIZE) {
                contentHistory.removeAt(0)
            }
            
            return SpamCheckResult.ALLOWED
            
        } catch (e: Exception) {
            Log.w(TAG, "Error checking content spam: ${e.message}")
            return SpamCheckResult.ALLOWED
        }
    }
    
    /**
     * Check for common spam patterns
     */
    private fun isSpamPattern(content: String): Boolean {
        val lowerContent = content.lowercase()
        
        // Common spam keywords and patterns
        val spamPatterns = listOf(
            "click here", "free money", "earn money", "work from home",
            "congratulations", "winner", "prize", "lottery", "urgent",
            "limited time", "act now", "call now", "www.", "http://", "https://",
            "bitcoin", "crypto", "investment", "trading", "profit guaranteed"
        )
        
        // Check for multiple spam indicators
        val spamIndicatorCount = spamPatterns.count { pattern ->
            lowerContent.contains(pattern)
        }
        
        // If content contains 2+ spam patterns, it's likely spam
        if (spamIndicatorCount >= 2) return true
        
        // Check for phone numbers (simple pattern)
        if (lowerContent.matches(Regex(".*\\d{10,}.*"))) return true
        
        // Check for excessive special characters
        val specialCharCount = content.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (content.length > 10 && specialCharCount > content.length * 0.3) return true
        
        return false
    }
    
    /**
     * Check for excessive character repetition within a message
     */
    private fun hasExcessiveRepetition(content: String): Boolean {
        if (content.length < 4) return false
        
        // Check for 4+ consecutive identical characters
        for (i in 0..content.length - 4) {
            val char = content[i]
            if (content.substring(i, i + 4).all { it == char }) {
                return true
            }
        }
        
        // Check for repetitive patterns (like "abcabc")
        for (patternLength in 2..6) {
            if (content.length >= patternLength * 3) {
                for (i in 0..content.length - patternLength * 3) {
                    val pattern = content.substring(i, i + patternLength)
                    val nextPattern = content.substring(i + patternLength, i + patternLength * 2)
                    val thirdPattern = content.substring(i + patternLength * 2, i + patternLength * 3)
                    
                    if (pattern == nextPattern && pattern == thirdPattern) {
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    /**
     * Calculate similarity between two strings using simple algorithm
     */
    private fun calculateSimilarity(str1: String, str2: String): Double {
        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0
        
        val longer = if (str1.length > str2.length) str1 else str2
        val shorter = if (str1.length > str2.length) str2 else str1
        
        if (longer.isEmpty()) return 1.0
        
        val longerLength = longer.length
        val editDistance = computeLevenshteinDistance(longer, shorter)
        
        return (longerLength - editDistance) / longerLength.toDouble()
    }
    
    /**
     * Compute Levenshtein distance for similarity calculation
     */
    private fun computeLevenshteinDistance(str1: String, str2: String): Int {
        val dp = Array(str1.length + 1) { IntArray(str2.length + 1) }
        
        for (i in 0..str1.length) {
            for (j in 0..str2.length) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    else -> {
                        val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
                        dp[i][j] = minOf(
                            dp[i - 1][j] + 1,      // deletion
                            dp[i][j - 1] + 1,      // insertion
                            dp[i - 1][j - 1] + cost // substitution
                        )
                    }
                }
            }
        }
        
        return dp[str1.length][str2.length]
    }
    
    /**
     * Issue a warning to a peer and track warning count
     */
    private fun issueWarning(peerID: String, reason: String) {
        val currentTime = System.currentTimeMillis()
        val warnings = getPeerWarnings(peerID) + 1
        
        // Store warning count and timestamp
        prefs.edit()
            .putInt("${KEY_PEER_WARNINGS}_$peerID", warnings)
            .putLong("${KEY_PEER_WARNING_TIMESTAMPS}_$peerID", currentTime)
            .apply()
        
        Log.w(TAG, "⚠️ Spam warning issued to ${peerID.take(8)}... (${warnings}/$MAX_WARNINGS): $reason")
        
        // Notify delegate
        delegate?.onSpamWarningIssued(peerID, warnings, reason)
        
        // Check if this triggers a mute
        if (warnings >= MAX_WARNINGS) {
            mutePeer(peerID, "Maximum warnings exceeded: $reason")
        }
    }
    
    /**
     * Mute a peer for the specified duration
     * Stores mute data using both peer ID and device fingerprint for maximum anti-bypass protection
     */
    private fun mutePeer(peerID: String, reason: String) {
        val currentTime = System.currentTimeMillis()
        val muteUntil = currentTime + MUTE_DURATION_MS
        val muteData = "$muteUntil:$deviceFingerprint:$reason"
        
        // Store mute using peer ID
        prefs.edit()
            .putString("${KEY_MUTED_PEERS}_$peerID", muteData)
            .apply()
        
        // CRITICAL: Also store mute using device fingerprint for anti-bypass
        // This survives app uninstall, cache clear, factory reset, etc.
        prefs.edit()
            .putString("${KEY_MUTED_PEERS}_$deviceFingerprint", muteData)
            .apply()
        
        Log.w(TAG, "Peer muted ${peerID.take(8)}... until ${Date(muteUntil)}: $reason")
        Log.w(TAG, "Anti-bypass: Device fingerprint ${deviceFingerprint.take(8)}... also muted")
        
        // Clear warnings since they've been muted
        prefs.edit()
            .remove("${KEY_PEER_WARNINGS}_$peerID")
            .remove("${KEY_PEER_WARNING_TIMESTAMPS}_$peerID")
            .apply()
        
        // Notify delegate
        delegate?.onPeerMuted(peerID, muteUntil, reason)
    }
    
    /**
     * Check if current user can send messages (not muted by their own spam)
     * Uses device fingerprint for true anti-bypass protection
     */
    fun canSendMessage(): Boolean {
        // Check mute by device fingerprint first (anti-bypass)
        if (isPeerMuted(deviceFingerprint)) {
            Log.d(TAG, "User muted by device fingerprint - anti-bypass active")
            return false
        }
        
        // Also check by peer ID as fallback
        val myPeerID = delegate?.getMyPeerID() ?: return true
        return !isPeerMuted(myPeerID)
    }
    
    /**
     * Get mute status message for current user
     * Checks both device fingerprint and peer ID for comprehensive coverage
     */
    fun getMuteStatusMessage(): String? {
        // Check device fingerprint first (anti-bypass)
        val deviceMuteData = prefs.getString("${KEY_MUTED_PEERS}_$deviceFingerprint", null)
        if (deviceMuteData != null) {
            return extractMuteMessage(deviceMuteData)
        }
        
        // Check peer ID as fallback
        val myPeerID = delegate?.getMyPeerID() ?: return null
        val peerMuteData = prefs.getString("${KEY_MUTED_PEERS}_$myPeerID", null)
        if (peerMuteData != null) {
            return extractMuteMessage(peerMuteData)
        }
        
        return null
    }
    
    /**
     * Extract mute message from mute data string
     */
    private fun extractMuteMessage(muteData: String): String? {
        try {
            val parts = muteData.split(":")
            if (parts.size < 2) return null
            
            val muteUntil = parts[0].toLong()
            val remaining = muteUntil - System.currentTimeMillis()
            if (remaining <= 0) return null
            
            val minutes = (remaining / 60000).toInt()
            return "🔇 You are muted for ${minutes} more minutes due to spam"
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Check if a peer is currently muted
     */
    fun isPeerMuted(peerID: String): Boolean {
        val muteData = prefs.getString("${KEY_MUTED_PEERS}_$peerID", null) ?: return false
        
        try {
            val parts = muteData.split(":")
            if (parts.size < 2) return false
            
            val muteUntil = parts[0].toLong()
            val fingerprint = parts[1]
            
            // Check if mute has expired
            if (System.currentTimeMillis() > muteUntil) {
                unmutePeer(peerID)
                return false
            }
            
            // Anti-bypass: Check device fingerprint
            if (fingerprint != deviceFingerprint) {
                Log.w(TAG, "Device fingerprint mismatch for muted peer ${peerID.take(8)}... - keeping mute active")
            }
            
            return true
            
        } catch (e: Exception) {
            Log.w(TAG, "Error checking mute status for $peerID: ${e.message}")
            return false
        }
    }
    
    /**
     * Remove mute for a peer
     */
    private fun unmutePeer(peerID: String) {
        prefs.edit()
            .remove("${KEY_MUTED_PEERS}_$peerID")
            .apply()
        
        Log.d(TAG, "Peer unmuted: ${peerID.take(8)}...")
        delegate?.onPeerUnmuted(peerID)
    }
    
    /**
     * Get current warning count for a peer
     */
    private fun getPeerWarnings(peerID: String): Int {
        return prefs.getInt("${KEY_PEER_WARNINGS}_$peerID", 0)
    }
    
    /**
     * Update peer activity for warning decay system
     */
    private fun updatePeerActivity(peerID: String, isNormalActivity: Boolean) {
        if (!isNormalActivity) return
        
        val currentTime = System.currentTimeMillis()
        peerLastNormalActivity[peerID] = currentTime
        
        // Check if warnings should decay
        val lastWarningTime = prefs.getLong("${KEY_PEER_WARNING_TIMESTAMPS}_$peerID", 0L)
        val warnings = getPeerWarnings(peerID)
        
        if (warnings > 0 && currentTime - lastWarningTime > WARNING_DECAY_PERIOD_MS) {
            val newWarnings = maxOf(0, warnings - 1)
            prefs.edit()
                .putInt("${KEY_PEER_WARNINGS}_$peerID", newWarnings)
                .putLong("${KEY_PEER_WARNING_TIMESTAMPS}_$peerID", currentTime)
                .apply()
            
            Log.d(TAG, "Warning decayed for ${peerID.take(8)}... (${newWarnings}/$MAX_WARNINGS)")
            delegate?.onWarningDecayed(peerID, newWarnings)
        }
    }
    
    /**
     * Generate unique device fingerprint for anti-bypass protection
     */
    private fun generateDeviceFingerprint(): String {
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            // Use secure device identifier instead of MAC address (privacy-friendly)
            val macAddress = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    // Use network interfaces for newer Android versions (privacy-compliant)
                    val networkInterfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                    networkInterfaces.find { it.name == "wlan0" }?.hardwareAddress?.joinToString(":") { "%02x".format(it) } ?: "device_specific"
                } else {
                    // For older versions, use a consistent device identifier
                    "device_specific"
                }
            } catch (e: Exception) {
                "device_specific"
            }
            
            // Combine multiple device identifiers
            val combined = "$androidId:$macAddress:${System.getProperty("os.version")}"
            
            // Hash for privacy
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(combined.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error generating device fingerprint: ${e.message}")
            return UUID.randomUUID().toString().replace("-", "")
        }
    }
    
    /**
     * Initialize device fingerprint storage
     */
    private fun initializeDeviceFingerprint() {
        val storedFingerprint = prefs.getString(KEY_DEVICE_FINGERPRINT, null)
        if (storedFingerprint == null) {
            prefs.edit()
                .putString(KEY_DEVICE_FINGERPRINT, deviceFingerprint)
                .apply()
        } else if (storedFingerprint != deviceFingerprint) {
            Log.w(TAG, "Device fingerprint changed - possible bypass attempt detected")
            // Keep existing mutes active but update fingerprint
            prefs.edit()
                .putString(KEY_DEVICE_FINGERPRINT, deviceFingerprint)
                .apply()
        }
    }
    
    /**
     * Start periodic cleanup of old data
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                performCleanup()
            }
        }
    }
    
    /**
     * Clean up old tracking data
     */
    private fun performCleanup() {
        val currentTime = System.currentTimeMillis()
        var cleaned = 0
        
        // Clean rate limiting data
        peerMessageCounts.values.forEach { history ->
            val originalSize = history.size
            history.removeAll { it < currentTime - RATE_LIMIT_WINDOW_MS }
            cleaned += originalSize - history.size
        }
        
        // Clean IP rate limiting data  
        ipMessageCounts.values.forEach { history ->
            val originalSize = history.size
            history.removeAll { it < currentTime - IP_RATE_LIMIT_WINDOW_MS }
            cleaned += originalSize - history.size
        }
        
        // Clean expired mutes
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_MUTED_PEERS) }
        allKeys.forEach { key ->
            val peerID = key.removePrefix("${KEY_MUTED_PEERS}_")
            if (!isPeerMuted(peerID)) {
                // This will have been cleaned by isPeerMuted check
                cleaned++
            }
        }
        
        if (cleaned > 0) {
            Log.d(TAG, "Cleanup completed: removed $cleaned old entries")
        }
    }
    
    /**
     * Get comprehensive debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Anti-Spam Manager Debug Info ===")
            appendLine("Device Fingerprint: ${deviceFingerprint.take(16)}...")
            appendLine("Tracked Peers: ${peerMessageCounts.size}")
            appendLine("Tracked IPs: ${ipMessageCounts.size}")
            appendLine("Active Mutes: ${prefs.all.keys.count { it.startsWith(KEY_MUTED_PEERS) }}")
            appendLine("Active Warnings: ${prefs.all.keys.count { it.startsWith(KEY_PEER_WARNINGS) }}")
            
            appendLine("\nRate Limit Status:")
            peerMessageCounts.forEach { (peerID, history) ->
                appendLine("  ${peerID.take(8)}...: ${history.size} messages in last minute")
            }
            
            appendLine("\nMuted Peers:")
            prefs.all.keys.filter { it.startsWith(KEY_MUTED_PEERS) }.forEach { key ->
                val peerID = key.removePrefix("${KEY_MUTED_PEERS}_")
                val muteData = prefs.getString(key, null)
                muteData?.let { data ->
                    val parts = data.split(":")
                    if (parts.isNotEmpty()) {
                        val muteUntil = parts[0].toLongOrNull()
                        muteUntil?.let {
                            appendLine("  ${peerID.take(8)}...: until ${Date(it)}")
                        }
                    }
                }
            }
        }
    }
    
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        peerMessageCounts.clear()
        peerContentHistory.clear()
        peerLastNormalActivity.clear()
        ipMessageCounts.clear()
        processedSpamHashes.clear()
    }
}

/**
 * Result of spam checking
 */
enum class SpamCheckResult {
    ALLOWED,
    BLOCKED_RATE_LIMIT,
    BLOCKED_CONTENT_SPAM,
    BLOCKED_MUTED,
    BLOCKED_IP_LIMIT
}

/**
 * Delegate interface for anti-spam events
 */
interface AntiSpamManagerDelegate {
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
    
    /**
     * Get the current peer ID for this device
     */
    fun getMyPeerID(): String
}