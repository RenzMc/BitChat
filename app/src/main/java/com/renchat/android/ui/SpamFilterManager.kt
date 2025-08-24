package com.renchat.android.ui

import android.content.Context
import android.util.Log
import com.renchat.android.security.AntiBypassStorage
import com.renchat.android.security.DeviceFingerprintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Data classes for spam filtering
 */
data class SpamWarning(
    val peerID: String,
    val timestamp: Date,
    val reason: String,
    val severity: SpamSeverity
)

enum class SpamSeverity {
    LOW,    // Minor spam patterns
    MEDIUM, // Moderate spam patterns  
    HIGH,   // Severe spam patterns
    CRITICAL // Immediate ban territory
}

data class SpamPattern(
    val pattern: Regex,
    val severity: SpamSeverity,
    val description: String
)

data class UserSpamRecord(
    val peerID: String,
    val warningCount: Int = 0,
    val lastWarning: Date? = null,
    val totalSpamScore: Int = 0,
    val isBanned: Boolean = false,
    val banReason: String? = null,
    val banExpiry: Date? = null
)

/**
 * Advanced spam filtering manager with sophisticated anti-bypass detection
 * Uses graduated penalties, device fingerprinting, and user-friendly thresholds
 * Optimized to not disrupt normal users while being highly effective against spam
 */
class SpamFilterManager(
    private val context: Context,
    private val dataManager: DataManager
) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deviceFingerprintManager = DeviceFingerprintManager(context)
    private val antiBypassStorage = AntiBypassStorage(context, deviceFingerprintManager)
    companion object {
        private const val TAG = "SpamFilterManager"
        
        // Rate limiting - more user-friendly
        private const val BASE_MESSAGE_RATE_LIMIT = 18 // messages per minute for normal users (increased)
        private const val TRUSTED_USER_RATE_LIMIT = 30 // higher limit for trusted users (increased)
        private const val NEW_USER_RATE_LIMIT = 10 // lower limit for new users (increased)
        private const val BURST_ALLOWANCE = 5 // Allow short bursts of messages
        
        // Graduated warning system
        private const val WARNINGS_BEFORE_TEMP_BAN = 4 // increased from 3
        private const val TEMP_BAN_DURATION_HOURS = 12 // reduced from 24
        private const val LONG_BAN_DURATION_HOURS = 48
        private const val PERMANENT_BAN_THRESHOLD = 6
        
        // Spam detection thresholds - optimized for normal users
        private const val DUPLICATE_MESSAGE_THRESHOLD = 5 // increased from 4 for better UX
        private const val CAPS_PERCENTAGE_THRESHOLD = 90 // increased from 85 (people use caps for emphasis)
        private const val MIN_MESSAGE_LENGTH_FOR_CAPS_CHECK = 20 // increased from 15 (short excited messages OK)
        private const val RAPID_FIRE_THRESHOLD = 6 // messages in 10 seconds (increased)
        private const val SUSPICIOUS_PATTERN_SCORE = 50 // increased for better detection
        private const val AUTO_BAN_SCORE = 90 // increased for stricter control
        private const val CONTEXT_LEARNING_THRESHOLD = 10 // Learn from user patterns
        private const val TRUST_DECAY_FACTOR = 0.02 // Slowly decay trust over time
        private const val WHITELIST_LEARNING_THRESHOLD = 5 // Learn user patterns
    }
    
    // Enhanced user tracking with trust scores
    private val userRecords = ConcurrentHashMap<String, UserSpamRecord>()
    private val recentWarnings = mutableListOf<SpamWarning>()
    private val messageHistory = ConcurrentHashMap<String, MutableList<String>>() // peerID -> recent messages
    private val messageTimestamps = ConcurrentHashMap<String, MutableList<Date>>() // peerID -> timestamps
    private val userTrustScores = ConcurrentHashMap<String, Double>() // peerID -> trust score (0.0-1.0)
    private val userJoinTimes = ConcurrentHashMap<String, Long>() // peerID -> first seen timestamp
    private val rapidFireTracker = ConcurrentHashMap<String, MutableList<Long>>() // peerID -> rapid message timestamps
    private val userContextualPatterns = ConcurrentHashMap<String, MutableSet<String>>() // Learn user's normal patterns
    private val whitelistedPhrases = ConcurrentHashMap<String, MutableSet<String>>() // User-specific whitelist
    
    // Enhanced spam patterns with sophisticated detection
    private val spamPatterns = listOf(
        // URL spam patterns (more refined)
        SpamPattern(
            Regex("""(https?://[^\s]+){4,}""", RegexOption.IGNORE_CASE), // Increased threshold
            SpamSeverity.HIGH,
            "Multiple URL spam"
        ),
        SpamPattern(
            Regex("""(bit\.ly|tinyurl|t\.co|goo\.gl|short\.link|tny\.im|is\.gd|v\.gd)[^\s]*""", RegexOption.IGNORE_CASE),
            SpamSeverity.MEDIUM,
            "Suspicious shortened URLs"
        ),
        
        // Enhanced promotional spam detection
        SpamPattern(
            Regex("""(buy now|click here|limited time|act fast|free money|earn \$\d+|make money|get rich|no deposit|bonus|promo code|discount code|special offer|mlm|pyramid scheme|work from home|passive income|affiliate marketing).*?(guaranteed|instant|easy|quick|automatic)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Promotional spam"
        ),
        
        // Advanced crypto/scam patterns  
        SpamPattern(
            Regex("""(bitcoin|btc|crypto|nft|investment|trading signal|forex|binary options|pump|dump|altcoin|defi|yield farming|liquidity mining|airdrop|presale|ico|ido).*?(guaranteed|profit|100%|easy money|risk free|sure win|moon|lambo|diamond hands|hodl)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Financial scam patterns"
        ),
        
        // Dating/Adult spam
        SpamPattern(
            Regex("""(hot singles|meet women|adult dating|cam girls|xxx|porn|sexy|hookup|sugar daddy|escort)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Adult/Dating spam"
        ),
        
        // Phishing patterns
        SpamPattern(
            Regex("""(verify account|suspended account|click to verify|confirm identity|security alert|account locked|login here|update payment|claim reward|unusual activity|suspicious login|verify now|account compromised|immediate action required|temporary suspension)""", RegexOption.IGNORE_CASE),
            SpamSeverity.CRITICAL,
            "Phishing attempt"
        ),
        
        // Bot-like repetitive patterns (refined)
        SpamPattern(
            Regex("""(.)\1{12,}"""), // Same character repeated 12+ times (increased threshold)
            SpamSeverity.LOW,
            "Character spam"
        ),
        
        // Word repetition spam
        SpamPattern(
            Regex("""\b(\w+)\s+\1\s+\1\s+\1\b""", RegexOption.IGNORE_CASE), // Same word repeated 4+ times
            SpamSeverity.MEDIUM,
            "Word repetition spam"
        ),
        
        // Excessive emoji (more lenient)
        SpamPattern(
            Regex("""([\u{1F600}-\u{1F64F}]|[\u{1F300}-\u{1F5FF}]|[\u{1F680}-\u{1F6FF}]|[\u{1F1E0}-\u{1F1FF}]){20,}"""), // Increased from 15
            SpamSeverity.MEDIUM,
            "Excessive emoji spam"
        ),
        
        // Malicious links and social engineering
        SpamPattern(
            Regex("""(discord\.gg/[^\s]+|t\.me/[^\s]+|join\s+my\s+server|free\s+nitro|free\s+discord|steam\s+gift|free\s+games|csgo\s+skins|valorant\s+points|robux\s+generator|minecraft\s+account)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Social platform spam"
        ),
        
        // Gambling spam
        SpamPattern(
            Regex("""(casino|slots|poker|blackjack|roulette|bet|gambling|lottery|scratch card).*?(win|bonus|free|play now)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Gambling spam"
        ),
        
        // Extreme caps abuse (more lenient for normal conversation)
        SpamPattern(
            Regex("""[A-Z\s]{30,}"""), // 30+ consecutive caps (very high threshold)
            SpamSeverity.LOW,
            "Excessive caps"
        ),
        
        // Enhanced modern scam patterns
        SpamPattern(
            Regex("""(tesla\s+giveaway|elon\s+musk\s+giveaway|apple\s+giveaway|amazon\s+gift\s+card|google\s+play\s+card|steam\s+wallet|paypal\s+money|cash\s+app\s+flip|venmo\s+flip|zelle\s+flip).*?(send\s+first|trust\s+me|guarantee|legit|verified)""", RegexOption.IGNORE_CASE),
            SpamSeverity.CRITICAL,
            "Modern financial scam"
        ),
        
        // Sophisticated crypto MEV/defi scams
        SpamPattern(
            Regex("""(mev\s+bot|sandwich\s+attack|flash\s+loan|arbitrage\s+bot|front\s+running|sniper\s+bot|rugpull\s+scanner|degen\s+ape|shitcoin|moonshot|rug\s+pull|honey\s+pot|liquidity\s+lock|fair\s+launch|stealth\s+launch|presale\s+bot).*?(guaranteed\s+profit|100%\s+roi|passive\s+income|easy\s+money|no\s+risk)""", RegexOption.IGNORE_CASE),
            SpamSeverity.CRITICAL,
            "Advanced DeFi scam"
        ),
        
        // Social engineering and urgency tactics
        SpamPattern(
            Regex("""(urgent|emergency|immediate|asap|hurry|quick|fast|limited\s+time|expires\s+soon|act\s+now|don't\s+miss|last\s+chance|only\s+today|final\s+offer).*?(send\s+money|wire\s+transfer|western\s+union|moneygram|bitcoin\s+payment|crypto\s+payment|gift\s+cards|prepaid\s+cards)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Social engineering urgency scam"
        ),
        
        // AI-generated text patterns (ChatGPT, Claude, etc.)
        SpamPattern(
            Regex("""(as\s+an\s+ai|i'm\s+an\s+ai|artificial\s+intelligence|language\s+model|i\s+don't\s+have\s+personal|i\s+can't\s+browse|i\s+don't\s+have\s+access\s+to\s+real-time|my\s+knowledge\s+cutoff|i\s+apologize\s+for\s+any\s+confusion|i\s+understand\s+you're\s+looking\s+for|here's\s+what\s+i\s+can\s+help\s+with)""", RegexOption.IGNORE_CASE),
            SpamSeverity.MEDIUM,
            "AI-generated spam content"
        ),
        
        // Advanced romance/catfish scams
        SpamPattern(
            Regex("""(lonely\s+soldier|widowed|deployed|overseas|military\s+base|peacekeeping|un\s+mission).*?(love\s+you|miss\s+you|need\s+money|emergency|medical\s+bills|visa\s+fees|travel\s+expenses|western\s+union)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Romance/Military impersonation scam"
        ),
        
        // Modern social media manipulation
        SpamPattern(
            Regex("""(follow\s+back|f4f|like\s+for\s+like|sub\s+for\s+sub|subscribe\s+to\s+my|check\s+out\s+my\s+profile|dm\s+me|hit\s+me\s+up|add\s+me\s+on).*?(instagram|tiktok|snapchat|onlyfans|twitter|youtube|twitch|discord)""", RegexOption.IGNORE_CASE),
            SpamSeverity.MEDIUM,
            "Social media manipulation spam"
        ),
        
        // Sophisticated phishing with typos (intentional misspellings)
        SpamPattern(
            Regex("""(amaz0n|appl3|g00gle|micr0soft|payp4l|fac3book|netfl1x|sp0tify|uber|lyft).*?(suspended|verify|update|confirm|security|locked|unusual\s+activity)""", RegexOption.IGNORE_CASE),
            SpamSeverity.CRITICAL,
            "Brand impersonation phishing"
        ),
        
        // Advanced job scam patterns
        SpamPattern(
            Regex("""(work\s+from\s+home|remote\s+work|easy\s+money|data\s+entry|envelope\s+stuffing|mystery\s+shopper|survey\s+taker|virtual\s+assistant).*?(\$\d+\s+per\s+hour|\$\d+\s+per\s+day|no\s+experience|immediate\s+start|guaranteed\s+income|send\s+\$\d+|processing\s+fee|training\s+fee)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Work-from-home scam"
        ),
        
        // Sophisticated investment scams
        SpamPattern(
            Regex("""(forex\s+signal|binary\s+option|stock\s+tip|insider\s+trading|pump\s+and\s+dump|penny\s+stock|day\s+trading\s+course|cryptocurrency\s+course|trading\s+bot|signal\s+group).*?(guaranteed\s+return|risk\s+free|sure\s+profit|insider\s+info|exclusive\s+access|vip\s+group|limited\s+spots)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Investment scam signals"
        ),
        
        // Modern tech support scams
        SpamPattern(
            Regex("""(your\s+computer|virus\s+detected|malware\s+found|system\s+infected|microsoft\s+support|apple\s+support|windows\s+defender|security\s+alert|suspicious\s+activity\s+detected|call\s+immediately|tech\s+support).*?(call\s+now|immediate\s+action|click\s+here|download\s+now|install\s+software|remote\s+access|teamviewer|anydesk)""", RegexOption.IGNORE_CASE),
            SpamSeverity.CRITICAL,
            "Tech support scam"
        ),
        
        // Advanced referral and MLM schemes
        SpamPattern(
            Regex("""(join\s+my\s+team|business\s+opportunity|financial\s+freedom|be\s+your\s+own\s+boss|quit\s+your\s+job|make\s+money\s+online|pyramid\s+scheme|multi\s+level\s+marketing|mlm|network\s+marketing).*?(passive\s+income|residual\s+income|unlimited\s+earning|six\s+figure|seven\s+figure|millionaire\s+mindset|entrepreneur\s+lifestyle)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "MLM/Pyramid scheme spam"
        ),
        
        // Sophisticated health and supplement scams
        SpamPattern(
            Regex("""(lose\s+weight\s+fast|belly\s+fat|miracle\s+cure|natural\s+supplement|ancient\s+secret|doctors\s+hate|big\s+pharma|fda\s+approved|clinically\s+proven).*?(lose\s+\d+\s+pounds|guaranteed\s+results|no\s+side\s+effects|natural\s+ingredients|secret\s+formula|limited\s+time|order\s+now)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Health supplement scam"
        ),
        
        // Advanced fake news and conspiracy patterns
        SpamPattern(
            Regex("""(wake\s+up\s+sheeple|mainstream\s+media\s+lies|government\s+coverup|deep\s+state|illuminati|new\s+world\s+order|false\s+flag|crisis\s+actors|they\s+don't\s+want\s+you\s+to\s+know).*?(share\s+before|spread\s+the\s+word|they'll\s+delete\s+this|censored|banned|suppressed)""", RegexOption.IGNORE_CASE),
            SpamSeverity.MEDIUM,
            "Conspiracy theory spam"
        ),
        
        // Advanced gaming scams
        SpamPattern(
            Regex("""(free\s+vbucks|free\s+robux|free\s+skins|csgo\s+skins|valorant\s+points|apex\s+coins|fortnite\s+hack|minecraft\s+premium|steam\s+account|game\s+hack|aimbot|wallhack).*?(download\s+now|no\s+survey|no\s+verification|instant\s+delivery|working\s+2024|working\s+2025|legit\s+method)""", RegexOption.IGNORE_CASE),
            SpamSeverity.HIGH,
            "Gaming scam"
        ),
        
        // Sophisticated Unicode and homograph attacks
        SpamPattern(
            Regex("""[а-я]{3,}.*[a-z]{3,}|[a-z]{3,}.*[а-я]{3,}"""), // Mixing Cyrillic and Latin
            SpamSeverity.MEDIUM,
            "Homograph attack detected"
        )
    )
    
    private val _bannedUsers = MutableStateFlow<Set<String>>(emptySet())
    val bannedUsers: StateFlow<Set<String>> = _bannedUsers.asStateFlow()
    
    init {
        loadUserRecords()
        // Initialize cleanup coroutine
        scope.launch {
            kotlinx.coroutines.delay(60000) // Wait 1 minute after startup
            antiBypassStorage.cleanupExpiredRecords()
        }
    }
    
    /**
     * Advanced spam detection with user-friendly thresholds and bypass detection
     * Returns null if message is clean, or SpamWarning if spam detected
     */
    fun checkForSpam(peerID: String, message: String): SpamWarning? {
        val currentTime = Date()
        
        // Enhanced ban check with bypass detection
        scope.launch {
            val banCheck = antiBypassStorage.isBannedWithBypassCheck(peerID)
            if (banCheck.isBanned) {
                if (banCheck.bypassDetected) {
                    Log.w(TAG, "Ban bypass detected for $peerID - ${banCheck.banType}")
                    // Apply stricter ban for bypass attempt
                    antiBypassStorage.applyPersistentBan(
                        peerID, 
                        "Bypass attempt: ${banCheck.reason}", 
                        48, // 48 hour ban for bypass
                        3, // High severity
                        true // Enable hardware ban
                    )
                }
            }
        }
        
        if (isUserBanned(peerID)) {
            return null
        }
        
        // Initialize user tracking if new
        if (!userJoinTimes.containsKey(peerID)) {
            userJoinTimes[peerID] = currentTime.time
            userTrustScores[peerID] = 0.5 // Start with neutral trust
        }
        
        var totalSpamScore = 0.0
        var detectedReasons = mutableListOf<String>()
        val trustScore = getUserTrustScore(peerID)
        
        // 1. Enhanced rate limiting based on user trust and age
        val rateLimitViolation = checkEnhancedRateLimit(peerID, currentTime)
        if (rateLimitViolation.first) {
            val penalty = if (trustScore > 0.7) 20.0 else if (trustScore < 0.3) 40.0 else 30.0
            totalSpamScore += penalty
            detectedReasons.add(rateLimitViolation.second)
        }
        
        // 2. Rapid-fire message detection
        val rapidFireViolation = checkRapidFire(peerID, currentTime)
        if (rapidFireViolation) {
            totalSpamScore += 25.0
            detectedReasons.add("Rapid-fire messaging detected")
        }
        
        // 3. Smart duplicate message detection
        val duplicateScore = checkSmartDuplicates(peerID, message)
        if (duplicateScore > 0) {
            totalSpamScore += duplicateScore
            detectedReasons.add("Duplicate/similar message spam")
        }
        
        // 4. Context-aware caps detection
        if (message.length >= MIN_MESSAGE_LENGTH_FOR_CAPS_CHECK) {
            val capsScore = checkSmartCaps(message)
            if (capsScore > 0) {
                totalSpamScore += capsScore
                detectedReasons.add("Excessive capitals in context")
            }
        }
        
        // 5. Advanced pattern matching with context
        val patternScore = checkAdvancedPatterns(message, peerID)
        if (patternScore > 0) {
            totalSpamScore += patternScore
            detectedReasons.add("Suspicious content patterns detected")
        }
        
        // 6. Message quality analysis
        val qualityScore = analyzeMessageQuality(message)
        if (qualityScore < 0.3 && message.length > 20) { // Poor quality long message
            totalSpamScore += 15.0
            detectedReasons.add("Low message quality")
        }
        
        // 7. Enhanced behavioral analysis with AI-like detection
        val behaviorScore = analyzeBehavioralPattern(peerID, message)
        totalSpamScore += behaviorScore
        if (behaviorScore > 10) {
            detectedReasons.add("Suspicious behavioral patterns")
        }
        
        // Adjust score based on user trust (trusted users get benefit of doubt)
        val adjustedScore = if (trustScore > 0.8) {
            totalSpamScore * 0.6 // 40% reduction for highly trusted users
        } else if (trustScore < 0.3) {
            totalSpamScore * 1.3 // 30% increase for low-trust users
        } else {
            totalSpamScore
        }
        
        // Determine spam severity with user-friendly thresholds
        val severity = when {
            adjustedScore >= AUTO_BAN_SCORE -> SpamSeverity.CRITICAL
            adjustedScore >= SUSPICIOUS_PATTERN_SCORE -> SpamSeverity.HIGH
            adjustedScore >= 25.0 -> SpamSeverity.MEDIUM
            adjustedScore >= 15.0 -> SpamSeverity.LOW
            else -> return null // Not spam - allow message
        }
        
        // Update user trust score based on spam detection
        updateUserTrustScore(peerID, severity)
        
        // Create warning with enhanced information
        val warning = SpamWarning(
            peerID = peerID,
            timestamp = currentTime,
            reason = detectedReasons.joinToString(", "),
            severity = severity
        )
        
        // Process the warning with graduated response
        processEnhancedSpamWarning(warning, adjustedScore.toInt())
        
        Log.i(TAG, "Spam detected from $peerID: ${warning.reason} (Severity: $severity, Score: %.1f, Trust: %.2f)".format(adjustedScore, trustScore))
        
        return warning
    }
    
    /**
     * Enhanced spam warning processing with graduated penalties
     * Uses sophisticated escalation that's fair to normal users
     */
    private fun processEnhancedSpamWarning(warning: SpamWarning, spamScore: Int) {
        val userRecord = userRecords[warning.peerID] ?: UserSpamRecord(warning.peerID)
        val trustScore = getUserTrustScore(warning.peerID)
        
        // Add warning to history
        recentWarnings.add(warning)
        
        // Calculate escalation based on severity, history, and trust
        val severityPoints = when (warning.severity) {
            SpamSeverity.LOW -> 1
            SpamSeverity.MEDIUM -> 2
            SpamSeverity.HIGH -> 4
            SpamSeverity.CRITICAL -> 8
        }
        
        val newWarningCount = userRecord.warningCount + 1
        val newSpamScore = userRecord.totalSpamScore + severityPoints
        
        val updatedRecord = userRecord.copy(
            warningCount = newWarningCount,
            lastWarning = warning.timestamp,
            totalSpamScore = newSpamScore
        )
        
        // Graduated escalation system
        when {
            // Immediate ban for critical threats
            warning.severity == SpamSeverity.CRITICAL && spamScore >= AUTO_BAN_SCORE -> {
                scope.launch {
                    antiBypassStorage.applyPersistentBan(
                        warning.peerID,
                        "Critical spam: ${warning.reason}",
                        LONG_BAN_DURATION_HOURS.toLong(),
                        3,
                        spamScore >= 100 // Hardware ban for extreme cases
                    )
                }
                banUser(warning.peerID, "Critical spam: ${warning.reason}")
                Log.w(TAG, "Critical spam - immediate ban applied to ${warning.peerID}")
            }
            
            // Temporary ban for repeat offenders (graduated)
            newWarningCount >= WARNINGS_BEFORE_TEMP_BAN || newSpamScore >= 12 -> {
                val banDuration = when {
                    newWarningCount >= PERMANENT_BAN_THRESHOLD -> {
                        // Persistent ban for chronic offenders
                        scope.launch {
                            antiBypassStorage.applyPersistentBan(
                                warning.peerID,
                                "Chronic spam violations: ${warning.reason}",
                                LONG_BAN_DURATION_HOURS.toLong() * 2,
                                2,
                                true // Enable hardware ban
                            )
                        }
                        LONG_BAN_DURATION_HOURS
                    }
                    newSpamScore >= 15 -> LONG_BAN_DURATION_HOURS
                    else -> TEMP_BAN_DURATION_HOURS
                }
                
                banUser(warning.peerID, "Repeated violations: ${warning.reason}")
                Log.i(TAG, "Temporary ban (${banDuration}h) applied to ${warning.peerID} after ${newWarningCount} warnings")
            }
            
            // Warning with cooldown for trusted users
            trustScore > 0.7 && warning.severity == SpamSeverity.LOW -> {
                userRecords[warning.peerID] = updatedRecord
                saveUserRecords()
                Log.i(TAG, "Light warning issued to trusted user ${warning.peerID}: ${warning.reason}")
            }
            
            // Standard warning
            else -> {
                userRecords[warning.peerID] = updatedRecord
                saveUserRecords()
                Log.i(TAG, "Warning ${newWarningCount}/$WARNINGS_BEFORE_TEMP_BAN issued to ${warning.peerID}: ${warning.reason} (Score: ${newSpamScore})")
            }
        }
        
        // Clean old warnings (keep only last 150 for analysis)
        if (recentWarnings.size > 150) {
            recentWarnings.removeAt(0)
        }
    }
    
    /**
     * Apply ban with enhanced tracking and anti-bypass measures
     */
    private fun banUser(peerID: String, reason: String, durationHours: Long = TEMP_BAN_DURATION_HOURS.toLong()) {
        val banExpiry = Date(System.currentTimeMillis() + (durationHours * 60 * 60 * 1000))
        
        val bannedRecord = UserSpamRecord(
            peerID = peerID,
            warningCount = 0,
            totalSpamScore = 0,
            isBanned = true,
            banReason = reason,
            banExpiry = banExpiry
        )
        
        userRecords[peerID] = bannedRecord
        
        // Update trust score for banned user
        val currentTrust = getUserTrustScore(peerID)
        userTrustScores[peerID] = (currentTrust * 0.3).coerceAtLeast(0.1) // Significant trust reduction
        
        updateBannedUsersFlow()
        saveUserRecords()
        
        Log.i(TAG, "User $peerID banned for ${durationHours} hours. Reason: $reason")
    }
    
    /**
     * Check if user is currently banned
     */
    fun isUserBanned(peerID: String): Boolean {
        val record = userRecords[peerID] ?: return false
        
        if (!record.isBanned) return false
        
        // Check if ban has expired
        val banExpiry = record.banExpiry
        if (banExpiry != null && Date().after(banExpiry)) {
            // Ban expired, unban user
            unbanUser(peerID)
            return false
        }
        
        return true
    }
    
    /**
     * Unban a user
     */
    fun unbanUser(peerID: String) {
        val record = userRecords[peerID] ?: return
        
        val unbannedRecord = record.copy(
            isBanned = false,
            banReason = null,
            banExpiry = null
        )
        
        userRecords[peerID] = unbannedRecord
        updateBannedUsersFlow()
        saveUserRecords()
        
        Log.i(TAG, "User $peerID unbanned")
    }
    
    /**
     * Get user's spam record
     */
    fun getUserRecord(peerID: String): UserSpamRecord? {
        return userRecords[peerID]
    }
    
    /**
     * Get recent warnings
     */
    fun getRecentWarnings(limit: Int = 20): List<SpamWarning> {
        return recentWarnings.takeLast(limit)
    }
    
    // Enhanced Helper Functions
    
    /**
     * Enhanced rate limiting based on user trust and account age
     */
    private fun checkEnhancedRateLimit(peerID: String, currentTime: Date): Pair<Boolean, String> {
        val timestamps = messageTimestamps.getOrPut(peerID) { mutableListOf() }
        
        // Add current timestamp
        timestamps.add(currentTime)
        
        // Remove timestamps older than 1 minute
        val oneMinuteAgo = Date(currentTime.time - 60000)
        timestamps.removeAll { it.before(oneMinuteAgo) }
        
        // Determine rate limit based on user characteristics
        val trustScore = getUserTrustScore(peerID)
        val accountAge = getAccountAgeHours(peerID)
        
        val rateLimit = when {
            trustScore >= 0.8 -> TRUSTED_USER_RATE_LIMIT // Trusted users get higher limit
            accountAge < 1 -> NEW_USER_RATE_LIMIT // New users get lower limit
            trustScore <= 0.3 -> NEW_USER_RATE_LIMIT // Low-trust users get lower limit
            else -> BASE_MESSAGE_RATE_LIMIT // Normal users
        }
        
        val isViolation = timestamps.size > rateLimit
        val reason = if (isViolation) {
            "Rate limit exceeded: ${timestamps.size}/$rateLimit messages per minute"
        } else {
            ""
        }
        
        return Pair(isViolation, reason)
    }
    
    /**
     * Check for rapid-fire messaging (multiple messages within seconds)
     */
    private fun checkRapidFire(peerID: String, currentTime: Date): Boolean {
        val rapidTimes = rapidFireTracker.getOrPut(peerID) { mutableListOf() }
        
        rapidTimes.add(currentTime.time)
        
        // Remove timestamps older than 10 seconds
        val tenSecondsAgo = currentTime.time - 10000
        rapidTimes.removeAll { it < tenSecondsAgo }
        
        return rapidTimes.size >= RAPID_FIRE_THRESHOLD
    }
    
    /**
     * Smart duplicate detection with similarity analysis
     */
    private fun checkSmartDuplicates(peerID: String, message: String): Double {
        val history = messageHistory.getOrPut(peerID) { mutableListOf() }
        
        var duplicateScore = 0.0
        val normalizedMessage = normalizeMessage(message)
        
        // Exact duplicates
        val exactDuplicates = history.count { it == message }
        if (exactDuplicates >= DUPLICATE_MESSAGE_THRESHOLD) {
            duplicateScore += 30.0
        } else if (exactDuplicates >= 2) {
            duplicateScore += 15.0
        }
        
        // Similar messages (using Levenshtein distance concept)
        val similarMessages = history.count { existingMessage ->
            val normalizedExisting = normalizeMessage(existingMessage)
            calculateSimilarity(normalizedMessage, normalizedExisting) > 0.8
        }
        
        if (similarMessages >= 3) {
            duplicateScore += 20.0
        } else if (similarMessages >= 2) {
            duplicateScore += 10.0
        }
        
        // Add to history
        history.add(message)
        
        // Keep only last 15 messages for analysis
        if (history.size > 15) {
            history.removeAt(0)
        }
        
        return duplicateScore
    }
    
    /**
     * Context-aware caps detection
     */
    private fun checkSmartCaps(message: String): Double {
        val capsPercentage = calculateCapsPercentage(message)
        
        // More lenient for shorter messages and common caps usage
        return when {
            message.length < 20 && capsPercentage < 95 -> 0.0 // Very lenient for short messages
            capsPercentage >= CAPS_PERCENTAGE_THRESHOLD -> {
                val penalty = ((capsPercentage - CAPS_PERCENTAGE_THRESHOLD) / 5.0) * 2.0
                min(penalty, 20.0) // Cap the penalty
            }
            else -> 0.0
        }
    }
    
    /**
     * Advanced pattern matching with context awareness
     */
    private fun checkAdvancedPatterns(message: String, peerID: String): Double {
        var patternScore = 0.0
        val matchedPatterns = mutableListOf<String>()
        
        for (pattern in spamPatterns) {
            if (pattern.pattern.containsMatchIn(message)) {
                val baseScore = when (pattern.severity) {
                    SpamSeverity.LOW -> 8.0
                    SpamSeverity.MEDIUM -> 18.0
                    SpamSeverity.HIGH -> 35.0
                    SpamSeverity.CRITICAL -> 60.0
                }
                
                // Reduce penalty for trusted users on lower severity patterns
                val trustScore = getUserTrustScore(peerID)
                val adjustedScore = if (trustScore > 0.7 && pattern.severity == SpamSeverity.LOW) {
                    baseScore * 0.5
                } else if (trustScore < 0.3) {
                    baseScore * 1.2
                } else {
                    baseScore
                }
                
                patternScore += adjustedScore
                matchedPatterns.add(pattern.description)
            }
        }
        
        return patternScore
    }
    
    /**
     * Analyze message quality to detect low-quality spam
     */
    private fun analyzeMessageQuality(message: String): Double {
        var qualityScore = 1.0
        
        // Length analysis
        if (message.length < 3) qualityScore -= 0.3
        if (message.length > 500) qualityScore -= 0.2 // Very long messages can be suspicious
        
        // Character variety
        val uniqueChars = message.toSet().size
        val totalChars = message.length
        val charVariety = if (totalChars > 0) uniqueChars.toDouble() / totalChars else 0.0
        if (charVariety < 0.3) qualityScore -= 0.3
        
        // Word analysis
        val words = message.split("\\s+".toRegex()).filter { it.length > 1 }
        if (words.isNotEmpty()) {
            val uniqueWords = words.toSet().size
            val wordVariety = uniqueWords.toDouble() / words.size
            if (wordVariety < 0.5) qualityScore -= 0.2
            
            // Average word length
            val avgWordLength = words.sumOf { it.length }.toDouble() / words.size
            if (avgWordLength < 3) qualityScore -= 0.2
        }
        
        // Special character spam
        val specialChars = message.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        val specialRatio = if (message.isNotEmpty()) specialChars.toDouble() / message.length else 0.0
        if (specialRatio > 0.3) qualityScore -= 0.4
        
        return qualityScore.coerceIn(0.0, 1.0)
    }
    
    /**
     * Advanced behavioral pattern analysis for bot/spam detection with AI-like scoring
     */
    private fun analyzeBehavioralPattern(peerID: String, message: String): Double {
        var behaviorScore = 0.0
        val trustScore = getUserTrustScore(peerID)
        val accountAge = getAccountAgeHours(peerID)
        
        // Account age analysis (new accounts are more suspicious)
        if (accountAge < 1) {
            behaviorScore += 5.0
        } else if (accountAge < 24) {
            behaviorScore += 2.0
        }
        
        // Message timing patterns (too regular = bot)
        val timestamps = messageTimestamps[peerID] ?: emptyList()
        if (timestamps.size >= 5) {
            val intervals = timestamps.zipWithNext { a, b -> b.time - a.time }
            val avgInterval = intervals.average()
            val intervalVariance = intervals.map { (it - avgInterval) * (it - avgInterval) }.average()
            
            // Very consistent timing suggests bot behavior
            if (intervalVariance < 1000 && avgInterval < 10000) { // Less than 1s variance, less than 10s average
                behaviorScore += 15.0
            }
        }
        
        // Message length consistency (bots often use similar lengths)
        val recentMessages = messageHistory[peerID] ?: emptyList()
        if (recentMessages.size >= 4) {
            val lengths = recentMessages.map { it.length }
            val avgLength = lengths.average()
            val lengthVariance = lengths.map { (it - avgLength) * (it - avgLength) }.average()
            
            if (lengthVariance < 10 && recentMessages.size >= 6) {
                behaviorScore += 10.0
            }
        }
        
        // Contextual learning - learn user's normal patterns
        val userPatterns = userContextualPatterns.getOrPut(peerID) { mutableSetOf() }
        val messageWords = message.toLowerCase().split("\\s+".toRegex()).filter { it.length > 2 }
        
        // If user consistently uses certain phrases, whitelist them
        val commonPhrases = whitelistedPhrases.getOrPut(peerID) { mutableSetOf() }
        messageWords.forEach { word ->
            userPatterns.add(word)
            if (userPatterns.count { it.contains(word) } >= WHITELIST_LEARNING_THRESHOLD) {
                commonPhrases.add(word)
            }
        }
        
        // Reduce score if message contains user's common phrases
        val whitelistMatches = messageWords.count { word ->
            commonPhrases.any { phrase -> phrase.contains(word) || word.contains(phrase) }
        }
        if (whitelistMatches > 0) {
            behaviorScore -= (whitelistMatches * 2.0)
        }
        
        // Trust decay over time (inactive users lose trust slowly)
        if (trustScore > 0.5) {
            val daysSinceLastMessage = (System.currentTimeMillis() - (timestamps.lastOrNull()?.time ?: 0)) / (24 * 60 * 60 * 1000)
            if (daysSinceLastMessage > 7) {
                userTrustScores[peerID] = (trustScore - (daysSinceLastMessage * TRUST_DECAY_FACTOR)).coerceAtLeast(0.1)
            }
        }
        
        return behaviorScore.coerceAtLeast(0.0)
    }
    
    /**
     * Get user trust score
     */
    private fun getUserTrustScore(peerID: String): Double {
        return userTrustScores.getOrDefault(peerID, 0.5) // Default neutral trust
    }
    
    /**
     * Update user trust score based on behavior
     */
    private fun updateUserTrustScore(peerID: String, spamSeverity: SpamSeverity) {
        val currentScore = getUserTrustScore(peerID)
        val adjustment = when (spamSeverity) {
            SpamSeverity.LOW -> -0.05
            SpamSeverity.MEDIUM -> -0.1
            SpamSeverity.HIGH -> -0.2
            SpamSeverity.CRITICAL -> -0.4
        }
        
        val newScore = (currentScore + adjustment).coerceIn(0.0, 1.0)
        userTrustScores[peerID] = newScore
    }
    
    /**
     * Increase trust score for good behavior (call from external sources)
     */
    fun increaseTrustScore(peerID: String, amount: Double = 0.02) {
        val currentScore = getUserTrustScore(peerID)
        val newScore = (currentScore + amount).coerceIn(0.0, 1.0)
        userTrustScores[peerID] = newScore
    }
    
    /**
     * Get account age in hours
     */
    private fun getAccountAgeHours(peerID: String): Long {
        val joinTime = userJoinTimes[peerID] ?: System.currentTimeMillis()
        return (System.currentTimeMillis() - joinTime) / (60 * 60 * 1000)
    }
    
    /**
     * Normalize message for similarity comparison
     */
    private fun normalizeMessage(message: String): String {
        return message.lowercase()
            .replace("\\s+".toRegex(), " ")
            .replace("[^\\w\\s]".toRegex(), "")
            .trim()
    }
    
    /**
     * Calculate similarity between two messages
     */
    private fun calculateSimilarity(msg1: String, msg2: String): Double {
        if (msg1 == msg2) return 1.0
        if (msg1.isEmpty() || msg2.isEmpty()) return 0.0
        
        val words1 = msg1.split(" ").toSet()
        val words2 = msg2.split(" ").toSet()
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }
    
    // Legacy function - replaced by checkSmartDuplicates
    private fun checkDuplicateMessage(peerID: String, message: String): Int {
        return checkSmartDuplicates(peerID, message).toInt() / 10 // Convert score to count approximation
    }
    
    private fun calculateCapsPercentage(message: String): Double {
        val letters = message.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        
        val capsCount = letters.count { it.isUpperCase() }
        return (capsCount.toDouble() / letters.length) * 100
    }
    
    private fun updateBannedUsersFlow() {
        val banned = userRecords.values.filter { it.isBanned }.map { it.peerID }.toSet()
        _bannedUsers.value = banned
    }
    
    private fun loadUserRecords() {
        // TODO: Load from DataManager persistent storage
        updateBannedUsersFlow()
    }
    
    private fun saveUserRecords() {
        // TODO: Save to DataManager persistent storage
    }
}