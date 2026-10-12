package com.bitchat.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.bitchat.android.onboarding.BluetoothCheckScreen
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BatteryOptimizationPreferenceManager
import com.bitchat.android.onboarding.BatteryOptimizationScreen
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import com.bitchat.android.onboarding.BackgroundLocationPermissionScreen
import com.bitchat.android.onboarding.InitializationErrorScreen
import com.bitchat.android.onboarding.InitializingScreen
import com.bitchat.android.onboarding.LocationCheckScreen
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionExplanationScreen
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.ui.BackAction
import com.bitchat.android.ui.AboutScreen
import com.bitchat.android.ui.AppForegroundEffect
import com.bitchat.android.ui.ChatBackUnwinder
import com.bitchat.android.ui.ChatMeshDelegate
import com.bitchat.android.ui.ChatSessionStartup
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.GeohashSession
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatUserSheet
import com.bitchat.android.ui.LocationChannelsScreen
import com.bitchat.android.ui.LocationNotesScreen
import com.bitchat.android.ui.MeshPeerListScreen
import com.bitchat.android.ui.PrivateChatScreen
import com.bitchat.android.ui.SecurityVerificationSheet
import com.bitchat.android.ui.VerificationHandler
import com.bitchat.android.ui.VerificationScreen
import com.bitchat.android.ui.debug.DebugSettingsScreen
import com.bitchat.android.ui.OrientationAwareActivity
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.wifiaware.WifiAwareController
import com.bitchat.android.nostr.PoWPreferenceManager
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.bitchat.android.navigation.AboutRoute
import com.bitchat.android.navigation.AppNavigator
import com.bitchat.android.navigation.BitchatNavDisplay
import com.bitchat.android.navigation.ChatRoute
import com.bitchat.android.navigation.ChatUserRoute
import com.bitchat.android.navigation.DebugSettingsRoute
import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.LocationChannelsRoute
import com.bitchat.android.navigation.LocationNotesRoute
import com.bitchat.android.navigation.MeshPeerListRoute
import com.bitchat.android.navigation.OnboardingRoute
import com.bitchat.android.navigation.PrivateChatRoute
import com.bitchat.android.navigation.ScreenTransitions
import com.bitchat.android.navigation.SecurityVerificationRoute
import com.bitchat.android.navigation.SheetSceneStrategy
import com.bitchat.android.navigation.VerificationRoute
import com.bitchat.android.navigation.rootRouteFor
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.VerificationService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

@AndroidEntryPoint
class MainActivity : OrientationAwareActivity() {

    private lateinit var permissionManager: PermissionManager
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private lateinit var bluetoothStatusManager: BluetoothStatusManager
    private lateinit var locationStatusManager: LocationStatusManager
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager
    
    // Core mesh service - provided by the foreground service holder
    private lateinit var meshService: BluetoothMeshService
    private lateinit var unifiedMeshService: MeshService
    private val mainViewModel: MainViewModel by viewModels()
    private var pendingMeshForegroundServiceStart = false

    // Held by ActivityRetainedComponent, so the back stack outlives configuration
    // changes without being rebuilt here.
    @Inject
    lateinit var navigator: AppNavigator

    // Watches the adapter for the whole Activity, not for as long as some
    // composable happens to stay on screen. Scoping it to a destination is what
    // let a Bluetooth switch-off go unnoticed once chat became its own route.
    private var bluetoothStateReceiver: android.content.BroadcastReceiver? = null

    @Inject
    lateinit var locationChannelManager: LocationChannelManager

    // The chat session's, shared with ChatViewModel; the verify deep link
    // starts a QR verification through it.
    @Inject
    lateinit var verificationHandler: VerificationHandler

    // What the mesh reports to while the UI is attached; the chat session's.
    @Inject
    lateinit var chatMeshDelegate: ChatMeshDelegate

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

        // Initialize permission management
        permissionManager = PermissionManager(this)
        // Start the foreground service when allowed, then get mesh instances from the holder.
        startMeshForegroundServiceBestEffort()
        meshService = com.bitchat.android.service.MeshServiceHolder.getOrCreate(applicationContext)
        unifiedMeshService = com.bitchat.android.service.MeshServiceHolder.getUnifiedOrCreate(applicationContext)
        // Expose BLE mesh to Wi‑Fi Aware controller for cross-transport relays - DEPRECATED
        // Bridging is now handled by TransportBridgeService automatically
        
        bluetoothStatusManager = BluetoothStatusManager(
            activity = this,
            context = this,
            onBluetoothEnabled = ::handleBluetoothEnabled,
            onBluetoothDisabled = ::handleBluetoothDisabled
        )
        locationStatusManager = LocationStatusManager(
            activity = this,
            context = this,
            onLocationEnabled = ::handleLocationEnabled,
            onLocationDisabled = ::handleLocationDisabled
        )
        batteryOptimizationManager = BatteryOptimizationManager(
            activity = this,
            context = this,
            onBatteryOptimizationDisabled = ::handleBatteryOptimizationDisabled,
            onBatteryOptimizationFailed = ::handleBatteryOptimizationFailed
        )
        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager,
            onOnboardingComplete = ::handleOnboardingComplete,
            onBackgroundLocationRequired = {
                mainViewModel.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
            },
            onOnboardingFailed = ::handleOnboardingFailed
        )
        
        setContent {
            BitchatTheme {
                AppForegroundEffect()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    // Only onboarding takes the scaffold insets; ChatScreen applies
                    // its own status, navigation and IME padding.
                    val onboardingModifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                    val onboardingState by mainViewModel.onboardingState.collectAsState()
                    val root = rootRouteFor(onboardingState)

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

                    // Seeds the stack on its first run and re-roots it on every
                    // later crossing between onboarding and chat. Keyed on root, so
                    // it stays quiet between onboarding steps. resetTo rather than
                    // goTo: onboarding must not be reachable with Back once the app
                    // is in. Compares the root of the stack, not its top, so pushing
                    // a destination onto chat does not read as a crossing.
                    LaunchedEffect(root) {
                        if (navigator.backStack.firstOrNull() != root) {
                            navigator.resetTo(root)
                        }
                    }

                    val sheetSceneStrategy = remember { SheetSceneStrategy() }
                    val entries: EntryProviderInstaller = {
                        entry<OnboardingRoute> { OnboardingFlowScreen(onboardingModifier) }
                        entry<ChatRoute>(metadata = ScreenTransitions.rootFade()) {
                            ChatScreen(
                                onShowAbout = { navigator.goTo(AboutRoute) },
                                onShowLocationNotes = { navigator.goTo(LocationNotesRoute) },
                                onShowChatUser = { nickname, messageId ->
                                    navigator.goTo(ChatUserRoute(nickname, messageId))
                                },
                                onShowLocationChannels = { navigator.goTo(LocationChannelsRoute) },
                                onShowPeerList = { navigator.goTo(MeshPeerListRoute) },
                                openPrivateChatID = navigator.backStack
                                    .lastOrNull { it is PrivateChatRoute }
                                    ?.let { (it as PrivateChatRoute).conversationID },
                            )
                        }
                        entry<MeshPeerListRoute>(metadata = SheetSceneStrategy.sheet()) {
                            MeshPeerListScreen(
                                onDismiss = { navigator.popTo(MeshPeerListRoute, inclusive = true) },
                                // Pushed over the list, so Back from verification
                                // returns to it.
                                onShowVerification = {
                                    navigator.goTo(
                                        VerificationRoute(chatState.selectedPrivateChatPeer.value)
                                    )
                                },
                            )
                        }
                        entry<PrivateChatRoute>(metadata = SheetSceneStrategy.sheet()) { route ->
                            PrivateChatScreen(
                                routeConversationID = route.conversationID,
                                onShowSecurityVerification = { conversationID ->
                                    navigator.goTo(SecurityVerificationRoute(conversationID))
                                },
                                onDismiss = { navigator.popTo(route, inclusive = true) },
                            )
                        }
                        entry<SecurityVerificationRoute>(metadata = SheetSceneStrategy.sheet()) { route ->
                            SecurityVerificationSheet(
                                conversationID = route.conversationID,
                                onDismiss = { navigator.popTo(route, inclusive = true) },
                            )
                        }
                        entry<LocationChannelsRoute> {
                            LocationChannelsScreen(
                                onClose = { navigator.popTo(LocationChannelsRoute, inclusive = true) },
                                // Swaps channels for notes rather than stacking them, so
                                // Back from notes returns to chat as it always has.
                                onShowLocationNotes = { navigator.replaceCurrent(LocationNotesRoute) },
                            )
                        }
                        entry<VerificationRoute> { route ->
                            // One close for the header button and Back. Popping by
                            // key makes it idempotent, so a second press during the
                            // exit animation does not pop what lies beneath.
                            val close = { navigator.popTo(route, inclusive = true); Unit }
                            BackHandler(onBack = close)
                            VerificationScreen(
                                peerID = route.peerID,
                                onClose = close,
                            )
                        }
                        entry<ChatUserRoute>(metadata = SheetSceneStrategy.sheet()) { route ->
                            ChatUserSheet(
                                onDismiss = { navigator.popTo(route, inclusive = true) },
                                targetNickname = route.nickname,
                                messageId = route.messageId,
                            )
                        }
                        entry<LocationNotesRoute>(metadata = SheetSceneStrategy.sheet()) {
                            val nickname by chatState.nickname.collectAsStateWithLifecycle()
                            LocationNotesScreen(
                                nickname = nickname,
                                onDismiss = { navigator.popTo(LocationNotesRoute, inclusive = true) },
                            )
                        }
                        entry<AboutRoute> {
                            AboutScreen(
                                onClose = { navigator.goBack() },
                                onShowDebug = { navigator.goTo(DebugSettingsRoute) }
                            )
                        }
                        entry<DebugSettingsRoute> {
                            DebugSettingsScreen(
                                onClose = { navigator.popTo(DebugSettingsRoute, inclusive = true) }
                            )
                        }
                    }

                    // NavDisplay rejects an empty back stack and the effect above does
                    // not run until after this composition, so the host waits a frame
                    // for it. Gate on the stack itself: root is non-null immediately,
                    // so gating on that would compose with nothing to show.
                    if (navigator.backStack.isNotEmpty()) {
                        BitchatNavDisplay(
                            navigator = navigator,
                            entryInstallers = setOf(entries),
                            onExit = { finish() },
                            modifier = Modifier.fillMaxSize(),
                            sceneStrategies = listOf(sheetSceneStrategy),
                        )
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
        
        bluetoothStateReceiver = bluetoothStatusManager.monitorBluetoothState(
            context = this,
            bluetoothStatusManager = bluetoothStatusManager,
            onBluetoothStateChanged = { status ->
                if (status == BluetoothStatus.ENABLED &&
                    mainViewModel.onboardingState.value == OnboardingState.BLUETOOTH_CHECK
                ) {
                    checkBluetoothAndProceed()
                }
            }
        )
        
        // Collect state changes in a lifecycle-aware manner
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.onboardingState.collect { state ->
                    handleOnboardingStateChange(state)
                }
            }
        }

        // Keep the unified mesh delegate attached when Wi-Fi Aware starts after the UI.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WifiAwareController.running.collect { running ->
                    if (running && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        unifiedMeshService.delegate = chatMeshDelegate
                    }
                }
            }
        }
        
        // Only start onboarding process if we're in the initial CHECKING state
        // This prevents restarting onboarding on configuration changes
        if (mainViewModel.onboardingState.value == OnboardingState.CHECKING) {
            checkOnboardingStatus()
        }
    }
    
    @Composable
    private fun OnboardingFlowScreen(modifier: Modifier = Modifier) {
        val onboardingState by mainViewModel.onboardingState.collectAsState()
        val bluetoothStatus by mainViewModel.bluetoothStatus.collectAsState()
        val locationStatus by mainViewModel.locationStatus.collectAsState()
        val batteryOptimizationStatus by mainViewModel.batteryOptimizationStatus.collectAsState()
        val errorMessage by mainViewModel.errorMessage.collectAsState()
        val isBluetoothLoading by mainViewModel.isBluetoothLoading.collectAsState()
        val isLocationLoading by mainViewModel.isLocationLoading.collectAsState()
        val isBatteryOptimizationLoading by mainViewModel.isBatteryOptimizationLoading.collectAsState()

        when (onboardingState) {
            OnboardingState.PERMISSION_REQUESTING -> {
                InitializingScreen(modifier)
            }
            
            OnboardingState.BLUETOOTH_CHECK -> {
                BluetoothCheckScreen(
                    modifier = modifier,
                    status = bluetoothStatus,
                    onEnableBluetooth = {
                        mainViewModel.updateBluetoothLoading(true)
                        bluetoothStatusManager.requestEnableBluetooth()
                    },
                    onRetry = {
                        checkBluetoothAndProceed()
                    },
                    onSkip = {
                        mainViewModel.skipBluetoothCheck()
                        checkLocationAndProceed()
                    },
                    isLoading = isBluetoothLoading
                )
            }
            
            OnboardingState.LOCATION_CHECK -> {
                LocationCheckScreen(
                    modifier = modifier,
                    status = locationStatus,
                    onEnableLocation = {
                        mainViewModel.updateLocationLoading(true)
                        locationStatusManager.requestEnableLocation()
                    },
                    onRetry = {
                        checkLocationAndProceed()
                    },
                    isLoading = isLocationLoading
                )
            }
            
            OnboardingState.BATTERY_OPTIMIZATION_CHECK -> {
                BatteryOptimizationScreen(
                    modifier = modifier,
                    status = batteryOptimizationStatus,
                    onDisableBatteryOptimization = {
                        mainViewModel.updateBatteryOptimizationLoading(true)
                        batteryOptimizationManager.requestDisableBatteryOptimization()
                    },
                    onRetry = {
                        checkBatteryOptimizationAndProceed()
                    },
                    onSkip = {
                        // Skip battery optimization and proceed
                        proceedWithPermissionCheck()
                    },
                    isLoading = isBatteryOptimizationLoading
                )
            }
            
            OnboardingState.PERMISSION_EXPLANATION -> {
                PermissionExplanationScreen(
                    modifier = modifier,
                    permissionCategories = permissionManager.getCategorizedPermissions(),
                    onContinue = {
                        mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
                        onboardingCoordinator.requestPermissions()
                    }
                )
            }

            OnboardingState.BACKGROUND_LOCATION_EXPLANATION -> {
                BackgroundLocationPermissionScreen(
                    modifier = modifier,
                    onContinue = {
                        onboardingCoordinator.requestBackgroundLocation()
                    },
                    onRetry = {
                        onboardingCoordinator.checkBackgroundLocationAndProceed()
                    },
                    onSkip = {
                        onboardingCoordinator.skipBackgroundLocation()
                    }
                )
            }

            // CHECKING, INITIALIZING and COMPLETE are handled by ChatRoute, so this
            // composable is only ever shown for the onboarding steps themselves.
            OnboardingState.CHECKING,
            OnboardingState.INITIALIZING,
            OnboardingState.COMPLETE -> Unit

            OnboardingState.ERROR -> {
                InitializationErrorScreen(
                    modifier = modifier,
                    errorMessage = errorMessage,
                    onRetry = {
                        mainViewModel.updateOnboardingState(OnboardingState.CHECKING)
                        checkOnboardingStatus()
                    },
                    onOpenSettings = {
                        onboardingCoordinator.openAppSettings()
                    }
                )
            }
        }
    }
    
    private fun handleOnboardingStateChange(state: OnboardingState) {

        when (state) {
            OnboardingState.COMPLETE -> {
                // App is fully initialized, mesh service is running
                android.util.Log.i("MainActivity", "Onboarding completed - app ready")
            }
            OnboardingState.ERROR -> {
                android.util.Log.e("MainActivity", "Onboarding error state reached")
            }
            else -> {}
        }
    }
    
    private fun checkOnboardingStatus() {
        lifecycleScope.launch {
            // Small delay to show the checking state
            delay(500)
            
            // First check Bluetooth status (always required)
            checkBluetoothAndProceed()
        }
    }
    
    /**
     * Check Bluetooth status and proceed with onboarding flow
     */
    private fun checkBluetoothAndProceed() {
        // Check if user has skipped Bluetooth check for this session
        if (mainViewModel.isBluetoothCheckSkipped.value) {
            checkLocationAndProceed()
            return
        }

        // For first-time users, skip Bluetooth check and go straight to permissions
        // We'll check Bluetooth after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check Bluetooth status first
        bluetoothStatusManager.logBluetoothStatus()
        mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())
        
        val bleRequired = try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        if (!bleRequired) {
            // Skip BLE checks entirely when BLE is disabled in debug settings
            checkLocationAndProceed()
            return
        }
        when (mainViewModel.bluetoothStatus.value) {
            BluetoothStatus.ENABLED -> {
                // Bluetooth is enabled, check location services next
                checkLocationAndProceed()
            }
            BluetoothStatus.DISABLED -> {
                // Show Bluetooth enable screen (should have permissions as existing user)
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                // Device doesn't support Bluetooth
                android.util.Log.e("MainActivity", "Bluetooth not supported")
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
        }
    }
    
    /**
     * Proceed with permission checking 
     */
    private fun proceedWithPermissionCheck() {
        lifecycleScope.launch {
            delay(200) // Small delay for smooth transition

            if (permissionManager.isFirstTimeLaunch()) {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (permissionManager.getUnrequestedOptionalPermissions().isNotEmpty()) {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (permissionManager.areRequiredPermissionsGranted()) {
                if (permissionManager.needsBackgroundLocationPermission() &&
                    !permissionManager.isBackgroundLocationGranted() &&
                    !com.bitchat.android.onboarding.BackgroundLocationPreferenceManager.isSkipped(this@MainActivity)
                ) {
                    mainViewModel.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
                } else {
                    mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                    initializeApp()
                }
            } else {
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
        }
    }
    
    /**
     * Handle Bluetooth enabled callback
     */
    private fun handleBluetoothEnabled() {
        mainViewModel.updateBluetoothLoading(false)
        mainViewModel.updateBluetoothStatus(BluetoothStatus.ENABLED)
        checkLocationAndProceed()
    }

    /**
     * Check Location services status and proceed with onboarding flow
     */
    private fun checkLocationAndProceed() {
        // For first-time users, skip location check and go straight to permissions
        // We'll check location after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check location status
        locationStatusManager.logLocationStatus()
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())
        
        when (mainViewModel.locationStatus.value) {
            LocationStatus.ENABLED -> {
                // Location services enabled, check battery optimization next
                checkBatteryOptimizationAndProceed()
            }
            LocationStatus.DISABLED -> {
                // Show location enable screen (should have permissions as existing user)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            LocationStatus.NOT_AVAILABLE -> {
                // Device doesn't support location services (very unusual)
                Log.e("MainActivity", "Location services not available")
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }

    /**
     * Handle Location enabled callback
     */
    private fun handleLocationEnabled() {
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(LocationStatus.ENABLED)
        // Ensure Wi-Fi Aware starts now that location is enabled
        com.bitchat.android.wifiaware.WifiAwareController.startIfPossible()
        checkBatteryOptimizationAndProceed()
    }

    /**
     * Handle Location disabled callback
     */
    private fun handleLocationDisabled(message: String) {
        Log.w("MainActivity", "Location services disabled or failed: $message")
        mainViewModel.updateLocationLoading(false)
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())

        when {
            mainViewModel.locationStatus.value == LocationStatus.NOT_AVAILABLE -> {
                // Show permanent error for devices without location services
                mainViewModel.updateErrorMessage(message)
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
            }
            else -> {
                // Stay on location check screen for retry
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
            }
        }
    }
    
    /**
     * Handle Bluetooth disabled callback
     */
    private fun handleBluetoothDisabled(message: String) {
        Log.w("MainActivity", "Bluetooth disabled or failed: $message")
        mainViewModel.updateBluetoothLoading(false)
        mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())
        
        when {
            mainViewModel.bluetoothStatus.value == BluetoothStatus.NOT_SUPPORTED -> {
                // Show permanent error for unsupported devices
                mainViewModel.updateErrorMessage(message)
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
            }
            message.contains("Permission") && permissionManager.isFirstTimeLaunch() -> {
                // During first-time onboarding, if Bluetooth enable fails due to permissions,
                // proceed to permission explanation screen where user will grant permissions first
                proceedWithPermissionCheck()
            }
            message.contains("Permission") -> {
                // For existing users, redirect to permission explanation to grant missing permissions
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
            else -> {
                // Stay on Bluetooth check screen for retry
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
            }
        }
    }
    
    private fun handleOnboardingComplete() {
        // After permissions are granted, re-check Bluetooth, Location, and Battery Optimization status
        val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
        val currentLocationStatus = locationStatusManager.checkLocationStatus()
        val currentBatteryOptimizationStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        
        val bleRequired2 = try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        when {
            bleRequired2 && currentBluetoothStatus != BluetoothStatus.ENABLED -> {
                // Bluetooth still disabled, but now we have permissions to enable it
                mainViewModel.updateBluetoothStatus(currentBluetoothStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
            currentLocationStatus != LocationStatus.ENABLED -> {
                // Location services still disabled, but now we have permissions to enable it
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            currentBatteryOptimizationStatus == BatteryOptimizationStatus.ENABLED -> {
                // Battery optimization still enabled, show battery optimization screen
                mainViewModel.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                mainViewModel.updateBatteryOptimizationLoading(false)
            }
            else -> {
                // Both are enabled, proceed to app initialization
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            }
        }
    }
    
    private fun handleOnboardingFailed(message: String) {
        Log.e("MainActivity", "Onboarding failed: $message")
        mainViewModel.updateErrorMessage(message)
        mainViewModel.updateOnboardingState(OnboardingState.ERROR)
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
    
    /**
     * Check Battery Optimization status and proceed with onboarding flow
     */
    private fun checkBatteryOptimizationAndProceed() {
        // For first-time users, skip battery optimization check and go straight to permissions
        // We'll check battery optimization after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        // Check if user has previously skipped battery optimization
        if (BatteryOptimizationPreferenceManager.isSkipped(this)) {
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check battery optimization status
        batteryOptimizationManager.logBatteryOptimizationStatus()
        val currentBatteryOptimizationStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        mainViewModel.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
        
        when (currentBatteryOptimizationStatus) {
            BatteryOptimizationStatus.DISABLED, BatteryOptimizationStatus.NOT_SUPPORTED -> {
                // Battery optimization is disabled or not supported, proceed with permission check
                proceedWithPermissionCheck()
            }
            BatteryOptimizationStatus.ENABLED -> {
                // Show battery optimization disable screen
                mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                mainViewModel.updateBatteryOptimizationLoading(false)
            }
        }
    }
    
    /**
     * Handle Battery Optimization disabled callback
     */
    private fun handleBatteryOptimizationDisabled() {
        mainViewModel.updateBatteryOptimizationLoading(false)
        mainViewModel.updateBatteryOptimizationStatus(BatteryOptimizationStatus.DISABLED)
        proceedWithPermissionCheck()
    }
    
    /**
     * Handle Battery Optimization failed callback
     */
    private fun handleBatteryOptimizationFailed(message: String) {
        android.util.Log.w("MainActivity", "Battery optimization disable failed: $message")
        mainViewModel.updateBatteryOptimizationLoading(false)
        val currentStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        mainViewModel.updateBatteryOptimizationStatus(currentStatus)
        
        // Stay on battery optimization check screen for retry
        mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
    }
    
    private fun initializeApp() {
        lifecycleScope.launch {
            try {
                // Initialize the app with a proper delay to ensure Bluetooth stack is ready
                // This solves the issue where app needs restart to work on first install
                delay(1000) // Give the system time to process permission grants

                // Initialize PoW preferences early in the initialization process
                PoWPreferenceManager.init(this@MainActivity)
                
                // Initialize Location Notes Manager (extracted to separate file)
                com.bitchat.android.nostr.LocationNotesInitializer.initialize(this@MainActivity)
                
                // Ensure all permissions are still granted (user might have revoked in settings)
                if (!permissionManager.areAllPermissionsGranted()) {
                    val missing = permissionManager.getMissingPermissions()
                    Log.w("MainActivity", "Permissions revoked during initialization: $missing")
                    handleOnboardingFailed("Some permissions were revoked. Please grant all permissions to continue.")
                    return@launch
                }

                // Set up unified mesh delegate and start enabled transports
                unifiedMeshService.delegate = chatMeshDelegate
                unifiedMeshService.startServices()
                startMeshForegroundServiceBestEffort()

                // Handle any notification intent
                handleNotificationIntent(intent)
                handleVerificationIntent(intent)

                // Small delay to ensure mesh service is fully initialized
                delay(500)
                Log.i("MainActivity", "App initialization complete")
                mainViewModel.updateOnboardingState(OnboardingState.COMPLETE)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to initialize app", e)
                handleOnboardingFailed("Failed to initialize the app: ${e.message}")
            }
        }
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
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            handleNotificationIntent(intent)
            handleVerificationIntent(intent)
        }
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
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Reattach the session's mesh delegate, detached in onPause
            try { unifiedMeshService.delegate = chatMeshDelegate } catch (_: Exception) { }

            // Check if Bluetooth was disabled while app was backgrounded
            val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
            val bleRequired = try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
            if (bleRequired && currentBluetoothStatus != BluetoothStatus.ENABLED && !mainViewModel.isBluetoothCheckSkipped.value) {
                Log.w("MainActivity", "Bluetooth disabled while app was backgrounded")
                mainViewModel.updateBluetoothStatus(currentBluetoothStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
                return
            }
            
            // Check if location services were disabled while app was backgrounded
            val currentLocationStatus = locationStatusManager.checkLocationStatus()
            if (currentLocationStatus != LocationStatus.ENABLED) {
                Log.w("MainActivity", "Location services disabled while app was backgrounded")
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            } else {
                // If location is enabled, ensure Wi-Fi Aware starts if it was blocked by location earlier
                com.bitchat.android.wifiaware.WifiAwareController.startIfPossible()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Only set background state if app is fully initialized
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Detach UI delegate so the foreground service can own DM notifications while UI is closed
            try { unifiedMeshService.delegate = null } catch (_: Exception) { }
        }
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
                        navigator.goTo(
                            PrivateChatRoute(ContactDirectory.canonicalConversationId(peerID))
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

        // Runs after the stack has been seeded: on a cold start from
        // initializeApp, which only runs once onboarding has put chat at the
        // root, and otherwise from onNewIntent once onboarding is complete.
        navigator.goTo(VerificationRoute(chatState.selectedPrivateChatPeer.value))
        val qr = VerificationService.verifyScannedQR(uri.toString())
        if (qr != null) {
            verificationHandler.beginQRVerification(qr)
        }
    }

    
    override fun onDestroy() {
        super.onDestroy()
        
        try { unregisterReceiver(forceFinishReceiver) } catch (_: Exception) { }
        bluetoothStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) { }
        }
        bluetoothStateReceiver = null
        
        // Cleanup location status manager
        try {
            locationStatusManager.cleanup()
        } catch (e: Exception) {
            Log.w("MainActivity", "Error cleaning up location status manager: ${e.message}")
        }
        
        // Do not stop mesh here; ForegroundService owns lifecycle for background reliability
    }
}
