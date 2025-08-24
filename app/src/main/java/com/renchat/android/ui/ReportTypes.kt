package com.renchat.android.ui

/**
 * Report reason enum for community reporting
 */
enum class ReportReason {
    SPAM,
    HARASSMENT,
    INAPPROPRIATE_CONTENT,
    SCAM,
    HATE_SPEECH,
    IMPERSONATION,
    OTHER
}

/**
 * Risk level enum for user moderation
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * User moderation profile data class
 */
data class UserModerationProfile(
    val peerID: String,
    val warningCount: Int = 0,
    val banCount: Int = 0,
    val reportsMade: Int = 0,
    val reportsAgainst: Int = 0,
    val trustScore: Double = 1.0,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val lastAction: java.util.Date? = null
)

// Extension functions for ReportReason
fun ReportReason.getDisplayName(): String {
    return when (this) {
        ReportReason.SPAM -> "Spam"
        ReportReason.HARASSMENT -> "Harassment"
        ReportReason.INAPPROPRIATE_CONTENT -> "Inappropriate Content"
        ReportReason.SCAM -> "Scam/Fraud"
        ReportReason.HATE_SPEECH -> "Hate Speech"
        ReportReason.IMPERSONATION -> "Impersonation"
        ReportReason.OTHER -> "Other"
    }
}

fun ReportReason.getDescription(): String {
    return when (this) {
        ReportReason.SPAM -> "Repeated unwanted messages or promotional content"
        ReportReason.HARASSMENT -> "Targeting, bullying, or threatening behavior"
        ReportReason.INAPPROPRIATE_CONTENT -> "Offensive, explicit, or unsuitable content"
        ReportReason.SCAM -> "Attempting to defraud or steal from users"
        ReportReason.HATE_SPEECH -> "Content promoting hatred or discrimination"
        ReportReason.IMPERSONATION -> "Pretending to be someone else"
        ReportReason.OTHER -> "Other violations not listed above"
    }
}