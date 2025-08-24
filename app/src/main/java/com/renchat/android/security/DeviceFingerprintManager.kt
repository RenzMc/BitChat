package com.renchat.android.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

/**
 * Advanced device fingerprinting system for anti-bypass detection
 * Uses multiple hardware characteristics to create persistent device identification
 * Survives app reinstalls, cache clearing, and some device resets
 */
class DeviceFingerprintManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceFingerprintManager"
        private const val PREFS_NAME = "device_fingerprint_secure"
        private const val KEY_DEVICE_FINGERPRINT = "device_fingerprint_v2"
        private const val KEY_HARDWARE_SIGNATURE = "hardware_signature_v2"
        private const val KEY_PERSISTENT_ID = "persistent_device_id"
        private const val FINGERPRINT_VERSION = 2
    }
    
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
    
    /**
     * Generate comprehensive device fingerprint
     * Uses multiple hardware and software characteristics
     */
    suspend fun generateDeviceFingerprint(): String = withContext(Dispatchers.Default) {
        try {
            val existingFingerprint = securePrefs.getString(KEY_DEVICE_FINGERPRINT, null)
            if (existingFingerprint != null) {
                Log.d(TAG, "Using existing device fingerprint")
                return@withContext existingFingerprint
            }
            
            val characteristics = mutableListOf<String>()
            
            // Hardware characteristics (most persistent)
            characteristics.add(getHardwareFingerprint())
            characteristics.add(getBuildFingerprint())
            characteristics.add(getDisplayFingerprint())
            characteristics.add(getCpuFingerprint())
            characteristics.add(getMemoryFingerprint())
            
            // System characteristics (moderately persistent)
            characteristics.add(getSystemFingerprint())
            characteristics.add(getTelephonyFingerprint())
            
            // Generate composite fingerprint
            val composite = characteristics.joinToString("|")
            val fingerprint = hashString("${FINGERPRINT_VERSION}:$composite")
            
            // Store securely
            securePrefs.edit()
                .putString(KEY_DEVICE_FINGERPRINT, fingerprint)
                .putString(KEY_HARDWARE_SIGNATURE, getHardwareSignature())
                .putLong("fingerprint_created", System.currentTimeMillis())
                .apply()
            
            Log.d(TAG, "Generated new device fingerprint")
            return@withContext fingerprint
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate device fingerprint", e)
            // Fallback to simpler fingerprint
            return@withContext generateFallbackFingerprint()
        }
    }
    
    /**
     * Get hardware-based fingerprint (most reliable)
     */
    @SuppressLint("HardwareIds")
    private fun getHardwareFingerprint(): String {
        val parts = mutableListOf<String>()
        
        try {
            // Build characteristics
            parts.add(Build.BOARD ?: "unknown")
            parts.add(Build.BOOTLOADER ?: "unknown")
            parts.add(Build.BRAND ?: "unknown") 
            parts.add(Build.DEVICE ?: "unknown")
            parts.add(Build.HARDWARE ?: "unknown")
            parts.add(Build.MANUFACTURER ?: "unknown")
            parts.add(Build.MODEL ?: "unknown")
            parts.add(Build.PRODUCT ?: "unknown")
            parts.add(Build.SERIAL.takeIf { it != "unknown" && it != Build.UNKNOWN } ?: "")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                parts.add(Build.getSerial().takeIf { it != "unknown" } ?: "")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error getting hardware fingerprint: ${e.message}")
        }
        
        return parts.joinToString(":").take(200) // Limit length
    }
    
    /**
     * Get build-specific fingerprint
     */
    private fun getBuildFingerprint(): String {
        return try {
            listOf(
                Build.FINGERPRINT ?: "unknown",
                Build.ID ?: "unknown", 
                Build.TAGS ?: "unknown",
                Build.TYPE ?: "unknown",
                Build.USER ?: "unknown"
            ).joinToString(":")
        } catch (e: Exception) {
            "build_error"
        }
    }
    
    /**
     * Get display characteristics fingerprint
     */
    private fun getDisplayFingerprint(): String {
        return try {
            val displayMetrics = context.resources.displayMetrics
            listOf(
                displayMetrics.densityDpi.toString(),
                displayMetrics.widthPixels.toString(),
                displayMetrics.heightPixels.toString(),
                displayMetrics.density.toString()
            ).joinToString(":")
        } catch (e: Exception) {
            "display_error"
        }
    }
    
    /**
     * Get CPU characteristics
     */
    private fun getCpuFingerprint(): String {
        return try {
            val cpuInfo = mutableListOf<String>()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cpuInfo.addAll(Build.SUPPORTED_ABIS.toList())
            } else {
                @Suppress("DEPRECATION")
                cpuInfo.add(Build.CPU_ABI)
                @Suppress("DEPRECATION") 
                cpuInfo.add(Build.CPU_ABI2 ?: "")
            }
            
            // Add processor count
            cpuInfo.add(Runtime.getRuntime().availableProcessors().toString())
            
            cpuInfo.joinToString(":")
        } catch (e: Exception) {
            "cpu_error"
        }
    }
    
    /**
     * Get memory characteristics
     */
    private fun getMemoryFingerprint(): String {
        return try {
            val runtime = Runtime.getRuntime()
            listOf(
                runtime.maxMemory().toString(),
                runtime.totalMemory().toString()
            ).joinToString(":")
        } catch (e: Exception) {
            "memory_error"
        }
    }
    
    /**
     * Get system-level fingerprint
     */
    private fun getSystemFingerprint(): String {
        return try {
            listOf(
                Build.VERSION.RELEASE ?: "unknown",
                Build.VERSION.SDK_INT.toString(),
                Locale.getDefault().toString(),
                TimeZone.getDefault().id
            ).joinToString(":")
        } catch (e: Exception) {
            "system_error"
        }
    }
    
    /**
     * Get telephony fingerprint (if available)
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    private fun getTelephonyFingerprint(): String {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephonyManager?.let { tm ->
                listOf(
                    tm.networkOperatorName ?: "unknown",
                    tm.networkCountryIso ?: "unknown",
                    tm.simCountryIso ?: "unknown"
                ).joinToString(":")
            } ?: "no_telephony"
        } catch (e: Exception) {
            "telephony_error"
        }
    }
    
    /**
     * Get unique hardware signature for cross-reference
     */
    private fun getHardwareSignature(): String {
        return try {
            val signature = listOf(
                Build.BRAND,
                Build.MODEL, 
                Build.MANUFACTURER,
                Build.DEVICE,
                Build.PRODUCT
            ).joinToString("-")
            
            hashString(signature).take(16)
        } catch (e: Exception) {
            "hw_sig_error"
        }
    }
    
    /**
     * Generate fallback fingerprint for error cases
     */
    private fun generateFallbackFingerprint(): String {
        val fallbackData = listOf(
            Build.MODEL ?: "unknown",
            Build.MANUFACTURER ?: "unknown",
            Build.VERSION.SDK_INT.toString(),
            System.currentTimeMillis().toString()
        ).joinToString(":")
        
        return hashString("fallback:$fallbackData")
    }
    
    /**
     * Check if device fingerprint matches known device
     */
    suspend fun validateDeviceFingerprint(knownFingerprint: String): Boolean = withContext(Dispatchers.Default) {
        try {
            val currentFingerprint = generateDeviceFingerprint()
            
            // Exact match
            if (currentFingerprint == knownFingerprint) {
                return@withContext true
            }
            
            // Check hardware signature for partial match (device might have been reset)
            val currentHwSig = getHardwareSignature()
            val storedHwSig = securePrefs.getString(KEY_HARDWARE_SIGNATURE, null)
            
            if (storedHwSig != null && currentHwSig == storedHwSig) {
                Log.i(TAG, "Device fingerprint changed but hardware signature matches")
                // Update fingerprint but maintain recognition
                securePrefs.edit()
                    .putString(KEY_DEVICE_FINGERPRINT, currentFingerprint)
                    .putLong("fingerprint_updated", System.currentTimeMillis())
                    .apply()
                return@withContext true
            }
            
            Log.w(TAG, "Device fingerprint validation failed")
            return@withContext false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error validating device fingerprint", e)
            return@withContext false
        }
    }
    
    /**
     * Generate persistent device ID that survives app reinstalls
     * Uses Android ID and fallbacks
     */
    @SuppressLint("HardwareIds")
    suspend fun getPersistentDeviceId(): String = withContext(Dispatchers.Default) {
        try {
            val existingId = securePrefs.getString(KEY_PERSISTENT_ID, null)
            if (existingId != null) {
                return@withContext existingId
            }
            
            // Try Android ID first (persists across app reinstalls)
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            
            val persistentId = if (androidId != null && androidId != "9774d56d682e549c") { // Not the broken Android ID
                hashString("aid:$androidId:${Build.MANUFACTURER}:${Build.MODEL}")
            } else {
                // Fallback to hardware-based ID
                val hwId = "${Build.MANUFACTURER}:${Build.MODEL}:${Build.SERIAL.takeIf { it != "unknown" } ?: UUID.randomUUID()}"
                hashString("hw:$hwId")
            }
            
            // Store for future use
            securePrefs.edit()
                .putString(KEY_PERSISTENT_ID, persistentId)
                .putLong("persistent_id_created", System.currentTimeMillis())
                .apply()
            
            Log.d(TAG, "Generated persistent device ID")
            return@withContext persistentId
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating persistent device ID", e)
            return@withContext hashString("error:${System.currentTimeMillis()}")
        }
    }
    
    /**
     * Get device risk score based on fingerprint analysis
     */
    suspend fun getDeviceRiskScore(): Double = withContext(Dispatchers.Default) {
        try {
            var riskScore = 0.0
            
            // Check for common emulator characteristics
            if (isLikelyEmulator()) {
                riskScore += 0.4
            }
            
            // Check for root/debugging signs
            if (hasRootAccess()) {
                riskScore += 0.3
            }
            
            // Check for development environment
            if (isDevelopmentEnvironment()) {
                riskScore += 0.2
            }
            
            // Check for fingerprint instability
            val fingerprintAge = getFingerprintAge()
            if (fingerprintAge < 60000) { // Less than 1 minute old
                riskScore += 0.1
            }
            
            return@withContext riskScore.coerceIn(0.0, 1.0)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating device risk score", e)
            return@withContext 0.5 // Default moderate risk
        }
    }
    
    /**
     * Check if device appears to be an emulator
     */
    private fun isLikelyEmulator(): Boolean {
        return try {
            val emulatorSigns = listOf(
                Build.MANUFACTURER.contains("Genymotion", true),
                Build.MODEL.contains("google_sdk", true),
                Build.MODEL.contains("Emulator", true),
                Build.MODEL.contains("Android SDK", true),
                Build.DEVICE.contains("generic", true),
                Build.BRAND.contains("generic", true),
                Build.HARDWARE.contains("goldfish", true),
                Build.HARDWARE.contains("ranchu", true),
                Build.PRODUCT.contains("sdk", true),
                Build.PRODUCT.contains("emulator", true),
                Build.FINGERPRINT.contains("generic", true)
            )
            
            emulatorSigns.count { it } >= 2
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check for root access indicators
     */
    private fun hasRootAccess(): Boolean {
        return try {
            val rootPaths = listOf(
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su"
            )
            
            rootPaths.any { java.io.File(it).exists() }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if running in development environment
     */
    private fun isDevelopmentEnvironment(): Boolean {
        return try {
            Build.TYPE == "eng" || Build.TYPE == "userdebug" || Build.TAGS.contains("test-keys")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get age of current fingerprint
     */
    private fun getFingerprintAge(): Long {
        val created = securePrefs.getLong("fingerprint_created", System.currentTimeMillis())
        return System.currentTimeMillis() - created
    }
    
    /**
     * Hash string using SHA-256
     */
    private fun hashString(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error hashing string", e)
            input.hashCode().toString()
        }
    }
    
    /**
     * Clear all stored fingerprint data (for testing/debugging)
     */
    fun clearFingerprintData() {
        securePrefs.edit().clear().apply()
        Log.d(TAG, "Cleared all fingerprint data")
    }
    
    /**
     * Get fingerprint statistics for debugging
     */
    fun getFingerprintStats(): Map<String, Any> {
        return try {
            mapOf(
                "has_fingerprint" to securePrefs.contains(KEY_DEVICE_FINGERPRINT),
                "has_hw_signature" to securePrefs.contains(KEY_HARDWARE_SIGNATURE),
                "has_persistent_id" to securePrefs.contains(KEY_PERSISTENT_ID),
                "fingerprint_age" to getFingerprintAge(),
                "is_emulator" to isLikelyEmulator(),
                "has_root" to hasRootAccess(),
                "is_dev_env" to isDevelopmentEnvironment()
            )
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }
}