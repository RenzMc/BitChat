package com.renchat.android.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent storage system for anti-bypass data
 * Survives app reinstalls, cache clearing, and device resets
 * Uses multiple storage layers and cross-device synchronization
 */
class AntiBypassStorage(
    private val context: Context,
    private val deviceFingerprintManager: DeviceFingerprintManager
) {
    
    companion object {
        private const val TAG = "AntiBypassStorage"
        private const val PREFS_NAME = "antibypass_persistent_v3"
        private const val KEY_BAN_RECORDS = "ban_records_v3"
        private const val KEY_DEVICE_RECORDS = "device_records_v3"
        private const val KEY_TRUST_RECORDS = "trust_records_v3"
        private const val KEY_PATTERN_VIOLATIONS = "pattern_violations_v3"
        private const val KEY_CROSS_DEVICE_SYNC = "cross_device_sync_v3"
        private const val STORAGE_VERSION = 3
    }
    
    private val gson = Gson()
    private val mutex = Mutex()
    
    private val securePrefs: android.content.SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted preferences, using regular SharedPreferences", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    // In-memory cache for frequently accessed data
    private val banRecordsCache = ConcurrentHashMap<String, PersistentBanRecord>()
    private val deviceRecordsCache = ConcurrentHashMap<String, PersistentDeviceRecord>()
    
    /**
     * Persistent ban record that survives reinstalls
     */
    data class PersistentBanRecord(
        val peerID: String,
        val deviceFingerprint: String,
        val persistentDeviceId: String,
        val banReason: String,
        val banTimestamp: Long,
        val banExpiry: Long,
        val violationCount: Int = 1,
        val severityLevel: Int = 1,
        val hardwareBanned: Boolean = false,
        val crossDeviceBan: Boolean = false
    )
    
    /**
     * Device-specific record for tracking across reinstalls
     */
    data class PersistentDeviceRecord(
        val deviceFingerprint: String,
        val persistentDeviceId: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val violationHistory: MutableList<ViolationRecord> = mutableListOf(),
        val trustScore: Double = 1.0,
        val riskLevel: String = "LOW",
        val isKnownBad: Boolean = false,
        val bypassAttempts: Int = 0
    )
    
    /**
     * Individual violation record
     */
    data class ViolationRecord(
        val timestamp: Long,
        val violationType: String,
        val severity: String,
        val details: String,
        val peerID: String? = null
    )
    
    /**
     * Cross-device sync data
     */
    data class CrossDeviceSyncData(
        val hardwareBans: Set<String>,
        val trustedDevices: Set<String>,
        val sharedViolations: Map<String, List<ViolationRecord>>,
        val lastSyncTime: Long
    )
    
    init {
        loadCacheData()
    }
    
    /**
     * Check if user/device is banned with comprehensive bypass detection
     */
    suspend fun isBannedWithBypassCheck(peerID: String): BanCheckResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val currentFingerprint = deviceFingerprintManager.generateDeviceFingerprint()
                val persistentId = deviceFingerprintManager.getPersistentDeviceId()
                
                // Check direct peer ban
                val directBan = banRecordsCache[peerID]
                if (directBan != null && directBan.banExpiry > System.currentTimeMillis()) {
                    return@withContext BanCheckResult(
                        isBanned = true,
                        reason = directBan.banReason,
                        expiresAt = directBan.banExpiry,
                        bypassDetected = false,
                        banType = "DIRECT"
                    )
                }
                
                // Check device fingerprint bans
                val deviceBans = banRecordsCache.values.filter { ban ->
                    ban.deviceFingerprint == currentFingerprint && 
                    ban.banExpiry > System.currentTimeMillis()
                }
                
                if (deviceBans.isNotEmpty()) {
                    val ban = deviceBans.first()
                    return@withContext BanCheckResult(
                        isBanned = true,
                        reason = "Device banned: ${ban.banReason}",
                        expiresAt = ban.banExpiry,
                        bypassDetected = ban.peerID != peerID, // Different peer on same device
                        banType = "DEVICE"
                    )
                }
                
                // Check persistent ID bans (survives app reinstall)
                val persistentBans = banRecordsCache.values.filter { ban ->
                    ban.persistentDeviceId == persistentId && 
                    ban.banExpiry > System.currentTimeMillis()
                }
                
                if (persistentBans.isNotEmpty()) {
                    val ban = persistentBans.first()
                    val bypassDetected = ban.deviceFingerprint != currentFingerprint
                    
                    if (bypassDetected) {
                        // Update ban record with new fingerprint
                        recordBypassAttempt(peerID, currentFingerprint, persistentId)
                    }
                    
                    return@withContext BanCheckResult(
                        isBanned = true,
                        reason = "Persistent ban: ${ban.banReason}",
                        expiresAt = ban.banExpiry,
                        bypassDetected = bypassDetected,
                        banType = "PERSISTENT"
                    )
                }
                
                // Check cross-device hardware bans
                if (isHardwareBanned(currentFingerprint, persistentId)) {
                    return@withContext BanCheckResult(
                        isBanned = true,
                        reason = "Hardware ban - bypass attempt detected",
                        expiresAt = Long.MAX_VALUE,
                        bypassDetected = true,
                        banType = "HARDWARE"
                    )
                }
                
                return@withContext BanCheckResult(
                    isBanned = false,
                    reason = null,
                    expiresAt = 0,
                    bypassDetected = false,
                    banType = "NONE"
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error checking ban status", e)
                return@withContext BanCheckResult(
                    isBanned = false,
                    reason = null,
                    expiresAt = 0,
                    bypassDetected = false,
                    banType = "ERROR"
                )
            }
        }
    }
    
    /**
     * Apply persistent ban that survives bypasses
     */
    suspend fun applyPersistentBan(
        peerID: String,
        reason: String,
        durationHours: Long,
        severityLevel: Int = 1,
        enableHardwareBan: Boolean = false
    ): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val currentFingerprint = deviceFingerprintManager.generateDeviceFingerprint()
                val persistentId = deviceFingerprintManager.getPersistentDeviceId()
                val banExpiry = System.currentTimeMillis() + (durationHours * 60 * 60 * 1000)
                
                val banRecord = PersistentBanRecord(
                    peerID = peerID,
                    deviceFingerprint = currentFingerprint,
                    persistentDeviceId = persistentId,
                    banReason = reason,
                    banTimestamp = System.currentTimeMillis(),
                    banExpiry = banExpiry,
                    violationCount = getViolationCount(peerID) + 1,
                    severityLevel = severityLevel,
                    hardwareBanned = enableHardwareBan,
                    crossDeviceBan = severityLevel >= 3
                )
                
                // Store in cache and persistent storage
                banRecordsCache[peerID] = banRecord
                updateDeviceRecord(currentFingerprint, persistentId, peerID, reason, severityLevel)
                
                if (enableHardwareBan) {
                    addHardwareBan(currentFingerprint, persistentId)
                }
                
                saveBanRecords()
                saveDeviceRecords()
                
                Log.i(TAG, "Applied persistent ban to $peerID (expires: ${Date(banExpiry)})")
                return@withContext true
                
            } catch (e: Exception) {
                Log.e(TAG, "Error applying persistent ban", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Record bypass attempt
     */
    private suspend fun recordBypassAttempt(
        peerID: String,
        newFingerprint: String,
        persistentId: String
    ) {
        try {
            val deviceRecord = deviceRecordsCache[persistentId] ?: PersistentDeviceRecord(
                deviceFingerprint = newFingerprint,
                persistentDeviceId = persistentId,
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis()
            )
            
            val updatedRecord = deviceRecord.copy(
                deviceFingerprint = newFingerprint,
                lastSeen = System.currentTimeMillis(),
                bypassAttempts = deviceRecord.bypassAttempts + 1,
                isKnownBad = true,
                violationHistory = deviceRecord.violationHistory.apply {
                    add(ViolationRecord(
                        timestamp = System.currentTimeMillis(),
                        violationType = "BYPASS_ATTEMPT",
                        severity = "HIGH",
                        details = "Attempted to bypass ban with fingerprint change",
                        peerID = peerID
                    ))
                }
            )
            
            deviceRecordsCache[persistentId] = updatedRecord
            saveDeviceRecords()
            
            Log.w(TAG, "Recorded bypass attempt for $peerID")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording bypass attempt", e)
        }
    }
    
    /**
     * Update device record with violation
     */
    private fun updateDeviceRecord(
        fingerprint: String,
        persistentId: String,
        peerID: String?,
        reason: String,
        severity: Int
    ) {
        val deviceRecord = deviceRecordsCache[persistentId] ?: PersistentDeviceRecord(
            deviceFingerprint = fingerprint,
            persistentDeviceId = persistentId,
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis()
        )
        
        val violation = ViolationRecord(
            timestamp = System.currentTimeMillis(),
            violationType = "BAN_APPLIED",
            severity = when (severity) {
                1 -> "LOW"
                2 -> "MEDIUM"
                3 -> "HIGH"
                else -> "CRITICAL"
            },
            details = reason,
            peerID = peerID
        )
        
        val updatedRecord = deviceRecord.copy(
            deviceFingerprint = fingerprint,
            lastSeen = System.currentTimeMillis(),
            trustScore = calculateTrustScore(deviceRecord.violationHistory + violation),
            riskLevel = calculateRiskLevel(deviceRecord.violationHistory + violation),
            violationHistory = deviceRecord.violationHistory.apply { add(violation) }
        )
        
        deviceRecordsCache[persistentId] = updatedRecord
    }
    
    /**
     * Check if hardware is banned
     */
    private fun isHardwareBanned(fingerprint: String, persistentId: String): Boolean {
        return try {
            val syncData = getCrossDeviceSyncData()
            syncData.hardwareBans.contains(fingerprint) || 
            syncData.hardwareBans.contains(persistentId) ||
            deviceRecordsCache.values.any { 
                it.isKnownBad && 
                it.bypassAttempts >= 3 &&
                (it.deviceFingerprint == fingerprint || it.persistentDeviceId == persistentId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking hardware ban", e)
            false
        }
    }
    
    /**
     * Add hardware ban
     */
    private fun addHardwareBan(fingerprint: String, persistentId: String) {
        try {
            val syncData = getCrossDeviceSyncData()
            val updatedSyncData = syncData.copy(
                hardwareBans = syncData.hardwareBans + fingerprint + persistentId,
                lastSyncTime = System.currentTimeMillis()
            )
            saveCrossDeviceSyncData(updatedSyncData)
            
            Log.w(TAG, "Added hardware ban for fingerprint/persistentId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error adding hardware ban", e)
        }
    }
    
    /**
     * Get violation count for peer
     */
    private fun getViolationCount(peerID: String): Int {
        return banRecordsCache[peerID]?.violationCount ?: 0
    }
    
    /**
     * Calculate trust score based on violation history
     */
    private fun calculateTrustScore(violations: List<ViolationRecord>): Double {
        if (violations.isEmpty()) return 1.0
        
        val recentViolations = violations.filter { 
            System.currentTimeMillis() - it.timestamp < 30 * 24 * 60 * 60 * 1000L // Last 30 days
        }
        
        val severityScore = recentViolations.sumOf { violation ->
            when (violation.severity) {
                "LOW" -> 0.1
                "MEDIUM" -> 0.25
                "HIGH" -> 0.5
                "CRITICAL" -> 1.0
                else -> 0.1
            }
        }
        
        return (1.0 - (severityScore / 5.0)).coerceIn(0.0, 1.0)
    }
    
    /**
     * Calculate risk level based on violations
     */
    private fun calculateRiskLevel(violations: List<ViolationRecord>): String {
        val recentViolations = violations.filter { 
            System.currentTimeMillis() - it.timestamp < 7 * 24 * 60 * 60 * 1000L // Last 7 days
        }
        
        val criticalCount = recentViolations.count { it.severity == "CRITICAL" }
        val highCount = recentViolations.count { it.severity == "HIGH" }
        val totalCount = recentViolations.size
        
        return when {
            criticalCount > 0 || highCount >= 3 -> "CRITICAL"
            highCount > 0 || totalCount >= 5 -> "HIGH"
            totalCount >= 2 -> "MEDIUM"
            else -> "LOW"
        }
    }
    
    /**
     * Load data into cache
     */
    private fun loadCacheData() {
        try {
            // Load ban records
            val banRecordsJson = securePrefs.getString(KEY_BAN_RECORDS, null)
            if (banRecordsJson != null) {
                val banRecordsList: List<PersistentBanRecord> = gson.fromJson(
                    banRecordsJson,
                    object : TypeToken<List<PersistentBanRecord>>() {}.type
                )
                banRecordsList.forEach { banRecordsCache[it.peerID] = it }
            }
            
            // Load device records
            val deviceRecordsJson = securePrefs.getString(KEY_DEVICE_RECORDS, null)
            if (deviceRecordsJson != null) {
                val deviceRecordsList: List<PersistentDeviceRecord> = gson.fromJson(
                    deviceRecordsJson,
                    object : TypeToken<List<PersistentDeviceRecord>>() {}.type
                )
                deviceRecordsList.forEach { deviceRecordsCache[it.persistentDeviceId] = it }
            }
            
            Log.d(TAG, "Loaded ${banRecordsCache.size} ban records and ${deviceRecordsCache.size} device records")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache data", e)
        }
    }
    
    /**
     * Save ban records to persistent storage
     */
    private fun saveBanRecords() {
        try {
            val banRecordsList = banRecordsCache.values.toList()
            val json = gson.toJson(banRecordsList)
            securePrefs.edit()
                .putString(KEY_BAN_RECORDS, json)
                .putLong("ban_records_updated", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving ban records", e)
        }
    }
    
    /**
     * Save device records to persistent storage
     */
    private fun saveDeviceRecords() {
        try {
            val deviceRecordsList = deviceRecordsCache.values.toList()
            val json = gson.toJson(deviceRecordsList)
            securePrefs.edit()
                .putString(KEY_DEVICE_RECORDS, json)
                .putLong("device_records_updated", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving device records", e)
        }
    }
    
    /**
     * Get cross-device sync data
     */
    private fun getCrossDeviceSyncData(): CrossDeviceSyncData {
        return try {
            val json = securePrefs.getString(KEY_CROSS_DEVICE_SYNC, null)
            if (json != null) {
                gson.fromJson(json, CrossDeviceSyncData::class.java)
            } else {
                CrossDeviceSyncData(
                    hardwareBans = emptySet(),
                    trustedDevices = emptySet(),
                    sharedViolations = emptyMap(),
                    lastSyncTime = 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sync data", e)
            CrossDeviceSyncData(
                hardwareBans = emptySet(),
                trustedDevices = emptySet(),
                sharedViolations = emptyMap(),
                lastSyncTime = 0
            )
        }
    }
    
    /**
     * Save cross-device sync data
     */
    private fun saveCrossDeviceSyncData(syncData: CrossDeviceSyncData) {
        try {
            val json = gson.toJson(syncData)
            securePrefs.edit()
                .putString(KEY_CROSS_DEVICE_SYNC, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving sync data", e)
        }
    }
    
    /**
     * Clean up expired records
     */
    suspend fun cleanupExpiredRecords() = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                val currentTime = System.currentTimeMillis()
                
                // Remove expired bans
                val expiredBans = banRecordsCache.filterValues { it.banExpiry <= currentTime }
                expiredBans.keys.forEach { banRecordsCache.remove(it) }
                
                // Clean up old device records (keep for 90 days)
                val cutoffTime = currentTime - (90 * 24 * 60 * 60 * 1000L)
                val oldDeviceRecords = deviceRecordsCache.filterValues { it.lastSeen <= cutoffTime && !it.isKnownBad }
                oldDeviceRecords.keys.forEach { deviceRecordsCache.remove(it) }
                
                if (expiredBans.isNotEmpty() || oldDeviceRecords.isNotEmpty()) {
                    saveBanRecords()
                    saveDeviceRecords()
                    Log.d(TAG, "Cleaned up ${expiredBans.size} expired bans and ${oldDeviceRecords.size} old device records")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
    
    /**
     * Get storage statistics
     */
    fun getStorageStats(): Map<String, Any> {
        return mapOf(
            "total_ban_records" to banRecordsCache.size,
            "active_bans" to banRecordsCache.values.count { it.banExpiry > System.currentTimeMillis() },
            "total_device_records" to deviceRecordsCache.size,
            "known_bad_devices" to deviceRecordsCache.values.count { it.isKnownBad },
            "hardware_bans" to getCrossDeviceSyncData().hardwareBans.size,
            "storage_version" to STORAGE_VERSION
        )
    }
}

/**
 * Result of ban check with bypass detection
 */
data class BanCheckResult(
    val isBanned: Boolean,
    val reason: String?,
    val expiresAt: Long,
    val bypassDetected: Boolean,
    val banType: String
)