package com.renchat.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.NotificationManagerCompat
import com.renchat.android.MainActivity
import com.renchat.android.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced notification manager for direct messages with production-ready features:
 * - Notification grouping per sender
 * - Click handling to open specific DM
 * - App background state awareness
 * - Proper notification management and cleanup
 */
class NotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "NotificationManager"
        private const val CHANNEL_ID = "RenChat_dm_notifications"
        private const val GEOHASH_CHANNEL_ID = "RenChat_geohash_notifications"
        private const val PROXIMITY_CHANNEL_ID = "RenChat_proximity_notifications"
        private const val GROUP_KEY_DM = "RenChat_dm_group"
        private const val GROUP_KEY_GEOHASH = "RenChat_geohash_group"
        private const val NOTIFICATION_REQUEST_CODE = 1000
        private const val GEOHASH_NOTIFICATION_REQUEST_CODE = 2000
        private const val PROXIMITY_NOTIFICATION_REQUEST_CODE = 3000
        private const val SUMMARY_NOTIFICATION_ID = 999
        private const val GEOHASH_SUMMARY_NOTIFICATION_ID = 998
        private const val PROXIMITY_NOTIFICATION_ID = 997
        
        // Intent extras for notification handling
        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
        const val EXTRA_OPEN_GEOHASH_CHAT = "open_geohash_chat"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_SENDER_NICKNAME = "sender_nickname"
        const val EXTRA_GEOHASH = "geohash"
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    // Track pending notifications per sender to enable grouping
    private val pendingNotifications = ConcurrentHashMap<String, MutableList<PendingNotification>>()
    private val pendingGeohashNotifications = ConcurrentHashMap<String, MutableList<GeohashNotification>>()
    
    // Track previously notified nearby users to avoid spam
    private val notifiedNearbyUsers = mutableSetOf<String>()
    private var lastProximityNotificationTime = 0L
    private val PROXIMITY_NOTIFICATION_COOLDOWN = 60000L // 1 minute cooldown
    
    // Track app background state
    @Volatile
    private var isAppInBackground = false
    
    // Track current view state
    @Volatile
    private var currentPrivateChatPeer: String? = null
    
    @Volatile
    private var currentGeohash: String? = null

    data class PendingNotification(
        val senderPeerID: String,
        val senderNickname: String, 
        val messageContent: String,
        val timestamp: Long
    )
    
    data class GeohashNotification(
        val geohash: String,
        val senderNickname: String,
        val messageContent: String,
        val timestamp: Long,
        val isMention: Boolean = false,
        val isFirstMessage: Boolean = false
    )

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Direct Messages"
            val descriptionText = "Notifications for private messages from other users"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                setShowBadge(true)
            }
            systemNotificationManager.createNotificationChannel(channel)
            
            // Create geohash notification channel
            val geohashName = "Geohash Messages"
            val geohashDescription = "Notifications for mentions and messages in geohash channels"
            val geohashChannel = NotificationChannel(GEOHASH_CHANNEL_ID, geohashName, importance).apply {
                description = geohashDescription
                enableVibration(true)
                setShowBadge(true)
            }
            systemNotificationManager.createNotificationChannel(geohashChannel)
            
            // Create proximity notification channel
            val proximityName = "Nearby Users"
            val proximityDescription = "Notifications when new users are detected nearby"
            val proximityImportance = NotificationManager.IMPORTANCE_DEFAULT // Less urgent than messages
            val proximityChannel = NotificationChannel(PROXIMITY_CHANNEL_ID, proximityName, proximityImportance).apply {
                description = proximityDescription
                enableVibration(false) // Less intrusive
                setShowBadge(false)
            }
            systemNotificationManager.createNotificationChannel(proximityChannel)
        }
    }

    /**
     * Update app background state - notifications should only be shown when app is backgrounded
     */
    fun setAppBackgroundState(inBackground: Boolean) {
        isAppInBackground = inBackground
        Log.d(TAG, "App background state changed: $inBackground")
    }

    /**
     * Update current private chat peer - affects notification logic
     */
    fun setCurrentPrivateChatPeer(peerID: String?) {
        currentPrivateChatPeer = peerID
        Log.d(TAG, "Current private chat peer changed: $peerID")
    }

    /**
     * Show a notification for a private message with proper grouping and state awareness
     */
    fun showPrivateMessageNotification(senderPeerID: String, senderNickname: String, messageContent: String) {
        // Only show notifications if app is in background OR user is not viewing this specific chat
        val shouldNotify = isAppInBackground || (!isAppInBackground && currentPrivateChatPeer != senderPeerID)
        
        if (!shouldNotify) {
            Log.d(TAG, "Skipping notification - app in foreground and viewing chat with $senderNickname")
            return
        }

        Log.d(TAG, "Showing notification for message from $senderNickname (peerID: $senderPeerID)")

        val notification = PendingNotification(
            senderPeerID = senderPeerID,
            senderNickname = senderNickname,
            messageContent = messageContent,
            timestamp = System.currentTimeMillis()
        )

        // Add to pending notifications for this sender
        pendingNotifications.computeIfAbsent(senderPeerID) { mutableListOf() }.add(notification)

        // Create or update notification for this sender
        showNotificationForSender(senderPeerID)
        
        // Update summary notification if we have multiple senders
        if (pendingNotifications.size > 1) {
            showSummaryNotification()
        }
    }

    private fun showNotificationForSender(senderPeerID: String) {
        val notifications = pendingNotifications[senderPeerID] ?: return
        if (notifications.isEmpty()) return

        val latestNotification = notifications.last()
        val messageCount = notifications.size

        // Create intent to open the specific private chat
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PRIVATE_CHAT, true)
            putExtra(EXTRA_PEER_ID, senderPeerID)
            putExtra(EXTRA_SENDER_NICKNAME, latestNotification.senderNickname)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create person for better notification styling
        val person = Person.Builder()
            .setName(latestNotification.senderNickname)
            .setKey(senderPeerID)
            .build()

        // Build notification content
        val contentText = if (messageCount == 1) {
            latestNotification.messageContent
        } else {
            "${latestNotification.messageContent} (+${messageCount - 1} more)"
        }

        val contentTitle = if (messageCount == 1) {
            latestNotification.senderNickname
        } else {
            "${latestNotification.senderNickname} ($messageCount messages)"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addPerson(person)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        // Add to notification group if we have multiple senders
        if (pendingNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_DM)
        }

        // Add style for multiple messages
        if (messageCount > 1) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(contentTitle)
            
            // Show last few messages in expanded view
            notifications.takeLast(5).forEach { notif ->
                style.addLine(notif.messageContent)
            }
            
            if (messageCount > 5) {
                style.setSummaryText("and ${messageCount - 5} more")
            }
            
            builder.setStyle(style)
        } else {
            // Single message - use BigTextStyle for long messages
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(latestNotification.messageContent)
            )
        }

        // Use sender peer ID hash as notification ID to group messages from same sender
        val notificationId = senderPeerID.hashCode()
        notificationManager.notify(notificationId, builder.build())
        
        Log.d(TAG, "Displayed notification for $contentTitle with ID $notificationId")
    }

    private fun showSummaryNotification() {
        if (pendingNotifications.isEmpty()) return

        val totalMessages = pendingNotifications.values.sumOf { it.size }
        val senderCount = pendingNotifications.size

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("RenChat")
            .setContentText("$totalMessages messages from $senderCount people")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_DM)
            .setGroupSummary(true)

        // Add inbox style showing recent senders
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle("New Messages")
            
        pendingNotifications.entries.take(5).forEach { (peerID, notifications) ->
            val latestNotif = notifications.last()
            val count = notifications.size
            val line = if (count == 1) {
                "${latestNotif.senderNickname}: ${latestNotif.messageContent}"
            } else {
                "${latestNotif.senderNickname}: $count messages"
            }
            style.addLine(line)
        }
        
        if (pendingNotifications.size > 5) {
            style.setSummaryText("and ${pendingNotifications.size - 5} more conversations")
        }
        
        builder.setStyle(style)

        notificationManager.notify(SUMMARY_NOTIFICATION_ID, builder.build())
        
        Log.d(TAG, "Displayed summary notification for $senderCount senders")
    }

    /**
     * Clear notifications for a specific sender (e.g., when user opens their chat)
     */
    fun clearNotificationsForSender(senderPeerID: String) {
        pendingNotifications.remove(senderPeerID)
        
        // Cancel the individual notification
        val notificationId = senderPeerID.hashCode()
        notificationManager.cancel(notificationId)
        
        // Update or remove summary notification
        if (pendingNotifications.isEmpty()) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else if (pendingNotifications.size == 1) {
            // Only one sender left, remove group summary
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else {
            // Update summary notification
            showSummaryNotification()
        }
        
        Log.d(TAG, "Cleared notifications for sender: $senderPeerID")
    }

    /**
     * Clear all pending notifications
     */
    fun clearAllNotifications() {
        pendingNotifications.clear()
        notificationManager.cancelAll()
        Log.d(TAG, "Cleared all notifications")
    }

    /**
     * Get pending notification count for UI badging
     */
    fun getPendingNotificationCount(): Int {
        return pendingNotifications.values.sumOf { it.size }
    }

    /**
     * Get app background state for reactive read receipts
     */
    fun getAppBackgroundState(): Boolean {
        return isAppInBackground
    }

    /**
     * Get current private chat peer for reactive read receipts
     */
    fun getCurrentPrivateChatPeer(): String? {
        return currentPrivateChatPeer
    }

    /**
     * Get pending notifications for debugging
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("Notification Manager Debug Info:")
            appendLine("App in background: $isAppInBackground")
            appendLine("Current private chat peer: $currentPrivateChatPeer")
            appendLine("Pending notifications: ${pendingNotifications.size} senders")
            pendingNotifications.forEach { (peerID, notifications) ->
                appendLine("  $peerID: ${notifications.size} messages")
            }
        }
    }
    
    /**
     * Show proximity notification when new users are detected nearby
     * Only shows when app is in background and respects cooldown period
     */
    fun showProximityNotification(nearbyUsers: List<String>) {
        // Only show proximity notifications when app is in background
        if (!isAppInBackground) {
            Log.d(TAG, "Skipping proximity notification - app is in foreground")
            return
        }
        
        // Apply cooldown to prevent spam
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProximityNotificationTime < PROXIMITY_NOTIFICATION_COOLDOWN) {
            Log.d(TAG, "Skipping proximity notification - within cooldown period")
            return
        }
        
        // Filter out users we already notified about recently
        val newUsers = nearbyUsers.filter { !notifiedNearbyUsers.contains(it) }
        if (newUsers.isEmpty()) {
            return
        }
        
        // Update tracking
        lastProximityNotificationTime = currentTime
        notifiedNearbyUsers.addAll(newUsers)
        
        // Clear old tracked users periodically (keep last 20)
        if (notifiedNearbyUsers.size > 20) {
            val usersToRemove = notifiedNearbyUsers.take(notifiedNearbyUsers.size - 20)
            notifiedNearbyUsers.removeAll(usersToRemove.toSet())
        }
        
        // Create notification
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            PROXIMITY_NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = when (newUsers.size) {
            1 -> "New user nearby"
            else -> "${newUsers.size} users nearby"
        }
        
        val content = when (newUsers.size) {
            1 -> "${newUsers.first()} is nearby"
            2 -> "${newUsers[0]} and ${newUsers[1]} are nearby"
            else -> "${newUsers[0]}, ${newUsers[1]} and ${newUsers.size - 2} others are nearby"
        }
        
        val notification = NotificationCompat.Builder(context, PROXIMITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
        
        try {
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(PROXIMITY_NOTIFICATION_ID, notification)
                Log.d(TAG, "Showed proximity notification for users: $newUsers")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show proximity notification: $e")
        }
    }
    
    /**
     * Clear proximity notification tracking when app comes to foreground
     */
    fun clearProximityTracking() {
        notifiedNearbyUsers.clear()
        Log.d(TAG, "Cleared proximity tracking")
    }
}
