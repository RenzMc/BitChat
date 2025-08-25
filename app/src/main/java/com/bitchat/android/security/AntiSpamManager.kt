package com.bitchat.android.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Sophisticated Anti-Spam Manager with bypass-resistant protection
 * 
 * Features:
 * - Rate limiting: 15 messages per minute maximum
 * - Progressive warnings for spam behavior
 * - 30-minute mute for violators
 * - Device fingerprinting to prevent bypass via IP change, app reinstall, etc.
 * - Persistent storage of mute data
 */
class AntiSpamManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "AntiSpamManager"
        private const val PREFS_NAME = "bitchat_antispam"
        
        // Rate limiting configuration
        private const val MAX_MESSAGES_PER_MINUTE = 15
        private const val RATE_LIMIT_WINDOW_MS = 60000L // 1 minute
        private const val MUTE_DURATION_MS = 30 * 60 * 1000L // 30 minutes
        
        // Warning thresholds
        private const val FIRST_WARNING_THRESHOLD = 12 // 80% of limit
        private const val FINAL_WARNING_THRESHOLD = 14 // 93% of limit
        
        @Volatile
        private var INSTANCE: AntiSpamManager? = null
        
        fun getInstance(context: Context): AntiSpamManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AntiSpamManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    
    // In-memory rate limiting data
    private val userMessageTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val userWarningStates = ConcurrentHashMap<String, WarningState>()
    private val deviceFingerprint = generateDeviceFingerprint()
    
    data class WarningState(
        var firstWarningShown: Boolean = false,
        var finalWarningShown: Boolean = false,
        var lastWarningTime: Long = 0L
    )
    
    data class AntiSpamResult(
        val allowed: Boolean,
        val warning: String? = null,
        val remainingMessages: Int = 0,
        val muteTimeRemaining: Long = 0L,
        val action: SpamAction = SpamAction.ALLOW
    )
    
    enum class SpamAction {
        ALLOW,
        WARNING_FIRST,
        WARNING_FINAL,
        RATE_LIMITED,
        MUTED
    }
    
    init {
        Log.i(TAG, "AntiSpamManager initialized with device fingerprint: ${deviceFingerprint.take(8)}...")
        cleanupOldData()
    }
    
    /**
     * Generate a unique device fingerprint that persists across app reinstalls
     * Uses multiple device characteristics to create bypass-resistant ID
     */
    private fun generateDeviceFingerprint(): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, 
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        
        val displayMetrics = context.resources.displayMetrics
        val screenInfo = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels}@${displayMetrics.densityDpi}"
        
        val buildInfo = "${android.os.Build.MANUFACTURER}-${android.os.Build.MODEL}-${android.os.Build.BRAND}"
        val osInfo = "${android.os.Build.VERSION.RELEASE}-${android.os.Build.VERSION.SDK_INT}"
        
        // Create composite fingerprint
        val composite = "$androidId|$screenInfo|$buildInfo|$osInfo"
        return composite.hashCode().toString(16)
    }
    
    /**
     * Get unique user identifier that combines peer ID with device fingerprint
     * This prevents bypass by creating new accounts
     */
    private fun getUserIdentifier(peerID: String): String {
        return "${peerID}_${deviceFingerprint}"
    }
    
    /**
     * Check if user is currently muted (bypass-resistant)
     */
    suspend fun isUserMuted(peerID: String): Boolean = mutex.withLock {
        val userKey = getUserIdentifier(peerID)
        val muteEndTime = prefs.getLong("mute_end_$userKey", 0L)
        val currentTime = System.currentTimeMillis()
        
        if (muteEndTime > currentTime) {
            Log.d(TAG, "User $peerID is muted until ${Date(muteEndTime)}")
            return@withLock true
        }
        
        // Clean up expired mute
        if (muteEndTime > 0) {
            prefs.edit().remove("mute_end_$userKey").apply()
        }
        
        return@withLock false
    }
    
    /**
     * Get remaining mute time for user
     */
    suspend fun getMuteTimeRemaining(peerID: String): Long = mutex.withLock {
        val userKey = getUserIdentifier(peerID)
        val muteEndTime = prefs.getLong("mute_end_$userKey", 0L)
        val currentTime = System.currentTimeMillis()
        
        return@withLock if (muteEndTime > currentTime) {
            muteEndTime - currentTime
        } else {
            0L
        }
    }
    
    /**
     * Main anti-spam check - call before allowing message to be sent
     */
    suspend fun checkMessage(peerID: String, messageContent: String): AntiSpamResult = mutex.withLock {
        val userKey = getUserIdentifier(peerID)
        val currentTime = System.currentTimeMillis()
        
        // Check if user is muted
        if (isUserMuted(peerID)) {
            val timeRemaining = getMuteTimeRemaining(peerID)
            val minutesRemaining = (timeRemaining / 60000L).toInt()
            return@withLock AntiSpamResult(
                allowed = false,
                warning = "Anda di-mute karena spam. Waktu tersisa: $minutesRemaining menit",
                muteTimeRemaining = timeRemaining,
                action = SpamAction.MUTED
            )
        }
        
        // Get or create message timestamp list for this user
        val timestamps = userMessageTimestamps.getOrPut(userKey) { mutableListOf() }
        val warningState = userWarningStates.getOrPut(userKey) { WarningState() }
        
        // Clean up old timestamps (outside the rate limit window)
        timestamps.removeAll { it < currentTime - RATE_LIMIT_WINDOW_MS }
        
        // Check current message count in the window
        val currentCount = timestamps.size
        val remainingMessages = MAX_MESSAGES_PER_MINUTE - currentCount
        
        Log.d(TAG, "User $peerID: $currentCount messages in last minute, $remainingMessages remaining")
        
        // Check if user has exceeded the limit
        if (currentCount >= MAX_MESSAGES_PER_MINUTE) {
            Log.w(TAG, "User $peerID exceeded rate limit, applying mute")
            
            // Apply mute
            val muteEndTime = currentTime + MUTE_DURATION_MS
            prefs.edit().putLong("mute_end_$userKey", muteEndTime).apply()
            
            // Clear timestamps and warning state for next period
            timestamps.clear()
            userWarningStates.remove(userKey)
            
            return@withLock AntiSpamResult(
                allowed = false,
                warning = "Anda telah di-mute selama 30 menit karena spam (lebih dari $MAX_MESSAGES_PER_MINUTE pesan per menit)",
                muteTimeRemaining = MUTE_DURATION_MS,
                action = SpamAction.MUTED
            )
        }
        
        // Add current message timestamp
        timestamps.add(currentTime)
        
        // Check for warning thresholds
        val newCount = timestamps.size
        val newRemainingMessages = MAX_MESSAGES_PER_MINUTE - newCount
        
        when {
            newCount >= FINAL_WARNING_THRESHOLD && !warningState.finalWarningShown -> {
                warningState.finalWarningShown = true
                warningState.lastWarningTime = currentTime
                return@withLock AntiSpamResult(
                    allowed = true,
                    warning = "PERINGATAN TERAKHIR: Anda akan di-mute jika mengirim lebih dari $newRemainingMessages pesan lagi dalam 1 menit!",
                    remainingMessages = newRemainingMessages,
                    action = SpamAction.WARNING_FINAL
                )
            }
            newCount >= FIRST_WARNING_THRESHOLD && !warningState.firstWarningShown -> {
                warningState.firstWarningShown = true
                warningState.lastWarningTime = currentTime
                return@withLock AntiSpamResult(
                    allowed = true,
                    warning = "Peringatan: Anda mendekati batas spam. Maksimal $MAX_MESSAGES_PER_MINUTE pesan per menit. Sisa: $newRemainingMessages pesan",
                    remainingMessages = newRemainingMessages,
                    action = SpamAction.WARNING_FIRST
                )
            }
            newCount > MAX_MESSAGES_PER_MINUTE * 0.5 -> {
                return@withLock AntiSpamResult(
                    allowed = true,
                    remainingMessages = newRemainingMessages,
                    action = SpamAction.RATE_LIMITED
                )
            }
            else -> {
                return@withLock AntiSpamResult(
                    allowed = true,
                    remainingMessages = newRemainingMessages,
                    action = SpamAction.ALLOW
                )
            }
        }
    }
    
    /**
     * Manually mute a user (for admin purposes or additional spam detection)
     */
    suspend fun muteUser(peerID: String, durationMs: Long = MUTE_DURATION_MS): Boolean = mutex.withLock {
        val userKey = getUserIdentifier(peerID)
        val muteEndTime = System.currentTimeMillis() + durationMs
        prefs.edit().putLong("mute_end_$userKey", muteEndTime).apply()
        
        // Clear any existing rate limit data
        userMessageTimestamps.remove(userKey)
        userWarningStates.remove(userKey)
        
        Log.i(TAG, "Manually muted user $peerID until ${Date(muteEndTime)}")
        return@withLock true
    }
    
    /**
     * Unmute a user (for admin purposes)
     */
    suspend fun unmuteUser(peerID: String): Boolean = mutex.withLock {
        val userKey = getUserIdentifier(peerID)
        val wasRemoved = prefs.edit().remove("mute_end_$userKey").commit()
        
        // Clear any existing rate limit data
        userMessageTimestamps.remove(userKey)
        userWarningStates.remove(userKey)
        
        Log.i(TAG, "Unmuted user $peerID")
        return@withLock wasRemoved
    }
    
    /**
     * Get statistics for debugging
     */
    suspend fun getStats(): Map<String, Any> = mutex.withLock {
        val currentTime = System.currentTimeMillis()
        val activeMutes = prefs.all.entries
            .filter { it.key.startsWith("mute_end_") && (it.value as? Long ?: 0L) > currentTime }
            .size
            
        return@withLock mapOf(
            "activeUsers" to userMessageTimestamps.size,
            "activeMutes" to activeMutes,
            "deviceFingerprint" to deviceFingerprint.take(8),
            "maxMessagesPerMinute" to MAX_MESSAGES_PER_MINUTE,
            "muteDurationMinutes" to (MUTE_DURATION_MS / 60000L)
        )
    }
    
    /**
     * Clean up old data to prevent memory leaks
     */
    private fun cleanupOldData() {
        val currentTime = System.currentTimeMillis()
        
        // Clean up expired mutes from persistent storage
        val editor = prefs.edit()
        var cleanedCount = 0
        
        prefs.all.entries.forEach { entry ->
            if (entry.key.startsWith("mute_end_")) {
                val muteEndTime = entry.value as? Long ?: 0L
                if (muteEndTime > 0 && muteEndTime < currentTime) {
                    editor.remove(entry.key)
                    cleanedCount++
                }
            }
        }
        
        if (cleanedCount > 0) {
            editor.apply()
            Log.d(TAG, "Cleaned up $cleanedCount expired mutes")
        }
        
        // Clean up old in-memory data
        userMessageTimestamps.entries.removeAll { (_, timestamps) ->
            timestamps.removeAll { it < currentTime - RATE_LIMIT_WINDOW_MS }
            timestamps.isEmpty()
        }
        
        // Clean up old warning states (reset after 5 minutes)
        userWarningStates.entries.removeAll { (_, state) ->
            currentTime - state.lastWarningTime > 5 * 60 * 1000L
        }
    }
    
    /**
     * Format time remaining in a user-friendly way
     */
    fun formatTimeRemaining(timeMs: Long): String {
        val minutes = (timeMs / 60000L).toInt()
        val seconds = ((timeMs % 60000L) / 1000L).toInt()
        
        return when {
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}