package com.bitchat.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BatteryOptimizationScreen
import com.bitchat.android.onboarding.BackgroundLocation
import com.bitchat.android.onboarding.BackgroundLocationPermissionScreen
import com.bitchat.android.onboarding.InitializationErrorScreen
import com.bitchat.android.onboarding.InitializingScreen
import com.bitchat.android.onboarding.LocationCheckScreen
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingAction
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.OnboardingFlow
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionExplanationScreen
import com.bitchat.android.onboarding.OnboardingSystem
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.onboarding.SystemEvent
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
import com.bitchat.android.nostr.PoWPreferenceManager
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.bitchat.android.navigation.AppNavigator
import com.bitchat.android.navigation.BitchatNavDisplay
import com.bitchat.android.navigation.ChatRoute
import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.appEntries
import com.bitchat.android.navigation.MeshPeerListRoute
import com.bitchat.android.navigation.SheetSceneStrategy
import com.bitchat.android.navigation.VerificationRoute
import com.bitchat.android.navigation.openPrivateChat
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
    private lateinit var onboardingFlow: OnboardingFlow
    
    // Core mesh service - provided by the foreground service holder
    private lateinit var meshService: BluetoothMeshService
    private lateinit var unifiedMeshService: MeshService
    private val mainViewModel: MainViewModel by viewModels()
    private var pendingMeshForegroundServiceStart = false

    // Held by ActivityRetainedComponent, so the back stack outlives configuration
    // changes without being rebuilt here.
    @Inject
    lateinit var navigator: AppNavigator

    // Bluetooth, location services, battery optimization, permissions and the
    // stored onboarding choices, read in one place.
    @Inject
    lateinit var deviceReadiness: DeviceReadiness

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

    // Every feature's destinations, contributed through Hilt; the app's own
    // are in appEntries.
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

        // Initialize permission management
        permissionManager = PermissionManager(this, deviceReadiness)
        // Start the foreground service when allowed, then get mesh instances from the holder.
        startMeshForegroundServiceBestEffort()
        meshService = com.bitchat.android.service.MeshServiceHolder.getOrCreate(applicationContext)
        unifiedMeshService = com.bitchat.android.service.MeshServiceHolder.getUnifiedOrCreate(applicationContext)
        // Expose BLE mesh to Wi‑Fi Aware controller for cross-transport relays - DEPRECATED
        // Bridging is now handled by TransportBridgeService automatically

        onboardingFlow = OnboardingFlow(lifecycleScope, mainViewModel, onboardingSystem)

        // The managers and the coordinator register their launchers here, in this
        // order, and forward every result to the flow.
        bluetoothStatusManager = BluetoothStatusManager(
            activity = this,
            context = this,
            onBluetoothEnabled = {
                onboardingFlow.dispatch(OnboardingAction.BluetoothResult(enabled = true))
            },
            onBluetoothDisabled = { message ->
                onboardingFlow.dispatch(bluetoothDisabledAction(message))
            }
        )
        locationStatusManager = LocationStatusManager(
            activity = this,
            context = this,
            onLocationEnabled = {
                onboardingFlow.dispatch(OnboardingAction.LocationResult(enabled = true))
            },
            onLocationDisabled = { message ->
                onboardingFlow.dispatch(locationDisabledAction(message))
            }
        )
        batteryOptimizationManager = BatteryOptimizationManager(
            activity = this,
            context = this,
            onBatteryOptimizationDisabled = {
                onboardingFlow.dispatch(OnboardingAction.BatteryResult)
            },
            onBatteryOptimizationFailed = { message ->
                onboardingFlow.dispatch(OnboardingAction.BatteryLaunchFailed(message))
            }
        )
        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            onPermissionResults = { results ->
                onboardingFlow.dispatch(OnboardingAction.PermissionResults(results))
            },
            onBackgroundLocationResult = { granted ->
                onboardingFlow.dispatch(OnboardingAction.BackgroundResult(granted))
            }
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
                    val entries = appEntries {
                        OnboardingFlowScreen(onboardingModifier)
                    }

                    // NavDisplay rejects an empty back stack and the effect above does
                    // not run until after this composition, so the host waits a frame
                    // for it. Gate on the stack itself: root is non-null immediately,
                    // so gating on that would compose with nothing to show.
                    if (navigator.backStack.isNotEmpty()) {
                        BitchatNavDisplay(
                            navigator = navigator,
                            entryInstallers = entryInstallers + setOf(entries),
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
        
        // The adapter as the Activity starts. Without one the device cannot run
        // bitchat at all. One already on re-runs the Bluetooth check if that screen
        // is showing, which only a recreation finds.
        when (deviceReadiness.snapshot().bluetooth) {
            BluetoothStatus.NOT_SUPPORTED -> onboardingFlow.dispatch(OnboardingAction.BluetoothNotSupportedAtStart)
            BluetoothStatus.ENABLED -> onboardingFlow.dispatch(OnboardingAction.BluetoothTurnedOn)
            BluetoothStatus.DISABLED -> Unit
        }

        // Watches the radios for the whole Activity, not for as long as some
        // composable happens to stay on screen. Scoping it to a destination is what
        // let a Bluetooth switch-off go unnoticed once chat became its own route.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                deviceReadiness.systemEvents.collect { event ->
                    when (event) {
                        // Both, in this order: re-run the check if its screen is
                        // showing, then take the Bluetooth-enabled path.
                        SystemEvent.BluetoothOn -> {
                            onboardingFlow.dispatch(OnboardingAction.BluetoothTurnedOn)
                            onboardingFlow.dispatch(OnboardingAction.BluetoothResult(enabled = true))
                        }
                        SystemEvent.BluetoothOff -> onboardingFlow.dispatch(OnboardingAction.BluetoothTurnedOff)
                        is SystemEvent.LocationChanged ->
                            onboardingFlow.dispatch(OnboardingAction.LocationChanged(enabled = event.enabled))
                    }
                }
            }
        }
        
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
        
        // Starts onboarding only from the initial CHECKING state, so a
        // configuration change does not restart it
        onboardingFlow.dispatch(OnboardingAction.Start)
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
                        onboardingFlow.dispatch(OnboardingAction.BluetoothEnableTapped)
                    },
                    onRetry = {
                        onboardingFlow.dispatch(OnboardingAction.BluetoothCheckAgainTapped)
                    },
                    onSkip = {
                        onboardingFlow.dispatch(OnboardingAction.BluetoothSkipTapped)
                    },
                    isLoading = isBluetoothLoading
                )
            }
            
            OnboardingState.LOCATION_CHECK -> {
                LocationCheckScreen(
                    modifier = modifier,
                    status = locationStatus,
                    onEnableLocation = {
                        onboardingFlow.dispatch(OnboardingAction.LocationOpenSettingsTapped)
                    },
                    onRetry = {
                        onboardingFlow.dispatch(OnboardingAction.LocationCheckAgainTapped)
                    },
                    isLoading = isLocationLoading
                )
            }
            
            OnboardingState.BATTERY_OPTIMIZATION_CHECK -> {
                BatteryOptimizationScreen(
                    modifier = modifier,
                    status = batteryOptimizationStatus,
                    onDisableBatteryOptimization = {
                        onboardingFlow.dispatch(OnboardingAction.BatteryDisableTapped)
                    },
                    onRetry = {
                        onboardingFlow.dispatch(OnboardingAction.BatteryCheckAgainTapped)
                    },
                    onSkip = {
                        onboardingFlow.dispatch(OnboardingAction.BatterySkipTapped)
                    },
                    isLoading = isBatteryOptimizationLoading
                )
            }
            
            OnboardingState.PERMISSION_EXPLANATION -> {
                PermissionExplanationScreen(
                    modifier = modifier,
                    permissionCategories = permissionManager.getCategorizedPermissions(),
                    onContinue = {
                        onboardingFlow.dispatch(OnboardingAction.GrantPermissionsTapped)
                    }
                )
            }

            OnboardingState.BACKGROUND_LOCATION_EXPLANATION -> {
                BackgroundLocationPermissionScreen(
                    modifier = modifier,
                    onContinue = {
                        onboardingFlow.dispatch(OnboardingAction.BackgroundGrantTapped)
                    },
                    onRetry = {
                        onboardingFlow.dispatch(OnboardingAction.BackgroundCheckAgainTapped)
                    },
                    onSkip = {
                        onboardingFlow.dispatch(OnboardingAction.BackgroundSkipTapped)
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
                        onboardingFlow.dispatch(OnboardingAction.TryAgainTapped)
                    },
                    onOpenSettings = {
                        onboardingFlow.dispatch(OnboardingAction.OpenSettingsTapped)
                    }
                )
            }
        }
    }
    
    // What the onboarding flow reads and does. The reads and the stored choices
    // come from DeviceReadiness; the dialogs from this Activity's managers and
    // coordinator.
    private val onboardingSystem = object : OnboardingSystem {
        private fun readiness() = deviceReadiness.snapshot()

        override fun bluetoothStatus() = readiness().bluetooth
        override fun locationStatus() = readiness().location
        override fun batteryStatus() = readiness().batteryOptimization

        override fun isFirstTimeLaunch() = readiness().firstLaunch
        override fun markOnboardingComplete() = deviceReadiness.markOnboardingComplete()

        override fun requiredPermissions() = deviceReadiness.requiredPermissions()
        override fun missingPermissions() = readiness().missingPermissions
        override fun unrequestedOptionalPermissions() = readiness().unrequestedOptionalPermissions
        override fun markOptionalPermissionsRequested(p: Collection<String>) =
            deviceReadiness.markOptionalPermissionsRequested(p)
        override fun isPermissionGranted(p: String) = deviceReadiness.isPermissionGranted(p)
        override fun areRequiredPermissionsGranted() = readiness().requiredPermissionsGranted

        override fun needsBackgroundLocation() = readiness().backgroundLocation != BackgroundLocation.NOT_NEEDED
        override fun isBackgroundLocationGranted() = readiness().backgroundLocation != BackgroundLocation.NEEDED
        override fun backgroundLocationPermission() = deviceReadiness.backgroundLocationPermission()
        override fun isBackgroundLocationSkipped() = readiness().backgroundLocationSkipped
        override fun setBackgroundLocationSkipped() = deviceReadiness.setBackgroundLocationSkipped()

        override fun isBatterySkipped() = readiness().batterySkipped
        override fun setBatterySkipped() = deviceReadiness.setBatterySkipped()
        override fun bleRequired() = readiness().bleRequired

        override fun launchBluetoothEnable() = bluetoothStatusManager.requestEnableBluetooth()
        override fun launchLocationSettings() = locationStatusManager.requestEnableLocation()
        override fun launchBatteryOptimization() = batteryOptimizationManager.requestDisableBatteryOptimization()
        override fun launchPermissions(p: List<String>) = onboardingCoordinator.launchPermissions(p)
        override fun launchBackgroundLocation(p: String) = onboardingCoordinator.launchBackgroundLocation(p)
        override fun openAppSettings() = onboardingCoordinator.openAppSettings()

        override fun startWifiAwareIfPossible() = WifiAwareController.startIfPossible()
        override fun initializeApp() = this@MainActivity.initializeApp()

        override fun logBluetoothDiagnostics() = bluetoothStatusManager.logBluetoothStatus()
        override fun logLocationDiagnostics() = locationStatusManager.logLocationStatus()
        override fun logBatteryDiagnostics() = batteryOptimizationManager.logBatteryOptimizationStatus()
        override fun logPermissionDiagnostics() = permissionManager.logPermissionStatus()
    }

    // The managers report a declined dialog and a failed launch through one
    // callback; the message tells which it was. Any other message is a failed
    // launch, which the flow handles with that message.
    private fun bluetoothDisabledAction(message: String): OnboardingAction = when (message) {
        OnboardingFlow.BLUETOOTH_DECLINED_MESSAGE -> OnboardingAction.BluetoothResult(enabled = false)
        else -> OnboardingAction.BluetoothLaunchFailed(message)
    }

    private fun locationDisabledAction(message: String): OnboardingAction = when (message) {
        OnboardingFlow.LOCATION_DECLINED_MESSAGE -> OnboardingAction.LocationResult(enabled = false)
        else -> OnboardingAction.LocationLaunchFailed(message)
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
                val readiness = deviceReadiness.snapshot()
                if (!readiness.requiredPermissionsGranted) {
                    val missing = readiness.missingPermissions
                    Log.w("MainActivity", "Permissions revoked during initialization: $missing")
                    onboardingFlow.onInitFailed("Some permissions were revoked. Please grant all permissions to continue.")
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
                onboardingFlow.onInitComplete()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to initialize app", e)
                onboardingFlow.onInitFailed("Failed to initialize the app: ${e.message}")
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

            // Back to a check screen if Bluetooth or location services went off
            // while the app was in the background
            onboardingFlow.dispatch(OnboardingAction.ResumedWhileComplete)
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

        // Runs after the stack has been seeded: on a cold start from
        // initializeApp, which only runs once onboarding has put chat at the
        // root, and otherwise from onNewIntent once onboarding is complete.
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
