package com.bitchat.android.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.service.ConversationNotificationReceiver
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced notification manager for direct messages and geohash chats with production-ready features:
 * - Notification grouping per sender/geohash
 * - Click handling to open specific DM or geohash chat
 * - App background state awareness
 * - Support for mention notifications in geohash chats
 * - Support for first message notifications in geohash chats
 * - Proper notification management and cleanup
 */
class NotificationManager(
  private val context: Context,
  private val notificationManager: NotificationManagerCompat
) {

    companion object {
        private const val TAG = "NotificationManager"
        private const val CHANNEL_ID = "bitchat_dm_notifications"
        private const val GEOHASH_CHANNEL_ID = "bitchat_geohash_notifications"
        private const val GROUP_KEY_DM = "bitchat_dm_group"
        private const val GROUP_KEY_GEOHASH = "bitchat_geohash_group"
        private const val NOTIFICATION_REQUEST_CODE = 1000
        private const val GEOHASH_NOTIFICATION_REQUEST_CODE = 2000
        private const val SUMMARY_NOTIFICATION_ID = 999
      private const val GEOHASH_SUMMARY_NOTIFICATION_ID = 998
        private const val MAX_MESSAGES_IN_NOTIFICATION = 25

        // Intent extras for notification handling
        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
        const val EXTRA_OPEN_GEOHASH_CHAT = "open_geohash_chat"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_SENDER_NICKNAME = "sender_nickname"
        const val EXTRA_GEOHASH = "geohash"
        const val ACTION_REPLY_TO_CONVERSATION =
            "com.bitchat.android.action.REPLY_TO_CONVERSATION"
        const val ACTION_MARK_CONVERSATION_READ =
            "com.bitchat.android.action.MARK_CONVERSATION_READ"
        const val KEY_TEXT_REPLY = "conversation_reply_text"

        private val liveManagers: MutableSet<NotificationManager> =
            Collections.newSetFromMap(WeakHashMap<NotificationManager, Boolean>())

        /**
         * Synchronizes notification action receivers with every manager instance in this process.
         * Without this, an old in-memory MessagingStyle history could reappear on the next DM.
         */
        fun acknowledgeConversation(context: Context, conversationID: String) {
            val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
            val managers = synchronized(liveManagers) { liveManagers.toList() }
            managers.forEach { it.clearNotificationsForSender(canonicalID) }
            if (managers.isEmpty()) {
                NotificationManagerCompat.from(context).cancel(canonicalID.hashCode())
            }
        }
    }

    private val systemNotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager
    private val conversationPreferences =
        ConversationListPreferences.getInstance(context.applicationContext)
    
    // Track pending notifications per sender to enable grouping
    private val pendingNotifications = ConcurrentHashMap<String, MutableList<PendingNotification>>()
    private val pendingGeohashNotifications = ConcurrentHashMap<String, MutableList<GeohashNotification>>()

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
        val isMention: Boolean,
        val isFirstMessage: Boolean,
        val locationName: String? = null
    )

    init {
        synchronized(liveManagers) { liveManagers.add(this) }
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // DM notifications channel
            val dmName = context.getString(R.string.notification_channel_direct_messages)
            val dmDescriptionText =
                context.getString(R.string.notification_channel_direct_messages_description)
            val dmImportance = AndroidNotificationManager.IMPORTANCE_HIGH
            val dmChannel = NotificationChannel(CHANNEL_ID, dmName, dmImportance).apply {
                description = dmDescriptionText
                enableVibration(true)
                setShowBadge(true)
            }
            systemNotificationManager.createNotificationChannel(dmChannel)

            // Geohash notifications channel
            val geohashName = context.getString(R.string.notification_channel_geohash)
            val geohashDescriptionText =
                context.getString(R.string.notification_channel_geohash_description)
            val geohashImportance = AndroidNotificationManager.IMPORTANCE_HIGH
            val geohashChannel = NotificationChannel(GEOHASH_CHANNEL_ID, geohashName, geohashImportance).apply {
                description = geohashDescriptionText
                enableVibration(true)
                setShowBadge(true)
            }
            systemNotificationManager.createNotificationChannel(geohashChannel)
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
        currentPrivateChatPeer = peerID?.let { ContactDirectory.canonicalConversationId(it) }
        Log.d(TAG, "Current private chat peer changed: $currentPrivateChatPeer")
    }

    /**
     * Update current geohash - affects notification logic for geohash chats
     */
    fun setCurrentGeohash(geohash: String?) {
        currentGeohash = geohash
        Log.d(TAG, "Current geohash changed")
    }

    /**
     * Show a notification for a private message with proper grouping and state awareness
     */
    fun showPrivateMessageNotification(senderPeerID: String, senderNickname: String, messageContent: String) {
        val conversationID = ContactDirectory.canonicalConversationId(senderPeerID)
        if (conversationPreferences.isMuted(conversationID)) {
            Log.d(TAG, "Skipping muted conversation notification")
            return
        }
        // Only show notifications if app is in background OR user is not viewing this specific chat
        val shouldNotify = isAppInBackground ||
            (!isAppInBackground && currentPrivateChatPeer != conversationID)
        
        if (!shouldNotify) {
            Log.d(TAG, "Skipping notification - app in foreground and viewing chat with $senderNickname")
            return
        }

        Log.d(TAG, "Showing notification for message from $senderNickname (conversationID: $conversationID)")

        val notification = PendingNotification(
            senderPeerID = conversationID,
            senderNickname = senderNickname,
            messageContent = messageContent,
            timestamp = System.currentTimeMillis()
        )

        // Add to pending notifications for this sender
        pendingNotifications.computeIfAbsent(conversationID) { mutableListOf() }.add(notification)

        // Create or update notification for this sender
        showNotificationForSender(conversationID)
        
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
        val shortcutID = conversationShortcutID(senderPeerID)
        publishConversationShortcut(
            shortcutID = shortcutID,
            person = person,
            contentIntent = intent
        )

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
            .setShortcutId(shortcutID)
            .setLocusId(LocusIdCompat(shortcutID))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        val markReadIntent = Intent(context, ConversationNotificationReceiver::class.java).apply {
            action = ACTION_MARK_CONVERSATION_READ
            putExtra(EXTRA_PEER_ID, senderPeerID)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode() + 1,
            markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyIntent = Intent(context, ConversationNotificationReceiver::class.java).apply {
            action = ACTION_REPLY_TO_CONVERSATION
            putExtra(EXTRA_PEER_ID, senderPeerID)
            putExtra(EXTRA_SENDER_NICKNAME, latestNotification.senderNickname)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode() + 2,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notification_reply))
            .build()
        builder
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_mark_read),
                    markReadPendingIntent
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_reply),
                    replyPendingIntent
                )
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .build()
            )
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notification_private_message))
                    .setContentText(context.getString(R.string.notification_content_hidden))
                    .build()
            )

        // Add to notification group if we have multiple senders
        if (pendingNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_DM)
        }

        val self = Person.Builder()
            .setName(context.getString(R.string.you))
            .setKey("bitchat-self")
            .build()
        val messagingStyle = NotificationCompat.MessagingStyle(self)
            .setGroupConversation(false)
        notifications.takeLast(MAX_MESSAGES_IN_NOTIFICATION).forEach { notification ->
            messagingStyle.addMessage(
                notification.messageContent,
                notification.timestamp,
                person
            )
        }
        builder.setStyle(messagingStyle)

        // Use sender peer ID hash as notification ID to group messages from same sender
        val notificationId = senderPeerID.hashCode()
        notifySafely(notificationId, builder.build())

        Log.d(TAG, "Displayed notification for $contentTitle with ID $notificationId")
    }

    private fun conversationShortcutID(conversationID: String): String =
        "dm_" + java.util.UUID.nameUUIDFromBytes(
            conversationID.lowercase().toByteArray(Charsets.UTF_8)
        ).toString()

    private fun publishConversationShortcut(
        shortcutID: String,
        person: Person,
        contentIntent: Intent
    ) {
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutID)
            .setShortLabel(person.name?.toString()?.take(40).orEmpty())
            .setLongLived(true)
            .setPerson(person)
            .setLocusId(LocusIdCompat(shortcutID))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
            .setIntent(Intent(contentIntent).apply { action = Intent.ACTION_VIEW })
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun showVerificationNotification(title: String, body: String, peerID: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (peerID != null) {
                putExtra(EXTRA_OPEN_PRIVATE_CHAT, true)
                putExtra(EXTRA_PEER_ID, peerID)
                putExtra(EXTRA_SENDER_NICKNAME, body)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())

        notifySafely(
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            builder.build()
        )
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
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_messages_from_people, totalMessages, senderCount))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_DM)
            .setGroupSummary(true)

        // Add inbox style showing recent senders
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.notification_new_location_messages))
            
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
            style.setSummaryText(context.getString(R.string.notification_more_conversations, pendingNotifications.size - 5))
        }
        
        builder.setStyle(style)

        notifySafely(SUMMARY_NOTIFICATION_ID, builder.build())

        Log.d(TAG, "Displayed summary notification for $senderCount senders")
    }

    /**
     * Clear notifications for a specific sender (e.g., when user opens their chat)
     */
    fun clearNotificationsForSender(senderPeerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(senderPeerID)
        val matchingKeys = pendingNotifications.keys.filter { key ->
            ContactDirectory.canonicalConversationId(key) == conversationID
        }
        matchingKeys.forEach { key ->
            pendingNotifications.remove(key)
            notificationManager.cancel(key.hashCode())
        }
        notificationManager.cancel(conversationID.hashCode())

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
        
        Log.d(TAG, "Cleared notifications for conversation: $conversationID")
    }

    fun removeConversationShortcut(conversationID: String) {
        val shortcutIDs = listOf(conversationShortcutID(conversationID))
        ShortcutManagerCompat.removeDynamicShortcuts(context, shortcutIDs)
        ShortcutManagerCompat.removeLongLivedShortcuts(
            context,
            shortcutIDs
        )
    }

    /**
     * Show a notification for a geohash message with mention or first message
     */
    fun showGeohashNotification(
        geohash: String,
        senderNickname: String,
        messageContent: String,
        isMention: Boolean = false,
        isFirstMessage: Boolean = false,
        locationName: String? = null
    ) {
        // Only show notifications if app is in background OR user is not viewing this specific geohash
        val shouldNotify = isAppInBackground || (!isAppInBackground && currentGeohash != geohash)

        if (!shouldNotify) {
            Log.d(TAG, "Skipping geohash notification while viewing the channel")
            return
        }

        Log.d(TAG, "Showing geohash notification (mention: $isMention, first: $isFirstMessage)")

        val notification = GeohashNotification(
            geohash = geohash,
            senderNickname = senderNickname,
            messageContent = messageContent,
            timestamp = System.currentTimeMillis(),
            isMention = isMention,
            isFirstMessage = isFirstMessage,
            locationName = locationName
        )

        // Add to pending notifications for this geohash
        pendingGeohashNotifications.computeIfAbsent(geohash) { mutableListOf() }.add(notification)

        // Create or update notification for this geohash
        showNotificationForGeohash(geohash)

        // Update summary notification if we have multiple geohashes
        if (pendingGeohashNotifications.size > 1) {
            showGeohashSummaryNotification()
        }
    }

    private fun showNotificationForGeohash(geohash: String) {
        val notifications = pendingGeohashNotifications[geohash] ?: return
        if (notifications.isEmpty()) return

        val latestNotification = notifications.last()
        val messageCount = notifications.size
        val mentionCount = notifications.count { it.isMention }
        val firstMessageCount = notifications.count { it.isFirstMessage }

        // Create intent to open the specific geohash chat
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_GEOHASH_CHAT, true)
            putExtra(EXTRA_GEOHASH, geohash)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            GEOHASH_NOTIFICATION_REQUEST_CODE + geohash.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification content with location name if available
        val geohashDisplay = latestNotification.locationName?.let { "$it (#$geohash)" } ?: "#$geohash"
        val contentTitle = when {
            mentionCount > 0 && firstMessageCount > 0 && messageCount > 1 -> context.getString(R.string.notification_mentions_in_more, geohashDisplay, messageCount - 1)
            mentionCount > 0 -> if (mentionCount == 1) context.getString(R.string.notification_mentions_in, geohashDisplay) else context.getString(R.string.notification_mentions_in_plural, mentionCount, geohashDisplay)
            firstMessageCount > 0 -> context.getString(R.string.notification_new_activity_in, geohashDisplay)
            else -> context.getString(R.string.notification_messages_in, geohashDisplay)
        }

        val contentText = when {
            latestNotification.isMention -> "${latestNotification.senderNickname}: ${latestNotification.messageContent}"
            latestNotification.isFirstMessage -> context.getString(R.string.notification_joined_conversation, latestNotification.senderNickname)
            else -> "${latestNotification.senderNickname}: ${latestNotification.messageContent}"
        }

        val builder = NotificationCompat.Builder(context, GEOHASH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (latestNotification.isMention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        // Add to notification group if we have multiple geohashes
        if (pendingGeohashNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_GEOHASH)
        }

        // Add style for multiple messages
        if (messageCount > 1) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(contentTitle)

            // Show last few messages in expanded view
            notifications.takeLast(5).forEach { notif ->
                val prefix = when {
                    notif.isMention -> "💬 "
                    notif.isFirstMessage -> "👋 "
                    else -> ""
                }
                style.addLine("$prefix${notif.senderNickname}: ${notif.messageContent}")
            }

            if (messageCount > 5) {
                val extra = messageCount - 5
                style.setSummaryText(context.resources.getQuantityString(R.plurals.notification_and_more, extra, extra))
            }

            builder.setStyle(style)
        } else {
            // Single message - use BigTextStyle for long messages
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
            )
        }

        // Use geohash hash as notification ID to group messages from same geohash
        val notificationId = 3000 + geohash.hashCode()
        notifySafely(notificationId, builder.build())

        Log.d(TAG, "Displayed geohash notification for $contentTitle with ID $notificationId")
    }

    private fun showGeohashSummaryNotification() {
        if (pendingGeohashNotifications.isEmpty()) return

        val totalMessages = pendingGeohashNotifications.values.sumOf { it.size }
        val geohashCount = pendingGeohashNotifications.size
        val totalMentions = pendingGeohashNotifications.values.sumOf { notifications ->
            notifications.count { it.isMention }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            GEOHASH_NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentTitle = if (totalMentions > 0) {
            context.getString(R.string.notification_geohash_summary_title_mentions, totalMentions)
        } else {
            context.getString(R.string.notification_geohash_summary_title)
        }

        val contentText = context.getString(R.string.notification_geohash_summary_text, totalMessages, geohashCount)

        val builder = NotificationCompat.Builder(context, GEOHASH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_GEOHASH)
            .setGroupSummary(true)

        // Add inbox style showing recent geohashes
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.notification_new_messages))

        pendingGeohashNotifications.entries.take(5).forEach { (geohash, notifications) ->
            val mentionCount = notifications.count { it.isMention }
            val messageCount = notifications.size
            val latestNotification = notifications.last()
            val geohashDisplay = latestNotification.locationName?.let { "$it (#$geohash)" } ?: "#$geohash"
            val line = when {
                mentionCount > 0 -> "$geohashDisplay: $mentionCount mentions (+${messageCount - mentionCount} more)"
                messageCount == 1 -> "$geohashDisplay: 1 message"
                else -> "$geohashDisplay: $messageCount messages"
            }
            style.addLine(line)
        }

        if (pendingGeohashNotifications.size > 5) {
            style.setSummaryText(context.getString(R.string.notification_more_locations, pendingGeohashNotifications.size - 5))
        }

        builder.setStyle(style)

        notifySafely(GEOHASH_SUMMARY_NOTIFICATION_ID, builder.build())

        Log.d(TAG, "Displayed geohash summary notification for $geohashCount locations")
    }

    /**
     * Clear notifications for a specific geohash (e.g., when user opens that chat)
     */
    fun clearNotificationsForGeohash(geohash: String) {
        pendingGeohashNotifications.remove(geohash)

        // Cancel the individual notification
        val notificationId = 3000 + geohash.hashCode()
        notificationManager.cancel(notificationId)

        // Update or remove summary notification
        if (pendingGeohashNotifications.isEmpty()) {
            notificationManager.cancel(GEOHASH_SUMMARY_NOTIFICATION_ID)
        } else if (pendingGeohashNotifications.size == 1) {
            // Only one geohash left, remove group summary
            notificationManager.cancel(GEOHASH_SUMMARY_NOTIFICATION_ID)
        } else {
            // Update summary notification
            showGeohashSummaryNotification()
        }

        Log.d(TAG, "Cleared notifications for geohash")
    }

    /**
     * Show a notification for a mesh mention (@username format)
     */
    fun showMeshMentionNotification(
        senderNickname: String,
        messageContent: String,
        senderPeerID: String? = null
    ) {
        // Only show notifications if app is in background OR user is not viewing mesh chat
        // User is viewing mesh chat when: not in private chat AND not in geohash chat
        val isViewingMeshChat = currentPrivateChatPeer == null && currentGeohash == null
        val shouldNotify = isAppInBackground || (!isAppInBackground && !isViewingMeshChat)

        if (!shouldNotify) {
            Log.d(TAG, "Skipping mesh mention notification - app in foreground and viewing mesh chat")
            return
        }

        Log.d(TAG, "Showing mesh mention notification from $senderNickname")

        // Use a special key for mesh mentions to group them together
        val meshMentionKey = "mesh_mentions"
        val notification = PendingNotification(
            senderPeerID = senderPeerID ?: meshMentionKey,
            senderNickname = senderNickname,
            messageContent = messageContent,
            timestamp = System.currentTimeMillis()
        )

        // Add to pending notifications for mesh mentions
        pendingNotifications.computeIfAbsent(meshMentionKey) { mutableListOf() }.add(notification)

        // Create or update notification for mesh mentions
        showNotificationForMeshMentions()

        // Update summary notification if we have multiple senders
        if (pendingNotifications.size > 1) {
            showSummaryNotification()
        }
    }

    private fun showNotificationForMeshMentions() {
        val notifications = pendingNotifications["mesh_mentions"] ?: return
        if (notifications.isEmpty()) return

        val latestNotification = notifications.last()
        val messageCount = notifications.size

        // Create intent to open the mesh chat (no specific peer, just main chat)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // No specific chat to open, just bring the app to foreground
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE + "mesh_mentions".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification content
        val contentTitle = if (messageCount == 1) {
            context.getString(R.string.notification_mesh_mention_title_singular)
        } else {
            context.getString(R.string.notification_mesh_mention_title_plural, messageCount)
        }

        val contentText = "${latestNotification.senderNickname}: ${latestNotification.messageContent}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
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
                style.addLine("${notif.senderNickname}: ${notif.messageContent}")
            }

            if (messageCount > 5) {
                val extra = messageCount - 5
                style.setSummaryText(context.resources.getQuantityString(R.plurals.notification_and_more, extra, extra))
            }

            builder.setStyle(style)
        } else {
            // Single message - use BigTextStyle for long messages
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
            )
        }

        // Use a special notification ID for mesh mentions
        val notificationId = 4000 // Different from DM and geohash IDs
        notifySafely(notificationId, builder.build())

        Log.d(TAG, "Displayed mesh mention notification: $contentTitle")
    }

    /**
     * Clear mesh mention notifications (when user opens mesh chat)
     */
    fun clearMeshMentionNotifications() {
        pendingNotifications.remove("mesh_mentions")

        // Cancel the mesh mention notification
        val notificationId = 4000
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

        Log.d(TAG, "Cleared mesh mention notifications")
    }

    /**
     * Clear all pending notifications
     */
    fun clearAllNotifications(removeConversationShortcuts: Boolean = false) {
        pendingNotifications.clear()
        notificationManager.cancelAll()
        pendingGeohashNotifications.clear()
        if (removeConversationShortcuts) {
            val shortcutIDs = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            if (shortcutIDs.isNotEmpty()) {
                ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcutIDs)
            }
        }
        Log.d(TAG, "Cleared all notifications")
    }

    private fun notifySafely(notificationID: Int, notification: android.app.Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            notificationManager.notify(notificationID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission was revoked: ${error.message}")
        }
    }

    /**
     * Get pending notification count for UI badging
     */
    fun getPendingNotificationCount(): Int {
        return pendingNotifications.values.sumOf { it.size } +
               pendingGeohashNotifications.values.sumOf { it.size }
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
            appendLine("Current geohash: $currentGeohash")
            appendLine("Pending DM notifications: ${pendingNotifications.size} senders")
            pendingNotifications.forEach { (peerID, notifications) ->
                appendLine("  $peerID: ${notifications.size} messages")
            }
            appendLine("Pending geohash notifications: ${pendingGeohashNotifications.size} geohashes")
            pendingGeohashNotifications.forEach { (geohash, notifications) ->
                val mentions = notifications.count { it.isMention }
                val firstMessages = notifications.count { it.isFirstMessage }
                appendLine("  #$geohash: ${notifications.size} messages ($mentions mentions, $firstMessages first messages)")
            }
        }
    }
}
