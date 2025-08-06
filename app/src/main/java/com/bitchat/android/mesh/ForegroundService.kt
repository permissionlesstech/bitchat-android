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
import android.util.Log
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
import kotlin.random.Random

/**
 * A foreground service that provides a standard, live-updating Android notification
 * with peer and message counts.
 */
class ForegroundService : Service(), BluetoothMeshDelegate {

    private val binder = LocalBinder()
    private var meshService: BluetoothMeshService? = null
    private lateinit var notificationManager: NotificationManager
    private var serviceListener: ServiceListener? = null

    // --- Live State for Notification UI ---
    private var activePeers = listOf<PeerInfo>()
    private var unreadMessageCount = 0
    private val knownPeerIds = HashSet<String>() // Used to detect new peers

    // Scheduler for periodic UI refreshes
    private lateinit var uiUpdateScheduler: ScheduledExecutorService

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val NOTIFICATION_ID = 1
        private const val FOREGROUND_CHANNEL_ID = "com.bitchat.android.FOREGROUND_SERVICE"
        private const val MOCK_PEERS_ENABLED = true

        const val ACTION_RESET_UNREAD_COUNT = "com.bitchat.android.ACTION_RESET_UNREAD_COUNT"
        const val ACTION_SHUTDOWN = "com.bitchat.android.ACTION_SHUTDOWN"

        @Volatile
        var isServiceRunning = false
            private set
    }

    // --- Service Lifecycle & Setup ---

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Foreground Service onCreate")
        isServiceRunning = true
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (meshService == null) {
            meshService = BluetoothMeshService(this).apply {
                delegate = this@ForegroundService
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(this, notificationActionReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reset unread count if the user tapped the notification to open the app
        if (intent?.action == ACTION_RESET_UNREAD_COUNT) {
            unreadMessageCount = 0
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(false))
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

    override fun onBind(intent: Intent): IBinder = binder

    fun getMeshService(): BluetoothMeshService? = meshService

    // --- BluetoothMeshDelegate Implementation ---

    override fun didReceiveMessage(message: BitchatMessage) {
        Log.d(TAG, "didReceiveMessage: '${message.content}' from ${message.sender}")
        unreadMessageCount++
        updateNotification(false)
    }

    override fun didUpdatePeerList(peers: List<String>) {
        updateNotification(false)
    }

    override fun didConnectToPeer(peerID: String) {
        if (knownPeerIds.add(peerID)) {
            Log.i(TAG, "New peer connected: $peerID. Triggering alert.")
            updateNotification(true)
        } else {
            updateNotification(false)
        }
    }

    override fun didDisconnectFromPeer(peerID: String) {
        knownPeerIds.remove(peerID)
        updateNotification(false)
    }

    override fun didReceiveDeliveryAck(ack: DeliveryAck) {}
    override fun didReceiveReadReceipt(receipt: ReadReceipt) {}
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {}
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
    override fun getNickname(): String? = "bitchat_user"
    override fun isFavorite(peerID: String): Boolean = false

    // --- Notification Building & Logic ---

    /**
     * Creates a list of mock peers for debugging purposes.
     * Proximity is randomized on each call to simulate changing conditions.
     */
    private fun getMockPeers(): List<PeerInfo> {
        val allMockPeers = listOf(
            PeerInfo(id = "mock_1", nickname = "debugger_dan", proximity = Random.nextInt(0, 5)),
            PeerInfo(id = "mock_2", nickname = "test_tanya", proximity = Random.nextInt(0, 5)),
            PeerInfo(id = "mock_3", nickname = "fake_fred", proximity = Random.nextInt(0, 5)),
            PeerInfo(id = "mock_4", nickname = "staging_sue", proximity = Random.nextInt(0, 5)),
            PeerInfo(id = "mock_5", nickname = "dev_dave", proximity = Random.nextInt(0, 5))
        )

        // Determine a random number of users to show (between 3 and 5)
        val numToShow = Random.nextInt(3, 6) // Generates a number from 3 to 5

        // Shuffle the list and take a random number of peers
        return allMockPeers.shuffled().take(numToShow).sortedByDescending { it.proximity }
    }


    private fun updateNotification(alert: Boolean) {
        // When MOCK_PEERS_ENABLED, override real data with a mock user list.
        // This allows for easy UI testing without requiring physical peer devices.
        if (MOCK_PEERS_ENABLED) {
            activePeers = getMockPeers()
            // Set a mock message count for a more realistic debug notification.
            unreadMessageCount = 7
        } else {
            val nicknames = meshService?.getPeerNicknames() ?: emptyMap()
            val rssiValues = meshService?.getPeerRSSI() ?: emptyMap()

            activePeers = nicknames.map { (peerId, nickname) ->
                val rssi = rssiValues[peerId] ?: -100
                PeerInfo(id = peerId, nickname = nickname, proximity = getProximityFromRssi(rssi))
            }.sortedByDescending { it.proximity }
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(alert))
    }


    /**
     * Builds a standard, live-updating Android notification.
     * Collapsed: Shows peer and unread message counts.
     * Expanded: Shows a list of nearby peers and their proximity.
     */
    private fun buildNotification(alert: Boolean): Notification {
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = if (isDarkTheme) DarkColorScheme else LightColorScheme

        val peerCount = activePeers.size
        val contentText = getString(R.string.notification_scanning)
        val contentTitle = resources.getQuantityString(R.plurals.peers_nearby, peerCount, peerCount)
        val summaryText = resources.getQuantityString(R.plurals.unread_messages, unreadMessageCount, unreadMessageCount)

        // Expanded view style using InboxStyle
        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(contentTitle)
            .setSummaryText(summaryText)

        // Add each peer to the expanded view
        if (activePeers.isNotEmpty()) {
            activePeers.forEach { peer ->
                val proximityBars = "◼".repeat(peer.proximity) + "◻".repeat(4 - peer.proximity)
                inboxStyle.addLine("${peer.nickname}  $proximityBars")
            }
        } else {
            inboxStyle.addLine(contentText)
        }

        val builder = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(colors.primary.toArgb())
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setNumber(unreadMessageCount)
            .setStyle(inboxStyle)
            .setContentIntent(createMainPendingIntent())
            .setOngoing(true)
            .addAction(
                0,
                getString(
                R.string.notification_action_shutdown),
                createActionPendingIntent(ACTION_SHUTDOWN))

        if (alert) {
            builder.setOnlyAlertOnce(false)
            builder.setDefaults(Notification.DEFAULT_ALL)
        } else {
            builder.setOnlyAlertOnce(true)
        }

        return builder.build()
    }

    // --- Helper Functions ---

    private fun getProximityFromRssi(rssi: Int): Int {
        return when {
            rssi > -60 -> 4 // Excellent
            rssi > -70 -> 3 // Good
            rssi > -80 -> 2 // Fair
            rssi > -95 -> 1 // Weak
            else -> 0       // Very weak
        }
    }

    private fun startUiUpdater() {
        if (::uiUpdateScheduler.isInitialized && !uiUpdateScheduler.isShutdown) return
        uiUpdateScheduler = Executors.newSingleThreadScheduledExecutor()
        uiUpdateScheduler.scheduleWithFixedDelay({
            updateNotification(false)
        }, 0, 5000L, TimeUnit.MILLISECONDS) // Update every 5 seconds
    }

    // --- Boilerplate ---

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
            when (intent?.action) {
                ACTION_SHUTDOWN -> shutdownService()
            }
        }
    }

    internal fun shutdownService() {
        Log.i(TAG, "Shutdown action triggered. Stopping service.")
        serviceListener?.onServiceStopping()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }



    private fun createNotificationChannel() {
        val channelName = getString(R.string.notification_channel_name)
        val channelDescription = getString(R.string.notification_channel_description)
        val serviceChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = channelDescription
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(serviceChannel)
    }

    private fun createMainPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        // Add a way to reset unread count when user opens the app
        intent?.action = ACTION_RESET_UNREAD_COUNT
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).apply { `package` = packageName }
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
