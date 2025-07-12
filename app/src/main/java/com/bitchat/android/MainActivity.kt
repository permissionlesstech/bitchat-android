package com.bitchat.android

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.onboarding.*
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private lateinit var permissionManager: PermissionManager
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private lateinit var bluetoothStatusManager: BluetoothStatusManager
    
    // Core mesh service - managed at app level
    private lateinit var meshService: BluetoothMeshService
    private val chatViewModel: ChatViewModel by viewModels { 
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(application, meshService) as T
            }
        }
    }
    
    // UI state for onboarding flow
    private var onboardingState by mutableStateOf(OnboardingState.CHECKING)
    private var bluetoothStatus by mutableStateOf(BluetoothStatus.ENABLED)
    private var errorMessage by mutableStateOf("")
    private var isBluetoothLoading by mutableStateOf(false)
    
    enum class OnboardingState {
        CHECKING,
        BLUETOOTH_CHECK,
        PERMISSION_EXPLANATION,
        PERMISSION_REQUESTING,
        INITIALIZING,
        COMPLETE,
        ERROR
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize core mesh service first
        meshService = BluetoothMeshService(this)
        
        // Initialize permission management
        permissionManager = PermissionManager(this)
        bluetoothStatusManager = BluetoothStatusManager(
            activity = this,
            context = this,
            onBluetoothEnabled = ::handleBluetoothEnabled,
            onBluetoothDisabled = ::handleBluetoothDisabled
        )
        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager,
            onOnboardingComplete = ::handleOnboardingComplete,
            onOnboardingFailed = ::handleOnboardingFailed
        )
        
        setContent {
            BitchatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingFlowScreen()
                }
            }
        }
        
        // Start the onboarding process
        checkOnboardingStatus()
    }
    
    @Composable
    private fun OnboardingFlowScreen() {
        when (onboardingState) {
            OnboardingState.CHECKING -> {
                InitializingScreen()
            }
            
            OnboardingState.BLUETOOTH_CHECK -> {
                BluetoothCheckScreen(
                    status = bluetoothStatus,
                    onEnableBluetooth = {
                        isBluetoothLoading = true
                        bluetoothStatusManager.requestEnableBluetooth()
                    },
                    onRetry = {
                        checkBluetoothAndProceed()
                    },
                    isLoading = isBluetoothLoading
                )
            }
            
            OnboardingState.PERMISSION_EXPLANATION -> {
                PermissionExplanationScreen(
                    permissionCategories = permissionManager.getCategorizedPermissions(),
                    onContinue = {
                        onboardingState = OnboardingState.PERMISSION_REQUESTING
                        onboardingCoordinator.requestPermissions()
                    }
                )
            }
            
            OnboardingState.PERMISSION_REQUESTING -> {
                InitializingScreen()
            }
            
            OnboardingState.INITIALIZING -> {
                InitializingScreen()
            }
            
            OnboardingState.COMPLETE -> {
                // Set up back navigation handling for the chat screen
                val backCallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        // Let ChatViewModel handle navigation state
                        val handled = chatViewModel.handleBackPressed()
                        if (!handled) {
                            // If ChatViewModel doesn't handle it, disable this callback 
                            // and let the system handle it (which will exit the app)
                            this.isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            this.isEnabled = true
                        }
                    }
                }
                
                // Add the callback - this will be automatically removed when the activity is destroyed
                onBackPressedDispatcher.addCallback(this, backCallback)
                
                ChatScreen(viewModel = chatViewModel)
            }
            
            OnboardingState.ERROR -> {
                InitializationErrorScreen(
                    errorMessage = errorMessage,
                    onRetry = {
                        onboardingState = OnboardingState.CHECKING
                        checkOnboardingStatus()
                    },
                    onOpenSettings = {
                        onboardingCoordinator.openAppSettings()
                    }
                )
            }
        }
    }
    
    private fun checkOnboardingStatus() {
        android.util.Log.d("MainActivity", "Checking onboarding status")
        
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
        android.util.Log.d("MainActivity", "Checking Bluetooth status")
        
        // For first-time users, skip Bluetooth check and go straight to permissions
        // We'll check Bluetooth after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            android.util.Log.d("MainActivity", "First-time launch, skipping Bluetooth check - will check after permissions")
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check Bluetooth status first
        bluetoothStatusManager.logBluetoothStatus()
        bluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
        
        when (bluetoothStatus) {
            BluetoothStatus.ENABLED -> {
                // Bluetooth is enabled, proceed with permission/onboarding check
                proceedWithPermissionCheck()
            }
            BluetoothStatus.DISABLED -> {
                // Show Bluetooth enable screen (should have permissions as existing user)
                android.util.Log.d("MainActivity", "Bluetooth disabled, showing enable screen")
                onboardingState = OnboardingState.BLUETOOTH_CHECK
                isBluetoothLoading = false
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                // Device doesn't support Bluetooth
                android.util.Log.e("MainActivity", "Bluetooth not supported")
                onboardingState = OnboardingState.BLUETOOTH_CHECK
                isBluetoothLoading = false
            }
        }
    }
    
    /**
     * Proceed with permission checking 
     */
    private fun proceedWithPermissionCheck() {
        android.util.Log.d("MainActivity", "Proceeding with permission check")
        
        lifecycleScope.launch {
            delay(200) // Small delay for smooth transition
            
            if (permissionManager.isFirstTimeLaunch()) {
                android.util.Log.d("MainActivity", "First time launch, showing permission explanation")
                onboardingState = OnboardingState.PERMISSION_EXPLANATION
            } else if (permissionManager.areAllPermissionsGranted()) {
                android.util.Log.d("MainActivity", "Existing user with permissions, initializing app")
                onboardingState = OnboardingState.INITIALIZING
                initializeApp()
            } else {
                android.util.Log.d("MainActivity", "Existing user missing permissions, showing explanation")
                onboardingState = OnboardingState.PERMISSION_EXPLANATION
            }
        }
    }
    
    /**
     * Handle Bluetooth enabled callback
     */
    private fun handleBluetoothEnabled() {
        android.util.Log.d("MainActivity", "Bluetooth enabled by user")
        isBluetoothLoading = false
        bluetoothStatus = BluetoothStatus.ENABLED
        proceedWithPermissionCheck()
    }
    
    /**
     * Handle Bluetooth disabled callback
     */
    private fun handleBluetoothDisabled(message: String) {
        android.util.Log.w("MainActivity", "Bluetooth disabled or failed: $message")
        isBluetoothLoading = false
        bluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
        
        when {
            bluetoothStatus == BluetoothStatus.NOT_SUPPORTED -> {
                // Show permanent error for unsupported devices
                errorMessage = message
                onboardingState = OnboardingState.ERROR
            }
            message.contains("Permission") && permissionManager.isFirstTimeLaunch() -> {
                // During first-time onboarding, if Bluetooth enable fails due to permissions,
                // proceed to permission explanation screen where user will grant permissions first
                android.util.Log.d("MainActivity", "Bluetooth enable requires permissions, proceeding to permission explanation")
                proceedWithPermissionCheck()
            }
            message.contains("Permission") -> {
                // For existing users, redirect to permission explanation to grant missing permissions
                android.util.Log.d("MainActivity", "Bluetooth enable requires permissions, showing permission explanation")
                onboardingState = OnboardingState.PERMISSION_EXPLANATION
            }
            else -> {
                // Stay on Bluetooth check screen for retry
                onboardingState = OnboardingState.BLUETOOTH_CHECK
            }
        }
    }
    
    private fun handleOnboardingComplete() {
        android.util.Log.d("MainActivity", "Onboarding completed, checking Bluetooth again before initializing app")
        
        // After permissions are granted, re-check Bluetooth status
        val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
        if (currentBluetoothStatus == BluetoothStatus.ENABLED) {
            // Bluetooth is enabled, proceed to app initialization
            onboardingState = OnboardingState.INITIALIZING
            initializeApp()
        } else {
            // Bluetooth still disabled, but now we have permissions to enable it
            android.util.Log.d("MainActivity", "Permissions granted, but Bluetooth still disabled. Showing Bluetooth enable screen.")
            bluetoothStatus = currentBluetoothStatus
            onboardingState = OnboardingState.BLUETOOTH_CHECK
            isBluetoothLoading = false
        }
    }
    
    private fun handleOnboardingFailed(message: String) {
        android.util.Log.e("MainActivity", "Onboarding failed: $message")
        errorMessage = message
        onboardingState = OnboardingState.ERROR
    }
    
    private fun initializeApp() {
        android.util.Log.d("MainActivity", "Starting app initialization")
        
        lifecycleScope.launch {
            try {
                // Initialize the app with a proper delay to ensure Bluetooth stack is ready
                // This solves the issue where app needs restart to work on first install
                delay(1000) // Give the system time to process permission grants
                
                android.util.Log.d("MainActivity", "Permissions verified, initializing chat system")
                
                // Ensure all permissions are still granted (user might have revoked in settings)
                if (!permissionManager.areAllPermissionsGranted()) {
                    val missing = permissionManager.getMissingPermissions()
                    android.util.Log.w("MainActivity", "Permissions revoked during initialization: $missing")
                    handleOnboardingFailed("Some permissions were revoked. Please grant all permissions to continue.")
                    return@launch
                }
                
                // Set up mesh service delegate and start services
                meshService.delegate = chatViewModel
                meshService.startServices()
                
                android.util.Log.d("MainActivity", "Mesh service started successfully")
                
                // Handle any notification intent
                handleNotificationIntent(intent)
                
                // Small delay to ensure mesh service is fully initialized
                delay(500)
                
                android.util.Log.d("MainActivity", "App initialization complete")
                onboardingState = OnboardingState.COMPLETE
                
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to initialize app", e)
                handleOnboardingFailed("Failed to initialize the app: ${e.message}")
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification intents when app is already running
        if (onboardingState == OnboardingState.COMPLETE) {
            handleNotificationIntent(intent)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Check Bluetooth status on resume and handle accordingly
        if (onboardingState == OnboardingState.COMPLETE) {
            // Set app foreground state
            meshService.connectionManager.setAppBackgroundState(false)
            chatViewModel.setAppBackgroundState(false)
            
            // Check if Bluetooth was disabled while app was backgrounded
            val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
            if (currentBluetoothStatus != BluetoothStatus.ENABLED) {
                android.util.Log.w("MainActivity", "Bluetooth disabled while app was backgrounded")
                bluetoothStatus = currentBluetoothStatus
                onboardingState = OnboardingState.BLUETOOTH_CHECK
                isBluetoothLoading = false
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Only set background state if app is fully initialized
        if (onboardingState == OnboardingState.COMPLETE) {
            // Set app background state
            meshService.connectionManager.setAppBackgroundState(true)
            chatViewModel.setAppBackgroundState(true)
        }
    }
    
    /**
     * Handle intents from notification clicks - open specific private chat
     */
    private fun handleNotificationIntent(intent: Intent) {
        val shouldOpenPrivateChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, 
            false
        )
        
        if (shouldOpenPrivateChat) {
            val peerID = intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_PEER_ID)
            val senderNickname = intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_SENDER_NICKNAME)
            
            if (peerID != null) {
                android.util.Log.d("MainActivity", "Opening private chat with $senderNickname (peerID: $peerID) from notification")
                
                // Open the private chat with this peer
                chatViewModel.startPrivateChat(peerID)
                
                // Clear notifications for this sender since user is now viewing the chat
                chatViewModel.clearNotificationsForSender(peerID)
            }
        }
    }
    
    /**
     * Restart mesh services (for debugging/troubleshooting)
     */
    fun restartMeshServices() {
        if (onboardingState == OnboardingState.COMPLETE) {
            lifecycleScope.launch {
                try {
                    android.util.Log.d("MainActivity", "Restarting mesh services")
                    meshService.stopServices()
                    delay(1000)
                    meshService.startServices()
                    android.util.Log.d("MainActivity", "Mesh services restarted successfully")
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error restarting mesh services: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop mesh services if app was fully initialized
        if (onboardingState == OnboardingState.COMPLETE) {
            try {
                meshService.stopServices()
                android.util.Log.d("MainActivity", "Mesh services stopped successfully")
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Error stopping mesh services in onDestroy: ${e.message}")
            }
        }
    }
}
