package com.bitchat.android.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Binder
import android.os.IBinder
import android.os.Vibrator
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.ui.theme.DarkColorScheme
import com.bitchat.android.ui.theme.LightColorScheme
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// Data class to hold combined peer information for the UI
data class PeerInfo(val id: String, val nickname: String, val proximity: Int)

/**
 * A foreground service that provides a rich, interactive, and theme-consistent notification
 * in the style of a monochrome IRC/terminal client. It displays nearby peers with proximity
 * and a live log of recent messages by acting as a delegate for BluetoothMeshService.
 */
class ForegroundService : Service(), BluetoothMeshDelegate {

    private val binder = LocalBinder()
    private var meshService: BluetoothMeshService? = null
    private lateinit var notificationManager: NotificationManager
    private var serviceListener: ServiceListener? = null

    // --- Live State for Notification UI ---
    private var activePeers = listOf<PeerInfo>()
    private var unreadMessagesCount = 0
    private var recentMessages = mutableListOf<BitchatMessage>()
    private val knownPeerIds = HashSet<String>() // Used to detect new peers

    // Scheduler for periodic UI refreshes (e.g., timestamps)
    private lateinit var uiUpdateScheduler: ScheduledExecutorService

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_CHANNEL_ID = "bitchat_foreground_service"
        const val ACTION_STOP_SERVICE = "com.bitchat.android.ACTION_STOP_SERVICE"
        const val ACTION_MUTE = "com.bitchat.android.ACTION_MUTE"

        @Volatile
        var isServiceRunning = false
            private set
    }

    // --- Service Lifecycle & Setup ---

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Initialize the mesh service and set this class as its delegate
        if (meshService == null) {
            meshService = BluetoothMeshService(this).apply {
                delegate = this@ForegroundService
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_STOP_SERVICE)
            addAction(ACTION_MUTE)
        }
        // For Android 14+, must specify receiver exportability
        ContextCompat.registerReceiver(this, notificationActionReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(false)) // Initial notification is silent
        startUiUpdater()
        meshService?.startServices()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        uiUpdateScheduler.shutdown()
        unregisterReceiver(notificationActionReceiver)
        meshService?.stopServices()
        meshService = null
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun getMeshService(): BluetoothMeshService? {
        return meshService
    }

    // --- BluetoothMeshDelegate Implementation ---

    override fun didReceiveMessage(message: BitchatMessage) {
        Log.d(TAG, "didReceiveMessage: '${message.content}' from ${message.sender}")
        // Add message to the log and trim if it gets too long
        recentMessages.add(0, message)
        if (recentMessages.size > 10) {
            recentMessages = recentMessages.take(10).toMutableList()
        }
        updateNotification(false) // New messages don't need to alert
    }

    override fun didUpdatePeerList(peers: List<String>) {
        updateNotification(false)
    }

    override fun didConnectToPeer(peerID: String) {
        // Check if this is a genuinely new peer
        if (knownPeerIds.add(peerID)) {
            Log.i(TAG, "New peer connected: $peerID. Triggering alert.")
            // Trigger an alerting notification for the new peer
            updateNotification(true)
        } else {
            // Peer reconnected, just do a silent update
            updateNotification(false)
        }
    }

    override fun didDisconnectFromPeer(peerID: String) {
        knownPeerIds.remove(peerID)
        updateNotification(false)
    }

    // Other delegate methods can trigger a UI update if needed
    override fun didReceiveDeliveryAck(ack: DeliveryAck) { /* Can update UI later */ }
    override fun didReceiveReadReceipt(receipt: ReadReceipt) { /* Can update UI later */ }
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) { /* Can update UI later */ }
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
    override fun getNickname(): String? = "bitchat_user" // Provide a default or fetch from settings
    override fun isFavorite(peerID: String): Boolean = false

    // --- Notification Building & Logic ---

    private fun updateNotification(alert: Boolean) {
        // Fetch real-time data from the mesh service
        val nicknames = meshService?.getPeerNicknames() ?: emptyMap()
        val rssiValues = meshService?.getPeerRSSI() ?: emptyMap()

        // Combine the data into a list of PeerInfo objects for the UI
        activePeers = nicknames.map { (peerId, nickname) ->
            val rssi = rssiValues[peerId] ?: -100 // Default to a weak signal
            PeerInfo(
                id = peerId,
                nickname = nickname,
                proximity = getProximityFromRssi(rssi)
            )
        }.sortedByDescending { it.proximity } // Sort by strongest signal first

        // Update the notification with the new data
        notificationManager.notify(NOTIFICATION_ID, buildNotification(alert))
    }

    private fun buildNotification(alert: Boolean): Notification {
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = if (isDarkTheme) DarkColorScheme else LightColorScheme

        val builder = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(colors.primary.toArgb())
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(createCollapsedRemoteViews())
            .setCustomBigContentView(createExpandedRemoteViews())
            .setContentIntent(createMainPendingIntent())
            .setOngoing(true)

        if (alert) {
            // Make this specific update alert the user
            builder.setOnlyAlertOnce(false)
            builder.setDefaults(Notification.DEFAULT_ALL)
        } else {
            // Subsequent updates should be silent
            builder.setOnlyAlertOnce(true)
        }

        return builder.build()
    }

    private fun createCollapsedRemoteViews(): RemoteViews {
        val stopPendingIntent = createActionPendingIntent(ACTION_STOP_SERVICE)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = if (isDarkTheme) DarkColorScheme else LightColorScheme
        val dimColor = if (isDarkTheme) Color(0xB3FFFFFF).toArgb() else colors.onSurface.toArgb()

        return RemoteViews(packageName, R.layout.notification_terminal_collapsed).apply {
            setTextViewText(R.id.notification_info, "peers: ${activePeers.size} | unread: $unreadMessagesCount")
            setTextColor(R.id.notification_cursor, colors.primary.toArgb())
            setTextColor(R.id.notification_title, colors.primary.toArgb())
            setTextColor(R.id.notification_info, dimColor)
            setTextColor(R.id.notification_action_stop, dimColor)
            setOnClickPendingIntent(R.id.notification_action_stop, stopPendingIntent)
        }
    }

    private fun createExpandedRemoteViews(): RemoteViews {
        val stopPendingIntent = createActionPendingIntent(ACTION_STOP_SERVICE)
        val mutePendingIntent = createActionPendingIntent(ACTION_MUTE)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = if (isDarkTheme) DarkColorScheme else LightColorScheme

        val primaryColor = colors.primary.toArgb()
        val dimColor = if (isDarkTheme) Color(0xB3FFFFFF).toArgb() else colors.onSurface.toArgb()
        val veryDimColor = if (isDarkTheme) Color(0x80FFFFFF).toArgb() else Color(0x99000000).toArgb()
        val dividerColor = if (isDarkTheme) Color(0x4039FF14).toArgb() else Color(0x40000000).toArgb()
        val peerProximityColor = if (isDarkTheme) Color(0xFF8AFF8A).toArgb() else Color(0xFF006600).toArgb()

        return RemoteViews(packageName, R.layout.notification_terminal_expanded).apply {
            // --- Set Themed Colors for static elements ---
            setTextColor(R.id.notification_title_expanded, primaryColor)
            setTextColor(R.id.peer_list_header, veryDimColor)
            setTextColor(R.id.log_header, veryDimColor)
            setTextColor(R.id.notification_divider, dividerColor)
            setTextColor(R.id.notification_action_mute, dimColor)
            setTextColor(R.id.notification_action_stop_expanded, dimColor)

            // --- Populate Peer List from live data ---
            removeAllViews(R.id.notification_peer_list)
            activePeers.take(5).forEach { peer -> // Show top 5 peers
                val peerView = RemoteViews(packageName, R.layout.notification_peer_item_terminal).apply {
                    setTextViewText(R.id.peer_proximity_bar, getProximityBar(peer.proximity))
                    setTextColor(R.id.peer_proximity_bar, peerProximityColor)
                    setTextViewText(R.id.peer_name, peer.nickname)
                    setTextColor(R.id.peer_name, dimColor)
                }
                addView(R.id.notification_peer_list, peerView)
            }

            // --- Populate Message Log from live data ---
            removeAllViews(R.id.notification_message_log)
            recentMessages.take(4).forEach { message ->
                try {
                    val lineView = RemoteViews(packageName, R.layout.notification_line_item).apply {
                        // Defensive coding: handle potential nulls to prevent crashes
                        val sender = message.sender?.take(8) ?: "unknown"
                        val content = message.content ?: "[empty message]"
                        val formattedMessage = "<$sender> $content"
                        setTextViewText(R.id.line_item_text, formattedMessage)
                        setTextColor(R.id.line_item_text, dimColor)
                    }
                    addView(R.id.notification_message_log, lineView)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create message view for log", e)
                }
            }

            // --- Set Actions ---
            setOnClickPendingIntent(R.id.notification_action_mute, mutePendingIntent)
            setOnClickPendingIntent(R.id.notification_action_stop_expanded, stopPendingIntent)
        }
    }

    // --- Helper Functions ---

    private fun getProximityFromRssi(rssi: Int): Int {
        return when {
            rssi > -60 -> 4 // Excellent
            rssi > -70 -> 3 // Good
            rssi > -80 -> 2 // Fair
            rssi > -95 -> 1 // Weak
            else -> 0       // Very weak / No signal
        }
    }

    private fun getProximityBar(proximity: Int): String {
        val filledChar = "▆"
        val emptyChar = " "
        return "[${filledChar.repeat(proximity)}${emptyChar.repeat(4 - proximity)}]"
    }

    private fun startUiUpdater() {
        if (::uiUpdateScheduler.isInitialized && !uiUpdateScheduler.isShutdown) return
        uiUpdateScheduler = Executors.newSingleThreadScheduledExecutor()
        uiUpdateScheduler.scheduleWithFixedDelay({
            updateNotification(false)
        }, 5, 5, TimeUnit.SECONDS)
    }

    // --- Boilerplate (Intents, Binder, etc.) ---

    inner class LocalBinder : Binder() {
        fun getService(): ForegroundService = this@ForegroundService
        fun setServiceListener(listener: ServiceListener?) {
            this@ForegroundService.serviceListener = listener
        }
    }

    interface ServiceListener {
        fun onServiceStopping()
    }

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Notification action received: ${intent?.action}")
            when (intent?.action) {
                ACTION_STOP_SERVICE -> stopForegroundServiceAndApp()
                ACTION_MUTE -> Log.d(TAG, "Mute action tapped")
            }
        }
    }

    private fun stopForegroundServiceAndApp() {
        Log.i(TAG, "Stop action triggered. Stopping service.")
        serviceListener?.onServiceStopping()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        // Use IMPORTANCE_DEFAULT to allow sound/vibration for new peer alerts
        val serviceChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID, "Bitchat Active Service", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Keeps Bitchat connected and shows live status"
            setShowBadge(false)
            // Disable vibration/sound by default; we will trigger it manually
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(serviceChannel)
    }

    private fun createMainPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, notificationActionReceiver::class.java).also { it.action = action }
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
