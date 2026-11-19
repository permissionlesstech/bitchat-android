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
import com.bitchat.android.di.AppIdentityStartup
import com.bitchat.android.di.CurrentSession
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.mesh.IdentityMeshBinding
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
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.wifiaware.WifiAwareController
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.bitchat.android.navigation.AppNavigator
import com.bitchat.android.navigation.BitchatNavDisplay
import com.bitchat.android.navigation.ChatRoute
import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.GenerationViewModelStoreOwner
import com.bitchat.android.navigation.MeshPeerListRoute
import com.bitchat.android.navigation.SheetSceneStrategy
import com.bitchat.android.navigation.VerificationRoute
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.navigation.rootRouteFor
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.VerificationService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

@AndroidEntryPoint
class MainActivity : OrientationAwareActivity() {

    private var pendingMeshForegroundServiceStart = false

    // Held by ActivityRetainedComponent, so the back stack outlives configuration
    // changes without being rebuilt here.
    @Inject
    lateinit var navigator: AppNavigator

    // The screens' ViewModels, retained with the back stack. A new chat session discards them.
    @Inject
    lateinit var screenViewModels: GenerationViewModelStoreOwner

    // Bluetooth, location services, battery optimization, permissions and the
    // stored onboarding choices, read in one place.
    @Inject
    lateinit var deviceReadiness: DeviceReadiness

    // Decides between onboarding and chat. Retained, like the navigator.
    @Inject
    lateinit var startupGate: AppStartupGate

    // Whether the current intent has been handed to the notification and verify
    // handlers, so a later init does not open it a second time (F1). Saved with
    // the instance state: a recreated Activity gets the same intent back.
    private var intentHandled = false

    // Providers, asked when used, so the Activity keeps no other identity object.
    @Inject
    @CurrentIdentity
    lateinit var locationChannelManager: Provider<LocationChannelManager>

    @Inject
    lateinit var shutdownCoordinator: com.bitchat.android.service.AppShutdownCoordinator

    @Inject
    @CurrentIdentity
    lateinit var nearbyNotesController: Provider<com.bitchat.android.nostr.NearbyNotesController>

    // Asked where the foreground effect is composed, which is where it is first built.
    @Inject
    @CurrentIdentity
    lateinit var liveVoiceManager: Provider<com.bitchat.android.features.voice.LiveVoiceManager>

    // Providers, asked when used, so the Activity keeps nothing of the identity's.
    @Inject
    @CurrentIdentity
    lateinit var meshServiceHolder: Provider<MeshServiceHolder>

    @Inject
    @CurrentIdentity
    lateinit var wifiAwareController: Provider<WifiAwareController>

    @Inject
    @CurrentIdentity
    lateinit var contactDirectory: Provider<ContactDirectory>

    @Inject
    @CurrentIdentity
    lateinit var verificationService: Provider<VerificationService>

    // The chat session's objects, asked when used so the Activity keeps none of them: each read
    // gets the current session. The verify deep link starts a QR verification through this one.
    @Inject
    @CurrentSession
    lateinit var verificationHandler: Provider<VerificationHandler>

    // What the mesh reports to while the UI is attached.
    @Inject
    @CurrentSession
    lateinit var chatMeshDelegate: Provider<ChatMeshDelegate>

    // Made in onCreate; null only on a quit launch, which never gets that far. It follows the identity.
    private var meshDelegateBinding: IdentityMeshBinding? = null

    // Says when a panic is wiping the identity, which is when nothing new starts on it.
    @Inject
    lateinit var identityHolder: IdentityHolder

    // Says when a new identity has started, which is when the Activity moves over to it.
    @Inject
    lateinit var identityStartup: AppIdentityStartup

    // The Activity starts the session, unwinds the chat screen's overlays on Back,
    // and routes notification taps into it, so none of that waits for the chat
    // screen's ViewModel to exist.
    @Inject
    @CurrentSession
    lateinit var chatSessionStartup: Provider<ChatSessionStartup>

    // Injected here so it is built on the main thread, which is the only place the retained
    // lifecycle takes a listener. Its first read could otherwise come from a mesh callback.
    @Inject
    lateinit var chatSessionHolder: com.bitchat.android.ui.ChatSessionHolder

    @Inject
    @CurrentSession
    lateinit var chatBackUnwinder: Provider<ChatBackUnwinder>

    @Inject
    @CurrentSession
    lateinit var chatState: Provider<ChatState>

    @Inject
    @CurrentSession
    lateinit var chatNotifications: Provider<com.bitchat.android.ui.NotificationManager>

    @Inject
    @CurrentSession
    lateinit var geohashSession: Provider<GeohashSession>

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
        intentHandled = savedInstanceState?.getBoolean(STATE_INTENT_HANDLED) ?: false
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

        shutdownCoordinator.cancelPendingShutdown()
        
        // Enable edge-to-edge display for modern Android look
        enableEdgeToEdge()

        // Start the foreground service when allowed, then get mesh instances from the holder.
        startMeshForegroundServiceBestEffort()
        meshDelegateBinding = IdentityMeshBinding(
            generation = { identityStartup.startedGeneration.value },
            mesh = { meshServiceHolder.get().getUnifiedOrCreate(applicationContext) },
            delegate = { chatMeshDelegate.get() },
        )
        // Expose BLE mesh to Wi‑Fi Aware controller for cross-transport relays - DEPRECATED
        // Bridging is now handled by TransportBridgeService automatically

        setContent {
            BitchatTheme {
                // What the composition reads from the identity is read again for each new one.
                val identityGeneration by identityStartup.startedGeneration.collectAsState()
                key(identityGeneration) {
                    AppForegroundEffect(nearbyNotesController.get(), liveVoiceManager.get())
                }
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
                    // A new session forgets it, since it names the old session's screens.
                    var stackUnderOnboarding by rememberSaveable(
                        screenViewModels.generation,
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
                                viewModelStores = screenViewModels,
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
                    // Keyed on the identity ending too: a session held back while a panic wipes it
                    // gives way to a live one here when the panic keeps that identity.
                    val identityEnding by identityHolder.ending.collectAsState()
                    key(identityGeneration, screenViewModels.generation, identityEnding) {
                        chatSessionStartup.get().start()
                        val pendingBackAction by chatBackUnwinder.get().pendingBackAction.collectAsState()
                        BackHandler(
                            enabled = navigator.backStack.lastOrNull() == ChatRoute &&
                                pendingBackAction != BackAction.None
                        ) {
                            // enabled trails the state by a dispatch and a recomposition, so a
                            // second quick press can arrive with nothing left to unwind. Forward
                            // it rather than swallowing it: pop a route if there is one, and
                            // otherwise leave, which is what the press would have done anyway.
                            if (!chatBackUnwinder.get().handle() && !navigator.goBack()) finish()
                        }
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
                        AppPhase.INITIALIZING -> meshDelegateBinding?.attach()
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

        // Keep the unified mesh delegate attached when Wi-Fi Aware starts after the UI, watching
        // the current identity's controller. While resumed, the only emission here is a new
        // identity having started: the UI is up, so it moves to that identity's mesh at once. This
        // collector starts below RESUMED, where onResume does the attaching.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                identityStartup.startedGeneration.collectLatest {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) attachIfThePhaseAllows()
                    wifiAwareController.get().running.collect { running ->
                        if (running && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            meshDelegateBinding?.attach()
                        }
                    }
                }
            }
        }
        
        // Posted rather than immediate: a session is often first read during composition or under
        // the holder's lock, and neither is a place to change the back stack or clear ViewModels.
        // Not bound to a lifecycle state, so the stack and the store move even while stopped. Composition
        // pauses at ON_STOP, so a ViewModel on an on-screen entry is released when the Activity restarts.
        lifecycleScope.launch(Dispatchers.Main) {
            chatSessionHolder.generation.collect(::followSession)
        }

        // Decides between onboarding and init, only from the initial CHECKING
        // state, so a configuration change does not restart it
        startupGate.start()
    }
    
    // Closes what was open and discards the screens' ViewModels, so the next ones are built against
    // the new session. Compared rather than assumed new, since a recreated Activity reads the
    // generation again, and one that moved while no Activity was collecting is caught here.
    // Onboarding covering the chat stays up: popTo leaves a stack without chat alone.
    private fun followSession(generation: Int) {
        if (generation <= screenViewModels.generation) return
        navigator.popTo(ChatRoute)
        while (screenViewModels.generation < generation) screenViewModels.advance()
    }

    // Reattaches the session's mesh delegate. Also while starting up: the INITIALIZING branch
    // attached it once, on entry.
    private fun attachIfThePhaseAllows() {
        val phase = startupGate.phase.value
        if (phase == AppPhase.READY || phase == AppPhase.INITIALIZING) meshDelegateBinding?.attach()
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
        intentHandled = false
        
        // Check if this is a quit request from the notification
        if (intent.getBooleanExtra("ACTION_QUIT_APP", false)) {
            android.util.Log.d("MainActivity", "Quit request received, finishing activity")
            finish()
            return
        }

        shutdownCoordinator.cancelPendingShutdown()
        
        // Handle notification intents when app is already running
        if (startupGate.phase.value == AppPhase.READY) {
            handleIntentOnce(intent)
        }
    }

    // Each intent is handled at most once, across rotation too: a re-init after
    // onboarding came back mid-session must not reopen the chat the app was
    // launched into.
    private fun handleIntentOnce(intent: Intent) {
        if (intentHandled) return
        intentHandled = true
        handleNotificationIntent(intent)
        handleVerificationIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_INTENT_HANDLED, intentHandled)
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
        locationChannelManager.get().syncPermissionState()

        // Check Bluetooth and Location status on resume and handle accordingly
        // Reattach the session's mesh delegate, detached in onPause.
        try { attachIfThePhaseAllows() } catch (_: Exception) { }

        if (startupGate.phase.value == AppPhase.READY) {
            // Back to onboarding if Bluetooth or location services went off
            // while the app was in the background (T42/T43)
            startupGate.onResumed()
            if (startupGate.phase.value == AppPhase.READY) {
                // If location is enabled, ensure Wi-Fi Aware starts if it was blocked by location earlier
                wifiAwareController.get().startIfPossible()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Detach the UI delegate whatever the phase, so the foreground service can own
        // DM notifications while the UI is closed. Resume may have attached it just
        // before sending the app to onboarding.
        try { meshDelegateBinding?.detachIfAttached() } catch (_: Exception) { }
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
                            contactDirectory.get().canonicalConversationId(peerID),
                            over = MeshPeerListRoute,
                        )
                    }
                    
                    // Clear notifications for this sender since user is now viewing the chat
                    chatNotifications.get().clearNotificationsForSender(peerID)
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
                    geohashSession.get().selectLocationChannel(channelId)
                    
                    // Update current geohash state for notifications
                    chatNotifications.get().setCurrentGeohash(geohash)
                    
                    // Clear notifications for this geohash since user is now viewing it
                    chatNotifications.get().clearNotificationsForGeohash(geohash)
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
        navigator.goTo(VerificationRoute(chatState.get().selectedPrivateChatPeer.value))
        val qr = verificationService.get().verifyScannedQR(uri.toString())
        if (qr != null) {
            verificationHandler.get().beginQRVerification(qr)
        }
    }

    
    override fun onDestroy() {
        super.onDestroy()
        
        try { unregisterReceiver(forceFinishReceiver) } catch (_: Exception) { }
        // The Bluetooth and location receivers go with the systemEvents collector.

        // Do not stop mesh here; ForegroundService owns lifecycle for background reliability
    }

    private companion object {
        const val STATE_INTENT_HANDLED = "intent_handled"
    }
}
