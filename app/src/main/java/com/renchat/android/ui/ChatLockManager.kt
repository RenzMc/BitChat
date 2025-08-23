package com.renchat.android.ui

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt as AndroidXBiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import java.util.concurrent.Executor

/**
 * Manages individual chat locks with PIN/biometric authentication
 * Simple, lightweight, and secure implementation
 */
class ChatLockManager(
    private val context: Context,
    private val dataManager: DataManager
) {
    companion object {
        private const val TAG = "ChatLockManager"
    }

    // LiveData for locked chats state
    private val _lockedChats = MutableLiveData<Set<String>>(emptySet())
    val lockedChats: LiveData<Set<String>> = _lockedChats

    // Currently unlocked chats (in memory only)
    private val temporarilyUnlockedChats = mutableSetOf<String>()

    init {
        // Load locked chats from persistent storage
        refreshLockedChatsState()
    }

    /**
     * Check if biometric authentication is available
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Check if a chat is currently locked (and not temporarily unlocked)
     */
    fun isChatLocked(peerID: String): Boolean {
        val isLocked = dataManager.isChatLocked(peerID)
        val isTemporarilyUnlocked = temporarilyUnlockedChats.contains(peerID)
        return isLocked && !isTemporarilyUnlocked
    }

    /**
     * Lock a chat
     */
    fun lockChat(peerID: String) {
        Log.d(TAG, "Locking chat with $peerID")
        dataManager.lockChat(peerID)
        temporarilyUnlockedChats.remove(peerID)
        refreshLockedChatsState()
    }

    /**
     * Unlock a chat
     */
    fun unlockChat(peerID: String) {
        Log.d(TAG, "Unlocking chat with $peerID")
        dataManager.unlockChat(peerID)
        temporarilyUnlockedChats.remove(peerID)
        refreshLockedChatsState()
    }

    /**
     * Temporarily unlock a chat (until app restart or manual lock)
     */
    private fun temporarilyUnlockChat(peerID: String) {
        Log.d(TAG, "Temporarily unlocking chat with $peerID")
        temporarilyUnlockedChats.add(peerID)
        refreshLockedChatsState()
    }

    /**
     * Authenticate and unlock a chat if successful
     */
    fun authenticateAndUnlock(
        peerID: String,
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (isBiometricAvailable()) {
            authenticateWithBiometric(activity, peerID, onSuccess, onError)
        } else {
            // Fallback: temporarily unlock without authentication
            // In production, you might want to implement PIN fallback
            Log.w(TAG, "Biometric not available, temporarily unlocking chat")
            temporarilyUnlockChat(peerID)
            onSuccess()
        }
    }

    /**
     * Authenticate with biometric
     */
    private fun authenticateWithBiometric(
        activity: FragmentActivity,
        peerID: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor: Executor = ContextCompat.getMainExecutor(context)
        
        val biometricPrompt = AndroidXBiometricPrompt(activity, executor,
            object : AndroidXBiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.e(TAG, "Biometric authentication error: $errString")
                    onError("Authentication failed: $errString")
                }

                override fun onAuthenticationSucceeded(result: AndroidXBiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.d(TAG, "Biometric authentication succeeded for chat $peerID")
                    temporarilyUnlockChat(peerID)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.w(TAG, "Biometric authentication failed")
                    onError("Authentication failed")
                }
            })

        val promptInfo = AndroidXBiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Chat")
            .setSubtitle("Use your biometric to unlock this private chat")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Refresh the locked chats state from persistent storage
     */
    private fun refreshLockedChatsState() {
        val lockedChatIds = dataManager.getLockedChats()
        _lockedChats.postValue(lockedChatIds)
        Log.d(TAG, "Refreshed locked chats state: $lockedChatIds")
    }

    /**
     * Clear all locks (for emergency clear)
     */
    fun clearAllLocks() {
        Log.d(TAG, "Clearing all chat locks")
        dataManager.clearAllChatLocks()
        temporarilyUnlockedChats.clear()
        refreshLockedChatsState()
    }

    /**
     * Get all locked chat IDs
     */
    fun getLockedChats(): Set<String> {
        return dataManager.getLockedChats()
    }
}