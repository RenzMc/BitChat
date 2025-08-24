package com.renchat.android.ui

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import com.renchat.android.ui.ReportReason

/**
 * Data classes for community reporting
 */
data class CommunityReport(
    val id: String,
    val reporterPeerID: String,
    val targetPeerID: String,
    val reason: ReportReason,
    val description: String,
    val timestamp: Date,
    val messageContent: String? = null,
    val status: ReportStatus = ReportStatus.PENDING,
    val validationScore: Double = 0.0
)


enum class ReportStatus {
    PENDING,
    UNDER_REVIEW,
    VALIDATED,
    DISMISSED,
    ACTION_TAKEN
}

data class ReportValidation(
    val reportId: String,
    val isValid: Boolean,
    val confidence: Double,
    val reasons: List<String>
)

data class ReporterHistory(
    val peerID: String,
    val totalReports: Int = 0,
    val validReports: Int = 0,
    val invalidReports: Int = 0,
    val trustScore: Double = 1.0,
    val lastReportTime: Date? = null,
    val isSuspiciousReporter: Boolean = false
)

/**
 * Community reporting manager with anti-bot validation
 * Prevents spam reports and validates report authenticity
 */
class CommunityReportManager(
    private val dataManager: DataManager
) {
    companion object {
        private const val TAG = "CommunityReportManager"
        private const val MIN_TRUST_SCORE = 0.2 // More lenient for new users
        private const val MAX_REPORTS_PER_HOUR = 4 // Slightly reduced to prevent spam
        private const val BOT_DETECTION_THRESHOLD = 0.7 // More sensitive bot detection
        private const val VALIDATION_THRESHOLD = 0.65 // Slightly higher validation threshold
        private const val MANUAL_REVIEW_THRESHOLD = 0.85 // Flag for manual review, NO auto-bans
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.9 // Very high confidence for priority review
        private const val MAX_DAILY_REPORTS = 15 // Daily limit to prevent abuse
        private const val COORDINATED_REPORT_THRESHOLD = 3 // Multiple reports in short time
    }
    
    private val reports = ConcurrentHashMap<String, CommunityReport>()
    private val reporterHistories = ConcurrentHashMap<String, ReporterHistory>()
    private val reportsByTarget = ConcurrentHashMap<String, MutableList<String>>() // targetPeerID -> report IDs
    private val reportsByReporter = ConcurrentHashMap<String, MutableList<String>>() // reporterPeerID -> report IDs
    
    private val _pendingReports = MutableStateFlow<List<CommunityReport>>(emptyList())
    val pendingReports: StateFlow<List<CommunityReport>> = _pendingReports.asStateFlow()
    
    private val _validatedReports = MutableStateFlow<List<CommunityReport>>(emptyList())
    val validatedReports: StateFlow<List<CommunityReport>> = _validatedReports.asStateFlow()
    
    init {
        loadReportData()
    }
    
    /**
     * Submit a community report with bot detection
     */
    fun submitReport(
        reporterPeerID: String,
        targetPeerID: String,
        reason: ReportReason,
        description: String,
        messageContent: String? = null
    ): String? {
        
        // 1. Validate reporter eligibility
        if (!isReporterEligible(reporterPeerID)) {
            Log.w(TAG, "Reporter $reporterPeerID not eligible to submit reports")
            return null
        }
        
        // 2. Check for bot behavior
        if (detectBotReporting(reporterPeerID, targetPeerID)) {
            Log.w(TAG, "Bot reporting detected from $reporterPeerID")
            markSuspiciousReporter(reporterPeerID)
            return null
        }
        
        // 3. Check rate limiting
        if (isReportRateLimited(reporterPeerID)) {
            Log.w(TAG, "Report rate limit exceeded for $reporterPeerID")
            return null
        }
        
        // 4. Create report
        val reportId = generateReportId()
        val report = CommunityReport(
            id = reportId,
            reporterPeerID = reporterPeerID,
            targetPeerID = targetPeerID,
            reason = reason,
            description = description,
            timestamp = Date(),
            messageContent = messageContent
        )
        
        // 5. Validate report authenticity
        val validation = validateReport(report)
        val updatedReport = report.copy(
            validationScore = validation.confidence,
            status = if (validation.isValid) ReportStatus.UNDER_REVIEW else ReportStatus.DISMISSED
        )
        
        // 6. Store report
        reports[reportId] = updatedReport
        addReportToIndices(updatedReport)
        updateReporterHistory(reporterPeerID, validation.isValid)
        
        // 7. Enhanced review system - NO auto-bans, only flagging for review
        if (validation.confidence >= HIGH_CONFIDENCE_THRESHOLD && validation.isValid) {
            // Flag for priority manual review instead of auto-action
            val priorityReport = updatedReport.copy(
                status = ReportStatus.UNDER_REVIEW
            )
            reports[reportId] = priorityReport
            
            Log.i(TAG, "High-confidence report flagged for priority review: $reportId (Confidence: ${validation.confidence})")
        } else if (validation.confidence >= MANUAL_REVIEW_THRESHOLD && validation.isValid) {
            // Flag for standard manual review
            val reviewReport = updatedReport.copy(
                status = ReportStatus.UNDER_REVIEW
            )
            reports[reportId] = reviewReport
            
            Log.i(TAG, "Report flagged for manual review: $reportId")
        }
        
        updateFlows()
        saveReportData()
        
        Log.i(TAG, "Report $reportId submitted by $reporterPeerID against $targetPeerID (Confidence: ${validation.confidence})")
        
        return reportId
    }
    
    /**
     * Validate a report's authenticity using multiple factors
     */
    private fun validateReport(report: CommunityReport): ReportValidation {
        var confidence = 0.5 // Base confidence
        val reasons = mutableListOf<String>()
        
        // 1. Check reporter trust score
        val reporterHistory = reporterHistories[report.reporterPeerID]
        if (reporterHistory != null) {
            confidence += (reporterHistory.trustScore - 0.5) * 0.3
            if (reporterHistory.trustScore >= 0.8) {
                reasons.add("Trusted reporter")
            } else if (reporterHistory.trustScore <= 0.3) {
                reasons.add("Low trust reporter")
                confidence -= 0.2
            }
        }
        
        // 2. Check for duplicate/similar reports
        val similarReports = findSimilarReports(report)
        if (similarReports.isNotEmpty()) {
            confidence += 0.2
            reasons.add("Multiple similar reports")
        }
        
        // 3. Analyze report content quality
        val contentScore = analyzeReportContent(report)
        confidence += contentScore * 0.2
        if (contentScore > 0.7) {
            reasons.add("Detailed report description")
        }
        
        // 4. Check target's recent activity
        val targetReportCount = getRecentReportCount(report.targetPeerID)
        if (targetReportCount >= 3) {
            confidence += 0.15
            reasons.add("Multiple recent reports against target")
        }
        
        // 5. Time-based validation (reports during peak hours are more suspicious)
        val timeScore = analyzeReportTiming(report.timestamp)
        confidence += timeScore * 0.1
        
        // 6. Pattern detection for coordinated reporting
        val coordinationScore = detectCoordinatedReporting(report)
        confidence -= coordinationScore * 0.3
        if (coordinationScore > 0.5) {
            reasons.add("Potential coordinated reporting")
        }
        
        // Clamp confidence between 0 and 1
        confidence = confidence.coerceIn(0.0, 1.0)
        
        val isValid = confidence >= VALIDATION_THRESHOLD
        
        return ReportValidation(
            reportId = report.id,
            isValid = isValid,
            confidence = confidence,
            reasons = reasons
        )
    }
    
    /**
     * Advanced bot detection with sophisticated pattern analysis
     */
    private fun detectBotReporting(reporterPeerID: String, targetPeerID: String): Boolean {
        val reporterHistory = reporterHistories[reporterPeerID] ?: return false
        
        var botScore = 0.0
        
        // 1. Enhanced reporting frequency analysis
        val recentReports = getReporterRecentReports(reporterPeerID, hours = 1)
        when {
            recentReports.size >= 4 -> botScore += 0.4
            recentReports.size >= 3 -> botScore += 0.25
            recentReports.size >= 2 -> botScore += 0.1
        }
        
        // 2. Sophisticated description analysis
        val reporterReports = getReporterRecentReports(reporterPeerID, hours = 24)
        
        // Check for identical descriptions
        val descriptionGroups = reporterReports.groupBy { it.description }
        val maxIdentical = descriptionGroups.values.maxOfOrNull { it.size } ?: 0
        if (maxIdentical >= 4) {
            botScore += 0.5
        } else if (maxIdentical >= 3) {
            botScore += 0.3
        }
        
        // Check for similar descriptions (template-based)
        val similarityThreshold = 0.8
        var similarDescriptions = 0
        reporterReports.forEach { report1 ->
            reporterReports.forEach { report2 ->
                if (report1 != report2 && calculateDescriptionSimilarity(report1.description, report2.description) > similarityThreshold) {
                    similarDescriptions++
                }
            }
        }
        if (similarDescriptions >= 6) { // 3 pairs or more
            botScore += 0.35
        }
        
        // 3. Timing pattern analysis
        val lastReport = reporterHistory.lastReportTime
        if (lastReport != null) {
            val timeDiff = Date().time - lastReport.time
            when {
                timeDiff < 30000 -> botScore += 0.4 // Less than 30 seconds
                timeDiff < 60000 -> botScore += 0.25 // Less than 1 minute
                timeDiff < 120000 -> botScore += 0.1 // Less than 2 minutes
            }
        }
        
        // 4. Target diversity check (bots often target multiple users quickly)
        val recentTargets = reporterReports.map { it.targetPeerID }.toSet()
        if (recentTargets.size >= 5 && reporterReports.size >= 8) {
            botScore += 0.25 // Targeting many different users
        }
        
        // 5. Report reason patterns (bots often use same reasons)
        val reasonGroups = reporterReports.groupBy { it.reason }
        val maxSameReason = reasonGroups.values.maxOfOrNull { it.size } ?: 0
        if (maxSameReason >= reporterReports.size * 0.8 && reporterReports.size >= 5) {
            botScore += 0.2 // Using same reason for 80%+ of reports
        }
        
        // 6. Trust score penalty
        if (reporterHistory.trustScore <= MIN_TRUST_SCORE) {
            botScore += 0.15
        }
        
        // 7. Account age factor (very new accounts are more suspicious)
        val accountAge = reporterHistory.lastReportTime?.let { 
            Date().time - it.time 
        } ?: Long.MAX_VALUE
        if (accountAge < 3600000) { // Less than 1 hour old
            botScore += 0.2
        }
        
        return botScore >= BOT_DETECTION_THRESHOLD
    }
    
    /**
     * Detect coordinated reporting campaigns
     */
    private fun detectCoordinatedReporter(reporterPeerID: String): Boolean {
        val recentReports = getReporterRecentReports(reporterPeerID, 6) // Last 6 hours
        if (recentReports.size < COORDINATED_REPORT_THRESHOLD) return false
        
        // Check if this reporter is part of a coordinated attack
        val targetCounts = mutableMapOf<String, Int>()
        reports.values.filter { 
            it.timestamp.time > System.currentTimeMillis() - 6 * 60 * 60 * 1000 
        }.forEach { report ->
            targetCounts[report.targetPeerID] = targetCounts.getOrDefault(report.targetPeerID, 0) + 1
        }
        
        // Check if reporter is targeting same users as many others
        var coordinationScore = 0
        recentReports.forEach { report ->
            val targetReportCount = targetCounts[report.targetPeerID] ?: 0
            if (targetReportCount >= COORDINATED_REPORT_THRESHOLD) {
                coordinationScore++
            }
        }
        
        return coordinationScore >= 2 // Reporter involved in 2+ coordinated attacks
    }
    
    /**
     * Calculate similarity between report descriptions
     */
    private fun calculateDescriptionSimilarity(desc1: String, desc2: String): Double {
        if (desc1 == desc2) return 1.0
        if (desc1.isEmpty() || desc2.isEmpty()) return 0.0
        
        val words1 = desc1.lowercase().split("\\s+".toRegex()).toSet()
        val words2 = desc2.lowercase().split("\\s+".toRegex()).toSet()
        
        val intersection = words1.intersect(words2)
        val union = words1.union(words2)
        
        return if (union.isNotEmpty()) intersection.size.toDouble() / union.size else 0.0
    }
    
    /**
     * Enhanced reporter eligibility check with daily limits and pattern detection
     */
    private fun isReporterEligible(reporterPeerID: String): Boolean {
        val history = reporterHistories[reporterPeerID] ?: return true
        
        // Basic trust and suspicious reporter check
        if (history.isSuspiciousReporter || history.trustScore < MIN_TRUST_SCORE) {
            return false
        }
        
        // Check daily reporting limit
        val todayReports = getReporterRecentReports(reporterPeerID, 24).size
        if (todayReports >= MAX_DAILY_REPORTS) {
            Log.w(TAG, "Reporter $reporterPeerID exceeded daily report limit: $todayReports")
            return false
        }
        
        // Check for coordinated reporting patterns
        if (detectCoordinatedReporter(reporterPeerID)) {
            Log.w(TAG, "Coordinated reporting detected for $reporterPeerID")
            return false
        }
        
        return true
    }
    
    /**
     * Check if reporter is rate limited
     */
    private fun isReportRateLimited(reporterPeerID: String): Boolean {
        val recentReports = getReporterRecentReports(reporterPeerID, hours = 1)
        return recentReports.size >= MAX_REPORTS_PER_HOUR
    }
    
    /**
     * Mark reporter as suspicious
     */
    private fun markSuspiciousReporter(reporterPeerID: String) {
        val history = reporterHistories[reporterPeerID] ?: ReporterHistory(reporterPeerID)
        val updatedHistory = history.copy(
            isSuspiciousReporter = true,
            trustScore = (history.trustScore * 0.5).coerceAtLeast(0.1)
        )
        reporterHistories[reporterPeerID] = updatedHistory
        saveReportData()
    }
    
    /**
     * Process a validated report and take action
     */
    private fun processValidatedReport(report: CommunityReport) {
        val updatedReport = report.copy(status = ReportStatus.ACTION_TAKEN)
        reports[report.id] = updatedReport
        
        // Here you would integrate with SpamFilterManager or other action systems
        Log.i(TAG, "Taking action on validated report ${report.id} against ${report.targetPeerID}")
        
        updateFlows()
        saveReportData()
    }
    
    /**
     * Get reports for a specific target
     */
    fun getTargetReports(targetPeerID: String): List<CommunityReport> {
        val reportIds = reportsByTarget[targetPeerID] ?: return emptyList()
        return reportIds.mapNotNull { reports[it] }
    }
    
    /**
     * Get reporter's report history
     */
    fun getReporterHistory(reporterPeerID: String): ReporterHistory? {
        return reporterHistories[reporterPeerID]
    }
    
    /**
     * Administrative function to review a report
     */
    fun reviewReport(reportId: String, isValid: Boolean, adminPeerID: String): Boolean {
        val report = reports[reportId] ?: return false
        
        val newStatus = if (isValid) ReportStatus.VALIDATED else ReportStatus.DISMISSED
        val updatedReport = report.copy(status = newStatus)
        reports[reportId] = updatedReport
        
        // Update reporter trust score
        updateReporterHistory(report.reporterPeerID, isValid)
        
        if (isValid) {
            processValidatedReport(updatedReport)
        }
        
        updateFlows()
        saveReportData()
        
        Log.i(TAG, "Report $reportId reviewed by $adminPeerID: ${if (isValid) "VALID" else "INVALID"}")
        
        return true
    }
    
    // Helper functions
    
    private fun generateReportId(): String {
        return "report_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
    }
    
    private fun addReportToIndices(report: CommunityReport) {
        // Add to target index
        val targetReports = reportsByTarget.getOrPut(report.targetPeerID) { mutableListOf() }
        targetReports.add(report.id)
        
        // Add to reporter index
        val reporterReports = reportsByReporter.getOrPut(report.reporterPeerID) { mutableListOf() }
        reporterReports.add(report.id)
    }
    
    private fun updateReporterHistory(reporterPeerID: String, wasValid: Boolean) {
        val history = reporterHistories[reporterPeerID] ?: ReporterHistory(reporterPeerID)
        
        val newTotal = history.totalReports + 1
        val newValid = history.validReports + if (wasValid) 1 else 0
        val newInvalid = history.invalidReports + if (!wasValid) 1 else 0
        
        // Calculate new trust score
        val accuracy = if (newTotal > 0) newValid.toDouble() / newTotal else 0.5
        val newTrustScore = (accuracy * 0.7 + history.trustScore * 0.3).coerceIn(0.1, 1.0)
        
        val updatedHistory = history.copy(
            totalReports = newTotal,
            validReports = newValid,
            invalidReports = newInvalid,
            trustScore = newTrustScore,
            lastReportTime = Date()
        )
        
        reporterHistories[reporterPeerID] = updatedHistory
    }
    
    private fun findSimilarReports(report: CommunityReport): List<CommunityReport> {
        val targetReports = getTargetReports(report.targetPeerID)
        return targetReports.filter { 
            it.reason == report.reason && 
            it.timestamp.time > (report.timestamp.time - 24 * 60 * 60 * 1000) // Last 24 hours
        }
    }
    
    private fun analyzeReportContent(report: CommunityReport): Double {
        val description = report.description
        var score = 0.0
        
        // Length check
        if (description.length >= 20) score += 0.3
        if (description.length >= 50) score += 0.2
        
        // Word variety check
        val words = description.split("\\s+".toRegex())
        val uniqueWords = words.toSet().size
        if (uniqueWords > words.size * 0.7) score += 0.3
        
        // Specific details check
        if (description.contains(Regex("\\b(said|wrote|sent|posted)\\b", RegexOption.IGNORE_CASE))) {
            score += 0.2
        }
        
        return score.coerceIn(0.0, 1.0)
    }
    
    private fun analyzeReportTiming(timestamp: Date): Double {
        // Reports during normal hours are more trustworthy
        val hour = java.util.Calendar.getInstance().apply { time = timestamp }.get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 6..22 -> 0.3  // Normal hours
            in 23..24, in 0..5 -> -0.2  // Late night/early morning (more suspicious)
            else -> 0.0
        }
    }
    
    private fun detectCoordinatedReporting(report: CommunityReport): Double {
        val recentReports = getTargetReports(report.targetPeerID)
            .filter { it.timestamp.time > (report.timestamp.time - 60 * 60 * 1000) } // Last hour
        
        if (recentReports.size < 2) return 0.0
        
        // Check for identical descriptions
        val identicalCount = recentReports.count { it.description == report.description }
        if (identicalCount >= 2) return 0.8
        
        // Check for rapid succession
        val timeDiffs = recentReports.map { Math.abs(it.timestamp.time - report.timestamp.time) }
        val rapidReports = timeDiffs.count { it < 5 * 60 * 1000 } // Within 5 minutes
        
        return (rapidReports.toDouble() / recentReports.size).coerceIn(0.0, 1.0)
    }
    
    private fun getReporterRecentReports(reporterPeerID: String, hours: Int): List<CommunityReport> {
        val cutoff = Date(System.currentTimeMillis() - hours * 60 * 60 * 1000)
        val reportIds = reportsByReporter[reporterPeerID] ?: return emptyList()
        return reportIds.mapNotNull { reports[it] }.filter { it.timestamp.after(cutoff) }
    }
    
    private fun getReporterReports(reporterPeerID: String): List<CommunityReport> {
        val reportIds = reportsByReporter[reporterPeerID] ?: return emptyList()
        return reportIds.mapNotNull { reports[it] }
    }
    
    private fun getRecentReportCount(targetPeerID: String): Int {
        val yesterday = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        return getTargetReports(targetPeerID).count { it.timestamp.after(yesterday) }
    }
    
    private fun updateFlows() {
        val pending = reports.values.filter { it.status == ReportStatus.PENDING || it.status == ReportStatus.UNDER_REVIEW }
        val validated = reports.values.filter { it.status == ReportStatus.VALIDATED }
        
        _pendingReports.value = pending.sortedByDescending { it.timestamp }
        _validatedReports.value = validated.sortedByDescending { it.timestamp }
    }
    
    private fun loadReportData() {
        // TODO: Load from DataManager persistent storage
        updateFlows()
    }
    
    private fun saveReportData() {
        // TODO: Save to DataManager persistent storage
    }
}