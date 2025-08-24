package com.renchat.android.ui

import android.content.Context
import android.util.Log
import com.renchat.android.security.AntiBypassStorage
import com.renchat.android.security.DeviceFingerprintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Data classes for moderation
 */
data class ModerationAction(
    val id: String,
    val targetPeerID: String,
    val actionType: ActionType,
    val reason: String,
    val timestamp: Date,
    val moderatorPeerID: String? = null,
    val isAutomated: Boolean = false,
    val severity: ModerationSeverity = ModerationSeverity.MEDIUM
)

enum class ActionType {
    WARNING_ISSUED,
    TEMPORARY_BAN,
    PERMANENT_BAN,
    MESSAGE_DELETED,
    REPORT_VALIDATED,
    REPORT_DISMISSED,
    ACCOUNT_FLAGGED
}

enum class ModerationSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class ModerationStats(
    val totalWarnings: Int,
    val totalBans: Int,
    val totalReports: Int,
    val validatedReports: Int,
    val dismissedReports: Int,
    val automatedActions: Int,
    val manualActions: Int
)

data class UserModerationProfile(
    val peerID: String,
    val warningCount: Int = 0,
    val banCount: Int = 0,
    val reportsMade: Int = 0,
    val reportsAgainst: Int = 0,
    val trustScore: Double = 1.0,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val lastAction: Date? = null
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Enhanced central moderation manager with anti-bypass detection
 * Coordinates spam filtering, community reporting, device fingerprinting, and persistent storage
 * Provides sophisticated threat detection while remaining user-friendly
 */
class ModerationManager(
    private val context: Context,
    private val spamFilterManager: SpamFilterManager,
    private val communityReportManager: CommunityReportManager,
    private val dataManager: DataManager
) {
    
    private val deviceFingerprintManager = DeviceFingerprintManager(context)
    private val antiBypassStorage = AntiBypassStorage(context, deviceFingerprintManager)
    
    // Enhanced threat tracking
    private val userSessions = ConcurrentHashMap<String, UserSession>()
    private val deviceRiskCache = ConcurrentHashMap<String, DeviceRisk>()
    private val recentActions = ConcurrentHashMap<String, MutableList<TimestampedAction>>()
    
    data class UserSession(
        val peerID: String,
        val sessionStart: Long,
        val messageCount: Int = 0,
        val warningCount: Int = 0,
        val deviceFingerprint: String? = null,
        val trustScore: Double = 0.5,
        val riskLevel: String = "LOW"
    )
    
    data class DeviceRisk(
        val fingerprint: String,
        val riskScore: Double,
        val lastAssessment: Long,
        val knownViolations: Int = 0
    )
    
    data class TimestampedAction(
        val action: ModerationAction,
        val timestamp: Long
    )
    companion object {
        private const val TAG = "ModerationManager"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _moderationActions = MutableStateFlow<List<ModerationAction>>(emptyList())
    val moderationActions: StateFlow<List<ModerationAction>> = _moderationActions.asStateFlow()
    
    private val _userProfiles = MutableStateFlow<Map<String, UserModerationProfile>>(emptyMap())
    val userProfiles: StateFlow<Map<String, UserModerationProfile>> = _userProfiles.asStateFlow()
    
    private val _moderationStats = MutableStateFlow(ModerationStats(0, 0, 0, 0, 0, 0, 0))
    val moderationStats: StateFlow<ModerationStats> = _moderationStats.asStateFlow()
    
    private val actions = mutableListOf<ModerationAction>()
    private val userProfiles = mutableMapOf<String, UserModerationProfile>()
    
    init {
        scope.launch {
            observeSystemChanges()
        }
        loadModerationData()
    }
    
    /**
     * Enhanced message processing with comprehensive threat analysis
     * Incorporates device fingerprinting, bypass detection, and behavioral analysis
     */
    suspend fun processMessage(peerID: String, message: String): Boolean {
        try {
            // Update user session
            updateUserSession(peerID, message)
            
            // Advanced ban check with bypass detection
            val banCheck = antiBypassStorage.isBannedWithBypassCheck(peerID)
            if (banCheck.isBanned) {
                if (banCheck.bypassDetected) {
                    Log.w(TAG, "Ban bypass attempt detected for $peerID - applying enhanced ban")
                    handleBypassAttempt(peerID, banCheck)
                }
                Log.i(TAG, "Blocked message from banned user: $peerID (${banCheck.banType})")
                return true
            }
            
            // Device risk assessment
            val deviceRisk = assessDeviceRisk(peerID)
            if (deviceRisk.riskScore > 0.8) {
                Log.w(TAG, "High-risk device detected for $peerID: ${deviceRisk.riskScore}")
                // Apply stricter spam filtering for high-risk devices
            }
            
            // Enhanced spam checking
            val spamWarning = spamFilterManager.checkForSpam(peerID, message)
            if (spamWarning != null) {
                handleEnhancedSpamDetection(spamWarning, deviceRisk)
                
                // Block critical spam immediately
                if (spamWarning.severity == SpamSeverity.CRITICAL) {
                    return true
                }
                
                // For high-risk devices, block medium+ severity spam
                if (deviceRisk.riskScore > 0.7 && spamWarning.severity in listOf(SpamSeverity.MEDIUM, SpamSeverity.HIGH)) {
                    return true
                }
            } else {
                // Reward good behavior
                spamFilterManager.increaseTrustScore(peerID, 0.001)
            }
            
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message from $peerID", e)
            return false // Don't block on error
        }
    }
    
    /**
     * Handle bypass attempt with enhanced countermeasures
     */
    private suspend fun handleBypassAttempt(peerID: String, banCheck: AntiBypassStorage.BanCheckResult) {
        try {
            // Apply escalated ban
            antiBypassStorage.applyPersistentBan(
                peerID,
                "Bypass attempt detected: ${banCheck.reason}",
                72, // 72-hour ban
                4, // Critical severity
                true // Enable hardware ban
            )
            
            // Log the action
            val action = ModerationAction(
                id = generateActionId(),
                targetPeerID = peerID,
                actionType = ActionType.PERMANENT_BAN,
                reason = "Ban bypass attempt: ${banCheck.banType}",
                timestamp = Date(),
                isAutomated = true,
                severity = ModerationSeverity.CRITICAL
            )
            
            addModerationAction(action)
            
            Log.w(TAG, "Enhanced ban applied for bypass attempt: $peerID")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling bypass attempt", e)
        }
    }
    
    /**
     * Update user session data
     */
    private fun updateUserSession(peerID: String, message: String) {
        val currentSession = userSessions[peerID] ?: UserSession(
            peerID = peerID,
            sessionStart = System.currentTimeMillis()
        )
        
        val updatedSession = currentSession.copy(
            messageCount = currentSession.messageCount + 1
        )
        
        userSessions[peerID] = updatedSession
        
        // Clean up old sessions (older than 6 hours)
        val cutoff = System.currentTimeMillis() - (6 * 60 * 60 * 1000)
        userSessions.entries.removeAll { it.value.sessionStart < cutoff }
    }
    
    /**
     * Assess device risk level
     */
    private suspend fun assessDeviceRisk(peerID: String): DeviceRisk {
        val fingerprint = deviceFingerprintManager.generateDeviceFingerprint()
        
        val cached = deviceRiskCache[fingerprint]
        if (cached != null && System.currentTimeMillis() - cached.lastAssessment < 300000) { // 5 minutes cache
            return cached
        }
        
        val deviceRiskScore = deviceFingerprintManager.getDeviceRiskScore()
        val knownViolations = getUserProfile(peerID).warningCount
        
        // Combine device risk with user violation history
        val combinedRisk = (deviceRiskScore * 0.7) + (knownViolations * 0.05)
        
        val deviceRisk = DeviceRisk(
            fingerprint = fingerprint,
            riskScore = combinedRisk.coerceIn(0.0, 1.0),
            lastAssessment = System.currentTimeMillis(),
            knownViolations = knownViolations
        )
        
        deviceRiskCache[fingerprint] = deviceRisk
        return deviceRisk
    }
    
    /**
     * Handle a community report submission
     */
    fun handleCommunityReport(
        reporterPeerID: String,
        targetPeerID: String,
        reason: ReportReason,
        description: String,
        messageContent: String? = null
    ): String? {
        
        val reportId = communityReportManager.submitReport(
            reporterPeerID, targetPeerID, reason, description, messageContent
        )
        
        if (reportId != null) {
            // Log the action
            val action = ModerationAction(
                id = generateActionId(),
                targetPeerID = targetPeerID,
                actionType = ActionType.REPORT_VALIDATED,
                reason = "Community report: ${reason.name}",
                timestamp = Date(),
                moderatorPeerID = reporterPeerID,
                isAutomated = false,
                severity = mapReportReasonToSeverity(reason)
            )
            
            addModerationAction(action)
            updateUserProfile(targetPeerID) { profile ->
                profile.copy(reportsAgainst = profile.reportsAgainst + 1)
            }
            updateUserProfile(reporterPeerID) { profile ->
                profile.copy(reportsMade = profile.reportsMade + 1)
            }
        }
        
        return reportId
    }
    
    /**
     * Enhanced spam detection handling with device context
     */
    private suspend fun handleEnhancedSpamDetection(spamWarning: SpamWarning, deviceRisk: DeviceRisk) {
        val actionType = when {
            spamWarning.severity == SpamSeverity.CRITICAL -> ActionType.TEMPORARY_BAN
            spamWarning.severity == SpamSeverity.HIGH && deviceRisk.riskScore > 0.6 -> ActionType.TEMPORARY_BAN
            spamWarning.severity == SpamSeverity.HIGH -> ActionType.WARNING_ISSUED
            else -> ActionType.WARNING_ISSUED
        }
        
        val severity = when (spamWarning.severity) {
            SpamSeverity.LOW -> ModerationSeverity.LOW
            SpamSeverity.MEDIUM -> ModerationSeverity.MEDIUM
            SpamSeverity.HIGH -> ModerationSeverity.HIGH
            SpamSeverity.CRITICAL -> ModerationSeverity.CRITICAL
        }
        
        val action = ModerationAction(
            id = generateActionId(),
            targetPeerID = spamWarning.peerID,
            actionType = actionType,
            reason = "Enhanced spam detection: ${spamWarning.reason} (Device risk: ${"%.2f".format(deviceRisk.riskScore)})",
            timestamp = spamWarning.timestamp,
            isAutomated = true,
            severity = severity
        )
        
        addModerationAction(action)
        
        // Apply persistent ban for severe cases
        if (spamWarning.severity == SpamSeverity.CRITICAL || 
            (spamWarning.severity == SpamSeverity.HIGH && deviceRisk.riskScore > 0.7)) {
            
            val banDuration = when {
                spamWarning.severity == SpamSeverity.CRITICAL -> 48L
                deviceRisk.riskScore > 0.8 -> 24L
                else -> 12L
            }
            
            antiBypassStorage.applyPersistentBan(
                spamWarning.peerID,
                "Automated spam detection: ${spamWarning.reason}",
                banDuration,
                if (spamWarning.severity == SpamSeverity.CRITICAL) 3 else 2,
                deviceRisk.riskScore > 0.9
            )
        }
        
        // Update user profile with enhanced tracking
        updateUserProfile(spamWarning.peerID) { profile ->
            val newWarningCount = if (actionType == ActionType.WARNING_ISSUED) profile.warningCount + 1 else profile.warningCount
            val newBanCount = if (actionType == ActionType.TEMPORARY_BAN) profile.banCount + 1 else profile.banCount
            
            profile.copy(
                warningCount = newWarningCount,
                banCount = newBanCount,
                lastAction = action.timestamp,
                riskLevel = calculateEnhancedRiskLevel(newWarningCount, newBanCount, deviceRisk.riskScore)
            )
        }
        
        Log.i(TAG, "Enhanced spam detection handled for ${spamWarning.peerID}: ${actionType.name} (Device risk: ${"%.2f".format(deviceRisk.riskScore)})")
    }
    
    /**
     * Manual moderation action by an admin/moderator
     */
    fun takeManualAction(
        moderatorPeerID: String,
        targetPeerID: String,
        actionType: ActionType,
        reason: String,
        severity: ModerationSeverity = ModerationSeverity.MEDIUM
    ): Boolean {
        
        // TODO: Check if moderator has permissions
        
        val action = ModerationAction(
            id = generateActionId(),
            targetPeerID = targetPeerID,
            actionType = actionType,
            reason = reason,
            timestamp = Date(),
            moderatorPeerID = moderatorPeerID,
            isAutomated = false,
            severity = severity
        )
        
        // Execute the action
        when (actionType) {
            ActionType.TEMPORARY_BAN -> {
                spamFilterManager.unbanUser(targetPeerID) // Reset first
                // TODO: Implement manual ban duration
            }
            ActionType.PERMANENT_BAN -> {
                // TODO: Implement permanent ban
            }
            ActionType.WARNING_ISSUED -> {
                // TODO: Send warning message to user
            }
            else -> {
                // Handle other action types
            }
        }
        
        addModerationAction(action)
        
        // Update user profile
        updateUserProfile(targetPeerID) { profile ->
            val newWarningCount = if (actionType == ActionType.WARNING_ISSUED) profile.warningCount + 1 else profile.warningCount
            val newBanCount = if (actionType in listOf(ActionType.TEMPORARY_BAN, ActionType.PERMANENT_BAN)) profile.banCount + 1 else profile.banCount
            
            profile.copy(
                warningCount = newWarningCount,
                banCount = newBanCount,
                lastAction = action.timestamp,
                riskLevel = calculateRiskLevel(newWarningCount, newBanCount)
            )
        }
        
        Log.i(TAG, "Manual action taken by $moderatorPeerID against $targetPeerID: ${actionType.name}")
        
        return true
    }
    
    /**
     * Get user's moderation profile
     */
    fun getUserProfile(peerID: String): UserModerationProfile {
        return userProfiles[peerID] ?: UserModerationProfile(peerID)
    }
    
    /**
     * Get user's recent moderation actions
     */
    fun getUserActions(peerID: String, limit: Int = 20): List<ModerationAction> {
        return actions.filter { it.targetPeerID == peerID }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    /**
     * Get overall moderation statistics
     */
    fun getStats(): ModerationStats {
        return _moderationStats.value
    }
    
    /**
     * Check if user should be flagged for review
     */
    fun shouldFlagForReview(peerID: String): Boolean {
        val profile = getUserProfile(peerID)
        
        return when {
            profile.riskLevel == RiskLevel.CRITICAL -> true
            profile.warningCount >= 5 -> true
            profile.banCount >= 2 -> true
            profile.reportsAgainst >= 3 -> true
            profile.trustScore <= 0.3 -> true
            else -> false
        }
    }
    
    /**
     * Generate moderation summary for admin dashboard
     */
    fun getModerationSummary(): Map<String, Any> {
        val stats = getStats()
        val highRiskUsers = userProfiles.values.filter { it.riskLevel in listOf(RiskLevel.HIGH, RiskLevel.CRITICAL) }
        val recentActions = actions.filter { it.timestamp.time > System.currentTimeMillis() - 24 * 60 * 60 * 1000 }
        
        return mapOf(
            "stats" to stats,
            "highRiskUsers" to highRiskUsers.size,
            "recentActions" to recentActions.size,
            "pendingReports" to communityReportManager.pendingReports.value.size,
            "bannedUsers" to spamFilterManager.bannedUsers.value.size
        )
    }
    
    // Helper functions
    
    private fun addModerationAction(action: ModerationAction) {
        actions.add(action)
        
        // Keep only last 1000 actions
        if (actions.size > 1000) {
            actions.removeAt(0)
        }
        
        updateFlows()
        saveModerationData()
    }
    
    private fun updateUserProfile(peerID: String, updateFn: (UserModerationProfile) -> UserModerationProfile) {
        val currentProfile = userProfiles[peerID] ?: UserModerationProfile(peerID)
        val updatedProfile = updateFn(currentProfile)
        userProfiles[peerID] = updatedProfile
        
        updateFlows()
        saveModerationData()
    }
    
    private fun calculateRiskLevel(warningCount: Int, banCount: Int): RiskLevel {
        return calculateEnhancedRiskLevel(warningCount, banCount, 0.0)
    }
    
    /**
     * Enhanced risk level calculation incorporating device risk
     */
    private fun calculateEnhancedRiskLevel(warningCount: Int, banCount: Int, deviceRiskScore: Double): RiskLevel {
        val baseRisk = when {
            banCount >= 3 || warningCount >= 10 -> 4
            banCount >= 2 || warningCount >= 6 -> 3
            banCount >= 1 || warningCount >= 3 -> 2
            else -> 1
        }
        
        // Add device risk factor
        val deviceRiskBonus = when {
            deviceRiskScore > 0.8 -> 2
            deviceRiskScore > 0.6 -> 1
            else -> 0
        }
        
        val totalRisk = baseRisk + deviceRiskBonus
        
        return when {
            totalRisk >= 5 -> RiskLevel.CRITICAL
            totalRisk >= 4 -> RiskLevel.HIGH
            totalRisk >= 3 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
    
    private fun mapReportReasonToSeverity(reason: ReportReason): ModerationSeverity {
        return when (reason) {
            ReportReason.SPAM -> ModerationSeverity.MEDIUM
            ReportReason.HARASSMENT -> ModerationSeverity.HIGH
            ReportReason.HATE_SPEECH -> ModerationSeverity.HIGH
            ReportReason.SCAM -> ModerationSeverity.HIGH
            ReportReason.IMPERSONATION -> ModerationSeverity.HIGH
            ReportReason.INAPPROPRIATE_CONTENT -> ModerationSeverity.MEDIUM
            ReportReason.OTHER -> ModerationSeverity.LOW
        }
    }
    
    private fun generateActionId(): String {
        return "action_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
    
    private fun observeSystemChanges() {
        scope.launch {
            combine(
                spamFilterManager.bannedUsers,
                communityReportManager.pendingReports,
                communityReportManager.validatedReports
            ) { bannedUsers, pendingReports, validatedReports ->
                // React to system changes
                Log.d(TAG, "System state: ${bannedUsers.size} banned, ${pendingReports.size} pending reports, ${validatedReports.size} validated")
            }
        }
    }
    
    private fun updateFlows() {
        _moderationActions.value = actions.sortedByDescending { it.timestamp }.take(100)
        _userProfiles.value = userProfiles.toMap()
        
        // Update stats
        val stats = ModerationStats(
            totalWarnings = actions.count { it.actionType == ActionType.WARNING_ISSUED },
            totalBans = actions.count { it.actionType in listOf(ActionType.TEMPORARY_BAN, ActionType.PERMANENT_BAN) },
            totalReports = actions.count { it.actionType == ActionType.REPORT_VALIDATED },
            validatedReports = communityReportManager.validatedReports.value.size,
            dismissedReports = actions.count { it.actionType == ActionType.REPORT_DISMISSED },
            automatedActions = actions.count { it.isAutomated },
            manualActions = actions.count { !it.isAutomated }
        )
        _moderationStats.value = stats
    }
    
    private fun loadModerationData() {
        // TODO: Load from DataManager persistent storage
        updateFlows()
    }
    
    private fun saveModerationData() {
        // TODO: Save to DataManager persistent storage
    }
}