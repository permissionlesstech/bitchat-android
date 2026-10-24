package com.bitchat.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.onboarding.LocalOnboardingContentPadding
import com.bitchat.android.ui.BackAction
import com.bitchat.android.ui.AppForegroundEffect
import com.bitchat.android.ui.ChatBackUnwinder
import com.bitchat.android.ui.ChatMeshDelegate
import com.bitchat.android.ui.ChatSessionStartup
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.GeohashSession
import com.bitchat.android.ui.VerificationHandler
import com.bitchat.android.ui.OrientationAwareActivity
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.wifiaware.WifiAwareController
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.bitchat.android.navigation.AppNavigator
import com.bitchat.android.navigation.BitchatNavDisplay
import com.bitchat.android.navigation.ChatRoute
import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.MeshPeerListRoute
import com.bitchat.android.navigation.SheetSceneStrategy
import com.bitchat.android.navigation.VerificationRoute
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.navigation.rootRouteFor
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.VerificationService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

@AndroidEntryPoint
class MainActivity : OrientationAwareActivity() {

    // Core mesh service - provided by the foreground service holder
    private lateinit var meshService: BluetoothMeshService
    private lateinit var unifiedMeshService: MeshService
    private var pendingMeshForegroundServiceStart = false

    // Held by ActivityRetainedComponent, so the back stack outlives configuration
    // changes without being rebuilt here.
    @Inject
    lateinit var navigator: AppNavigator

    // Bluetooth, location services, battery optimization, permissions and the
    // stored onboarding choices, read in one place.
    @Inject
    lateinit var deviceReadiness: DeviceReadiness

    // Decides between onboarding and chat. Retained, like the navigator.
    @Inject
    lateinit var startupGate: AppStartupGate

    // The launch or new intent last handed to the notification and verify
    // handlers, so a later init does not open it a second time (F1).
    private var handledIntent: Intent? = null

    @Inject
    lateinit var locationChannelManager: LocationChannelManager

    // The chat session's, shared with ChatViewModel; the verify deep link
    // starts a QR verification through it.
    @Inject
    lateinit var verificationHandler: VerificationHandler

    // What the mesh reports to while the UI is attached; the chat session's.
    @Inject
    lateinit var chatMeshDelegate: ChatMeshDelegate
    private val meshDelegateBinding by lazy {
        com.bitchat.android.mesh.MeshDelegateBinding(unifiedMeshService, chatMeshDelegate)
    }

    // The chat session's. The Activity starts the session, unwinds the chat
    // screen's overlays on Back, and routes notification taps into it, so none
    // of that waits for the chat screen's ViewModel to exist.
    @Inject
    lateinit var chatSessionStartup: ChatSessionStartup

    @Inject
    lateinit var chatBackUnwinder: ChatBackUnwinder

    @Inject
    lateinit var chatState: ChatState

    @Inject
    lateinit var chatNotifications: com.bitchat.android.ui.NotificationManager

    @Inject
    lateinit var geohashSession: GeohashSession

    // Every destination, onboarding's included, contributed by the features
    // through Hilt.
    @Inject
    lateinit var entryInstallers: Set<@JvmSuppressWildcards EntryProviderInstaller>

    private val forceFinishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == com.bitchat.android.util.AppConstants.UI.ACTION_FORCE_FINISH) {
                android.util.Log.i("MainActivity", "Received force finish broadcast, closing UI")
                finishAffinity()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.setRecentsScreenshotEnabled(false)
        }
        // Register receiver for force finish signal from shutdown coordinator
        val filter = android.content.IntentFilter(com.bitchat.android.util.AppConstants.UI.ACTION_FORCE_FINISH)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                forceFinishReceiver,
                filter,
                com.bitchat.android.util.AppConstants.UI.PERMISSION_FORCE_FINISH,
                null,
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                forceFinishReceiver,
                filter,
                com.bitchat.android.util.AppConstants.UI.PERMISSION_FORCE_FINISH,
                null
            )
        }
        
        // Check if this is a quit request from the notification
        if (intent.getBooleanExtra("ACTION_QUIT_APP", false)) {
            android.util.Log.d("MainActivity", "Quit request received in onCreate, finishing activity")
            finish()
            return
        }

        com.bitchat.android.service.AppShutdownCoordinator.cancelPendingShutdown()
        
        // Enable edge-to-edge display for modern Android look
        enableEdgeToEdge()

        // Start the foreground service when allowed, then get mesh instances from the holder.
        startMeshForegroundServiceBestEffort()
        meshService = com.bitchat.android.service.MeshServiceHolder.getOrCreate(applicationContext)
        unifiedMeshService = com.bitchat.android.service.MeshServiceHolder.getUnifiedOrCreate(applicationContext)
        // Expose BLE mesh to Wi‑Fi Aware controller for cross-transport relays - DEPRECATED
        // Bridging is now handled by TransportBridgeService automatically

        setContent {
            BitchatTheme {
                AppForegroundEffect()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    val phase by startupGate.phase.collectAsState()
                    val retrying by startupGate.retrying.collectAsState()
                    // Read once per composition: it only matters while CHECKING,
                    // which after onboarding completes comes only from Try again.
                    val firstLaunch = remember { deviceReadiness.snapshot().firstLaunch }
                    val root = rootRouteFor(phase, firstLaunch, retrying)

                    // Restores the stack after process death. Composed before the
                    // seeding effect below, which then sees a stack whose root
                    // already matches and leaves it alone.
                    //
                    // NavKeySerializer handles the polymorphism, so this keeps
                    // working as routes gain arguments. Encoded to a Bundle through
                    // savedstate rather than to JSON: kotlinx-serialization-json is
                    // not on the classpath and is not needed here.
                    val stackSerializer = remember { ListSerializer(NavKeySerializer<NavKey>()) }
                    rememberSaveable(
                        saver = Saver(
                            save = { encodeToSavedState(stackSerializer, navigator.snapshot()) },
                            restore = { bundle ->
                                navigator.restore(decodeFromSavedState(stackSerializer, bundle))
                            }
                        )
                    ) { }

                    // The chat stack set aside while onboarding covers it, as when
                    // Bluetooth or a permission is turned off mid-session. Saved, so
                    // a process death during onboarding does not lose it either.
                    var stackUnderOnboarding by rememberSaveable(
                        saver = Saver(
                            save = { encodeToSavedState(stackSerializer, ArrayList(it.value)) },
                            restore = { mutableStateOf(decodeFromSavedState(stackSerializer, it)) }
                        )
                    ) { mutableStateOf(emptyList<NavKey>()) }

                    // Seeds the stack on its first run and re-roots it on every
                    // later crossing between onboarding and chat. Keyed on root, so
                    // it stays quiet between onboarding steps. The stack is replaced,
                    // not pushed onto: onboarding must not be reachable with Back once
                    // the app is in, nor chat while onboarding shows. Compares the
                    // root of the stack, not its top, so pushing a destination onto
                    // chat does not read as a crossing. Returning to chat puts back
                    // what onboarding interrupted, as upstream did by keeping chat's
                    // sheets in ChatViewModel.
                    LaunchedEffect(root) {
                        if (navigator.backStack.firstOrNull() != root) {
                            if (navigator.backStack.firstOrNull() == ChatRoute) {
                                stackUnderOnboarding = navigator.snapshot()
                            }
                            val interrupted = stackUnderOnboarding
                            if (root == ChatRoute && interrupted.firstOrNull() == ChatRoute) {
                                navigator.restore(interrupted)
                            } else {
                                navigator.resetTo(root)
                            }
                            if (root == ChatRoute) stackUnderOnboarding = emptyList()
                        }
                    }

                    val sheetSceneStrategy = remember { SheetSceneStrategy() }

                    // NavDisplay rejects an empty back stack and the effect above does
                    // not run until after this composition, so the host waits a frame
                    // for it. Gate on the stack itself: root is non-null immediately,
                    // so gating on that would compose with nothing to show.
                    //
                    // Only onboarding takes the scaffold insets; ChatScreen applies
                    // its own status, navigation and IME padding.
                    if (navigator.backStack.isNotEmpty()) {
                        CompositionLocalProvider(LocalOnboardingContentPadding provides innerPadding) {
                            BitchatNavDisplay(
                                navigator = navigator,
                                entryInstallers = entryInstallers,
                                onExit = { finish() },
                                modifier = Modifier.fillMaxSize(),
                                sceneStrategies = listOf(sheetSceneStrategy),
                            )
                        }
                    }

                    // NavDisplay enables its own back handler only while
                    // scene.previousEntries.isNotEmpty(), so at the root destination it
                    // takes no press and Back would close the app with an overlay open.
                    // Composed after NavDisplay because the last-composed enabled
                    // handler wins, so overlays unwind before routes pop; called
                    // unconditionally and gated by `enabled`, because a conditional
                    // call would reorder composition.
                    // Started on the first composition, where the Activity first
                    // reached the chat ViewModel before, and ahead of any
                    // notification intent that selects a channel. Idempotent.
                    chatSessionStartup.start()
                    val pendingBackAction by chatBackUnwinder.pendingBackAction.collectAsState()
                    BackHandler(
                        enabled = navigator.backStack.lastOrNull() == ChatRoute &&
                            pendingBackAction != BackAction.None
                    ) {
                        // enabled trails the state by a dispatch and a recomposition, so a
                        // second quick press can arrive with nothing left to unwind. Forward
                        // it rather than swallowing it: pop a route if there is one, and
                        // otherwise leave, which is what the press would have done anyway.
                        if (!chatBackUnwinder.handle() && !navigator.goBack()) finish()
                    }
                }
            }
        }
        
        // Watches the radios for the whole Activity, not for as long as some
        // composable happens to stay on screen. While the app is ready, a radio
        // off sends it back to onboarding (T12/T19); onboarding hears the radios
        // itself while it shows.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                deviceReadiness.systemEvents.collect { event -> startupGate.onSystemEvent(event) }
            }
        }

        // Follows the app gate for the whole Activity. The gate runs init itself,
        // so a configuration change does not interrupt it (F2); the Activity
        // does the parts that need it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                startupGate.phase.collect { phase ->
                    when (phase) {
                        // Set up the unified mesh delegate before the gate starts
                        // the transports
                        AppPhase.INITIALIZING -> meshDelegateBinding.attach()
                        // App is fully initialized, mesh service is running
                        AppPhase.READY -> {
                            if (startupGate.takeInitCompleted()) finishInit()
                            android.util.Log.i("MainActivity", "Onboarding completed - app ready")
                        }
                        AppPhase.INIT_FAILED -> android.util.Log.e("MainActivity", "Onboarding error state reached")
                        // Onboarding picks its own step
                        AppPhase.ONBOARDING, AppPhase.CHECKING -> Unit
                    }
                }
            }
        }

        // Keep the unified mesh delegate attached when Wi-Fi Aware starts after the UI.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WifiAwareController.running.collect { running ->
                    if (running && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        meshDelegateBinding.attach()
                    }
                }
            }
        }
        
        // Decides between onboarding and init, only from the initial CHECKING
        // state, so a configuration change does not restart it
        startupGate.start()
    }
    
    private fun startMeshForegroundServiceBestEffort() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingMeshForegroundServiceStart = true
            Log.i("MainActivity", "Deferring foreground mesh service start until activity is started")
            return
        }

        try {
            com.bitchat.android.service.MeshForegroundService.start(applicationContext)
            pendingMeshForegroundServiceStart = false
        } catch (e: Exception) {
            pendingMeshForegroundServiceStart = true
            Log.w("MainActivity", "Unable to start foreground mesh service; will retry when activity is started", e)
        }
    }
    
    // Init's Activity part, once per init that ended in READY.
    private fun finishInit() {
        startMeshForegroundServiceBestEffort()

        // Handle any notification intent, unless an earlier init did
        handleIntentOnce(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        // Check if this is a quit request from the notification
        if (intent.getBooleanExtra("ACTION_QUIT_APP", false)) {
            android.util.Log.d("MainActivity", "Quit request received, finishing activity")
            finish()
            return
        }

        com.bitchat.android.service.AppShutdownCoordinator.cancelPendingShutdown()
        
        // Handle notification intents when app is already running
        if (startupGate.phase.value == AppPhase.READY) {
            handleIntentOnce(intent)
        }
    }

    // Each intent instance is handled at most once: a re-init after onboarding
    // came back mid-session must not reopen the chat the app was launched into.
    private fun handleIntentOnce(intent: Intent) {
        if (intent === handledIntent) return
        handledIntent = intent
        handleNotificationIntent(intent)
        handleVerificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (pendingMeshForegroundServiceStart) {
            startMeshForegroundServiceBestEffort()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Revoke stale live-location work before any resumed UI can use cached channels.
        locationChannelManager.syncPermissionState()

        // Check Bluetooth and Location status on resume and handle accordingly
        // Reattach the session's mesh delegate, detached in onPause. Also while
        // starting up: the INITIALIZING branch attached it once, on entry.
        val phase = startupGate.phase.value
        if (phase == AppPhase.READY || phase == AppPhase.INITIALIZING) {
            try { meshDelegateBinding.attach() } catch (_: Exception) { }
        }

        if (startupGate.phase.value == AppPhase.READY) {
            // Back to onboarding if Bluetooth or location services went off
            // while the app was in the background (T42/T43)
            startupGate.onResumed()
            if (startupGate.phase.value == AppPhase.READY) {
                // If location is enabled, ensure Wi-Fi Aware starts if it was blocked by location earlier
                WifiAwareController.startIfPossible()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Detach the UI delegate whatever the phase, so the foreground service can own
        // DM notifications while the UI is closed. Resume may have attached it just
        // before sending the app to onboarding.
        try { meshDelegateBinding.detachIfAttached() } catch (_: Exception) { }
    }
    
    /**
     * Handle intents from notification clicks - open specific private chat or geohash chat
     */
    private fun handleNotificationIntent(intent: Intent) {
        val shouldOpenPrivateChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, 
            false
        )
        
        val shouldOpenGeohashChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_GEOHASH_CHAT,
            false
        )
        
        when {
            shouldOpenPrivateChat -> {
                val peerID = intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_PEER_ID)
                val senderNickname = intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_SENDER_NICKNAME)
                
                if (peerID != null) {
                    Log.d("MainActivity", "Opening private chat with $senderNickname (peerID: $peerID) from notification")
                    
                    // Opens the chat over the peer list, so closing it lands on
                    // the list of conversations rather than straight back on chat.
                    if (navigator.popTo(ChatRoute)) {
                        navigator.goTo(MeshPeerListRoute)
                        navigator.openPrivateChat(
                            ContactDirectory.canonicalConversationId(peerID),
                            over = MeshPeerListRoute,
                        )
                    }
                    
                    // Clear notifications for this sender since user is now viewing the chat
                    chatNotifications.clearNotificationsForSender(peerID)
                }
            }
            
            shouldOpenGeohashChat -> {
                val geohash = intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_GEOHASH)
                
                if (geohash != null) {
                    Log.d("MainActivity", "Opening geohash chat from notification")
                    
                    // Switch to the geohash channel - create appropriate geohash channel level
                    val level = when (geohash.length) {
                        7 -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                        6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                        5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                        4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                        2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                        else -> com.bitchat.android.geohash.GeohashChannelLevel.CITY // Default fallback
                    }
                    val geohashChannel = com.bitchat.android.geohash.GeohashChannel(level, geohash)
                    val channelId = com.bitchat.android.geohash.ChannelID.Location(geohashChannel)
                    geohashSession.selectLocationChannel(channelId)
                    
                    // Update current geohash state for notifications
                    chatNotifications.setCurrentGeohash(geohash)
                    
                    // Clear notifications for this geohash since user is now viewing it
                    chatNotifications.clearNotificationsForGeohash(geohash)
                }
            }
        }
    }

    private fun handleVerificationIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "bitchat" || uri.host != "verify") return

        // Runs after the stack has been seeded: on a cold start once init
        // reaches READY, by which time onboarding has put chat at the root,
        // and otherwise from onNewIntent once onboarding is complete.
        // A sheet is never under anything, so unwind to chat first. When chat
        // is not on the stack the result is false and nothing changes.
        navigator.popTo(ChatRoute)
        navigator.goTo(VerificationRoute(chatState.selectedPrivateChatPeer.value))
        val qr = VerificationService.verifyScannedQR(uri.toString())
        if (qr != null) {
            verificationHandler.beginQRVerification(qr)
        }
    }

    
    override fun onDestroy() {
        super.onDestroy()
        
        try { unregisterReceiver(forceFinishReceiver) } catch (_: Exception) { }
        // The Bluetooth and location receivers go with the systemEvents collector.

        // Do not stop mesh here; ForegroundService owns lifecycle for background reliability
    }
}
