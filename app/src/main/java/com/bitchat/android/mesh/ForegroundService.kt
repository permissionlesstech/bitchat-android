package com.bitchat.android.mesh

import android.R.style.Theme
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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.ui.theme.DarkColorScheme
import com.bitchat.android.ui.theme.LightColorScheme
import com.bitchat.android.util.AnsiChars
import com.bitchat.android.util.AnsiGrid
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Data class to hold combined peer information for the UI
data class PeerInfo(val id: String, val nickname: String, val proximity: Int)


private const val FONT_SIZE = 10

/**
 * A foreground service that provides a rich, interactive, and theme-consistent notification
 * in the style of a monochrome IRC/terminal client. It displays nearby peers with proximity
 * and a live log of recent messages by acting as a delegate for BluetoothMeshService.
 *
 * The notification features a generative ANSI art landscape where peers are represented as stars.
 */
class ForegroundService : Service(), BluetoothMeshDelegate {

    private val binder = LocalBinder()
    private var meshService: BluetoothMeshService? = null
    private lateinit var notificationManager: NotificationManager
    private var serviceListener: ServiceListener? = null

    // --- Live State for Notification UI ---
    private var activePeers = listOf<PeerInfo>()
    private var recentMessages = mutableListOf<BitchatMessage>()
    private val knownPeerIds = HashSet<String>() // Used to detect new peers

    // --- State for ANSI Grid Visualization ---
    private var frame: Long = 0 // Animation frame counter
    private val peerStarData = mutableMapOf<String, Pair<Int, Float>>() // PeerID -> (x, random phase offset)
    private val random = Random()


    // Scheduler for periodic UI refreshes
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

        if (meshService == null) {
            meshService = BluetoothMeshService(this).apply {
                delegate = this@ForegroundService
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_STOP_SERVICE)
            addAction(ACTION_MUTE)
        }
        ContextCompat.registerReceiver(this, notificationActionReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        recentMessages.add(0, message)
        if (recentMessages.size > 10) {
            recentMessages = recentMessages.take(10).toMutableList()
        }
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

    private fun updateNotification(alert: Boolean) {
        val nicknames = meshService?.getPeerNicknames() ?: emptyMap()
        val rssiValues = meshService?.getPeerRSSI() ?: emptyMap()

        activePeers = nicknames.map { (peerId, nickname) ->
            val rssi = rssiValues[peerId] ?: -100
            PeerInfo(id = peerId, nickname = nickname, proximity = getProximityFromRssi(rssi))
        }.sortedByDescending { it.proximity }

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
            builder.setOnlyAlertOnce(false)
            builder.setDefaults(Notification.DEFAULT_ALL)
        } else {
            builder.setOnlyAlertOnce(true)
        }

        return builder.build()
    }

    private fun getAnsiGrid(bitmapWidth: Int, bitmapHeight: Int, fgColor: Int): Triple<AnsiGrid, Float, Float>? {
        val density = resources.displayMetrics.density
        val textPaint = Paint().apply {
            color = fgColor
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = FONT_SIZE * density
        }
        // Use a standard, reliable character for measuring width. 'W' is a good choice.
        val charWidth = textPaint.measureText("W")
        val charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent

        if (charWidth <= 0 || charHeight <= 0) return null

        val numCols = (bitmapWidth / charWidth).toInt()
        val numRows = (bitmapHeight / charHeight).toInt()

        if (numCols <= 0 || numRows <= 0) return null

        val grid = AnsiGrid(numCols, numRows, textPaint)
        return Triple(grid, charWidth, charHeight)
    }

    private fun getCollapsedRenderBitmap(bitmapWidth: Int, bitmapHeight: Int): Bitmap {
        val bitmap = createBitmap(bitmapWidth, bitmapHeight)
        val canvas = Canvas(bitmap)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isDarkTheme) DarkColorScheme.background.toArgb() else LightColorScheme.background.toArgb()
        val fgColor = if (isDarkTheme) DarkColorScheme.primary.toArgb() else LightColorScheme.primary.toArgb()
        canvas.drawColor(bgColor)

        val gridData = getAnsiGrid(bitmapWidth, bitmapHeight, fgColor)
        if (gridData != null) {
            val (grid, charWidth, charHeight) = gridData
            drawTerminalContent(grid, activePeers, isForCollapsedView = true)
            // Pass charWidth and charHeight to the updated render function
            grid.render(canvas, charWidth, charHeight)
        }
        return bitmap
    }

    /**
     * Renders the entire expanded notification content into a single bitmap.
     */
    private fun getExpandedRenderBitmap(bitmapWidth: Int, bitmapHeight: Int): Bitmap {
        val bitmap = createBitmap(bitmapWidth, bitmapHeight)
        val canvas = Canvas(bitmap)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isDarkTheme) DarkColorScheme.background.toArgb() else LightColorScheme.background.toArgb()
        val fgColor = if (isDarkTheme) DarkColorScheme.primary.toArgb() else LightColorScheme.primary.toArgb()
        canvas.drawColor(bgColor)

        val gridData = getAnsiGrid(bitmapWidth, bitmapHeight, fgColor)
        if (gridData != null) {
            val (grid, charWidth, charHeight) = gridData
            drawTerminalContent(grid, activePeers, isForCollapsedView = false)
            // Pass charWidth and charHeight to the updated render function
            grid.render(canvas, charWidth, charHeight)
        }
        return bitmap
    }

    // --- Generative ANSI Art Functions ---

    /**
     * Main drawing function to orchestrate the creation of the terminal UI.
     * Switches between collapsed and expanded layouts.
     */
    private fun drawTerminalContent(grid: AnsiGrid, peers: List<PeerInfo>, isForCollapsedView: Boolean) {
        grid.clear()
        if (isForCollapsedView) {
            drawCollapsedContent(grid, peers)
        } else {
            drawExpandedContent(grid, peers)
        }
    }

    /**
     * Draws the content for the expanded notification view.
     */
    private fun drawExpandedContent(grid: AnsiGrid, peers: List<PeerInfo>) {
        drawBitchatLogo(grid)

        val peerCountText = "peers: ${peers.size}"
        val xPos = grid.width - peerCountText.length - 1
        if (xPos >= 0) {
            grid.drawText(xPos, 0, peerCountText)
        }

        val startX = 15
        val startY = 1
        val maxPeers = (grid.height - startY)
        val availableWidth = grid.width - startX
        drawPeerList(grid, peers, startX, startY, maxPeers, availableWidth)
    }

    /**
     * Draws a compact, info-rich layout for the collapsed notification view.
     */
    private fun drawCollapsedContent(grid: AnsiGrid, peers: List<PeerInfo>) {
        drawBitchatLogo(grid)

        val peerCountText = "peers: ${peers.size}"
        val xPos = grid.width - peerCountText.length - 1
        if (xPos >= 0) {
            grid.drawText(xPos, 0, peerCountText)
        }

        val startX = 15
        val startY = 1
        val maxPeers = (grid.height - startY).coerceAtMost(3)
        val availableWidth = grid.width - startX
        drawPeerList(grid, peers, startX, startY, maxPeers, availableWidth)
    }

    /**
     * Draws the "bitchat" logo and name side-by-side for the expanded view.
     */
    private fun drawBitchatLogo(grid: AnsiGrid) {
        val logoOutline = listOf(
            " ╓─╮ ─╥─ ─╥─",
            " ╟─┤  ║   ║ ",
            " ╙─╯ ─╨─  ╜ ",
            "   bitchat  "
        )

        logoOutline.forEachIndexed { index, line ->
            grid.drawText(0, index, line)
        }
    }


    /**
     * Draws a list of peers at a specified location with gradient proximity bars.
     */
    private fun drawPeerList(grid: AnsiGrid, peers: List<PeerInfo>, startX: Int, startY: Int, maxPeers: Int, availableWidth: Int) {
        val proximityGradient = listOf(AnsiChars.Shade.LIGHT, AnsiChars.Shade.MEDIUM, AnsiChars.Shade.DARK, AnsiChars.Block.FULL)

        peers.take(maxPeers).forEachIndexed { index, peer ->
            val yPos = startY + index
            if (yPos >= grid.height) return@forEachIndexed // Stop if we run out of space

            val bars = proximityGradient.take(peer.proximity).joinToString("")
            val padding = AnsiChars.line(' ', 4 - peer.proximity)
            val proximityString = "[$bars$padding]"

            // Ensure the nickname doesn't overflow the available space.
            val maxNicknameLength = (availableWidth - proximityString.length - 1).coerceAtLeast(1)
            val nickname = if (peer.nickname.length > maxNicknameLength) {
                peer.nickname.take(maxNicknameLength)
            } else {
                peer.nickname
            }
            val text = String.format("%-${maxNicknameLength}s %s", nickname, proximityString)
            grid.drawText(startX, yPos, text)
        }
    }

    private fun getRemoteViewDimensions(@LayoutRes layoutId: Int, viewId: Int): Pair<Int, Int>? {
        val remoteViews = RemoteViews(packageName, layoutId)
        val layout = remoteViews.apply(applicationContext, null) ?: return null
        val targetView = layout.findViewById<android.view.View>(viewId) ?: return null
        if (targetView.width == 0 || targetView.height == 0) {
            // If the view hasn't been measured yet, try to force a measure pass
            targetView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, android.view.View.MeasureSpec.AT_MOST),
                android.view.View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, android.view.View.MeasureSpec.AT_MOST)
            )
        }
        return if (targetView.measuredWidth > 0 && targetView.measuredHeight > 0) {
            Pair(targetView.measuredWidth, targetView.measuredHeight)
        } else null
    }

    private fun createCollapsedRemoteViews(): RemoteViews {
        val dimensions = getRemoteViewDimensions(R.layout.notification_terminal_collapsed, R.id.notification_render)
        val bitmapWidthPx = dimensions?.first ?: (resources.displayMetrics.widthPixels).toInt()
        val bitmapHeightPx = dimensions?.second ?: (48 * resources.displayMetrics.density).toInt()
        return RemoteViews(packageName, R.layout.notification_terminal_collapsed).apply {
            setImageViewBitmap(R.id.notification_render, getCollapsedRenderBitmap(bitmapWidthPx, bitmapHeightPx))
        }
    }

    private fun createExpandedRemoteViews(): RemoteViews {
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = if (isDarkTheme) DarkColorScheme else LightColorScheme
        val dimColor = if (isDarkTheme) Color(0xB3FFFFFF).toArgb() else colors.onSurface.toArgb()
        val dimensions = getRemoteViewDimensions(R.layout.notification_terminal_expanded, R.id.notification_render)
        val bitmapWidthPx = dimensions?.first ?: resources.displayMetrics.widthPixels
        val bitmapHeightPx = dimensions?.second ?: (208 * resources.displayMetrics.density).toInt()

        return RemoteViews(packageName, R.layout.notification_terminal_expanded).apply {
            setImageViewBitmap(R.id.notification_render, getExpandedRenderBitmap(bitmapWidthPx, bitmapHeightPx))

            // Set colors for the action buttons
            setTextColor(R.id.notification_action_mute, dimColor)
            setTextColor(R.id.notification_action_stop_expanded, dimColor)

            // Set Actions
            setOnClickPendingIntent(R.id.notification_action_mute, createActionPendingIntent(ACTION_MUTE))
            setOnClickPendingIntent(R.id.notification_action_stop_expanded, createActionPendingIntent(ACTION_STOP_SERVICE))
        }
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
            frame++ // Increment animation frame
            updateNotification(false)
        }, 0, 2000, TimeUnit.MILLISECONDS) // Update every 2 seconds
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
        val serviceChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID, "Bitchat Active Service", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Keeps Bitchat connected and shows live status"
            setShowBadge(false)
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
        val intent = Intent(action).apply { `package` = packageName }
        return PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
