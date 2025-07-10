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
import com.bitchat.android.onboarding.*
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager

class MainActivity : ComponentActivity() {
    
    private lateinit var permissionManager: PermissionManager
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private val chatViewModel: ChatViewModel by viewModels()
    
    // UI state for onboarding flow
    private var onboardingState by mutableStateOf(OnboardingState.CHECKING)
    private var errorMessage by mutableStateOf("")
    
    enum class OnboardingState {
        CHECKING,
        PERMISSION_EXPLANATION,
        PERMISSION_REQUESTING,
        INITIALIZING,
        COMPLETE,
        ERROR
    }
    
    // Add launcher for enabling Bluetooth
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Bluetooth enabled, continue initialization
            initializeApp()
        } else {
            // User denied, show error and allow retry
            handleOnboardingFailed("Bluetooth is required to use this app.")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize permission management
        permissionManager = PermissionManager(this)
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
            
            OnboardingState.PERMISSION_EXPLANATION -> {
                PermissionExplanationScreen(
                    permissionCategories = permissionManager.getCategorizedPermissions(),
                    onContinue = {
                        onboardingState = OnboardingState.PERMISSION_REQUESTING
                        onboardingCoordinator.requestPermissions()
                    },
                    onCancel = {
                        finish()
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
            
            if (permissionManager.isFirstTimeLaunch()) {
                android.util.Log.d("MainActivity", "First time launch, showing permission explanation")
                onboardingState = OnboardingState.PERMISSION_EXPLANATION
            } else if (permissionManager.areAllPermissionsGranted()) {
                android.util.Log.d("MainActivity", "Existing user with permissions, initializing app")
                onboardingState = OnboardingState.INITIALIZING
                ensureBluetoothEnabled()
            } else {
                android.util.Log.d("MainActivity", "Existing user missing permissions, showing explanation")
                onboardingState = OnboardingState.PERMISSION_EXPLANATION
            }
        }
    }
    
    private fun handleOnboardingComplete() {
        android.util.Log.d("MainActivity", "Onboarding completed, checking Bluetooth state")
        onboardingState = OnboardingState.INITIALIZING
        ensureBluetoothEnabled()
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
                
                android.util.Log.d("MainActivity", "Permissions verified, starting mesh service")
                
                // Ensure all permissions are still granted (user might have revoked in settings)
                if (!permissionManager.areAllPermissionsGranted()) {
                    val missing = permissionManager.getMissingPermissions()
                    android.util.Log.w("MainActivity", "Permissions revoked during initialization: $missing")
                    handleOnboardingFailed("Some permissions were revoked. Please grant all permissions to continue.")
                    return@launch
                }
                
                // Initialize chat view model - this will start the mesh service
                chatViewModel.meshService.startServices()
                
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
    
    // Add function to check and request Bluetooth
    private fun ensureBluetoothEnabled() {
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            handleOnboardingFailed("This device does not support Bluetooth.")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            initializeApp()
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Handle notification intents when app is already running
        if (onboardingState == OnboardingState.COMPLETE) {
            intent?.let { handleNotificationIntent(it) }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Only set background state if app is fully initialized
        if (onboardingState == OnboardingState.COMPLETE) {
            chatViewModel.setAppBackgroundState(false)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Only set background state if app is fully initialized
        if (onboardingState == OnboardingState.COMPLETE) {
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
    
    override fun onDestroy() {
        super.onDestroy()
        // Only stop mesh services if they were started
        if (onboardingState == OnboardingState.COMPLETE) {
            try {
                chatViewModel.meshService.stopServices()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Error stopping mesh services in onDestroy: ${e.message}")
            }
        }
    }
}
