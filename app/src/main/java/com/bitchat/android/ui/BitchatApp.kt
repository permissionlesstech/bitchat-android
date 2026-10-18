package com.bitchat.android.ui

import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.MainViewModel
import com.bitchat.android.onboarding.*
import com.bitchat.android.ui.theme.BitchatTheme

@Composable
internal fun BitchatApp(
    mainViewModel: MainViewModel,
    chatViewModel: ChatViewModel,
    permissionManager: PermissionManager,
    onboardingCoordinator: OnboardingCoordinator,
    bluetoothStatusManager: BluetoothStatusManager,
    locationStatusManager: LocationStatusManager,
    batteryOptimizationManager: BatteryOptimizationManager,
    onCheckBluetoothAndProceed: () -> Unit,
    onCheckLocationAndProceed: () -> Unit,
    onCheckBatteryOptimizationAndProceed: () -> Unit,
    onProceedWithPermissionCheck: () -> Unit,
    onCheckOnboardingStatus: () -> Unit
) {
    BitchatTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            OnboardingFlowScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                mainViewModel = mainViewModel,
                chatViewModel = chatViewModel,
                permissionManager = permissionManager,
                onboardingCoordinator = onboardingCoordinator,
                bluetoothStatusManager = bluetoothStatusManager,
                locationStatusManager = locationStatusManager,
                batteryOptimizationManager = batteryOptimizationManager,
                onCheckBluetoothAndProceed = onCheckBluetoothAndProceed,
                onCheckLocationAndProceed = onCheckLocationAndProceed,
                onCheckBatteryOptimizationAndProceed = onCheckBatteryOptimizationAndProceed,
                onProceedWithPermissionCheck = onProceedWithPermissionCheck,
                onCheckOnboardingStatus = onCheckOnboardingStatus
            )
        }
    }
}

@Composable
private fun OnboardingFlowScreen(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel,
    chatViewModel: ChatViewModel,
    permissionManager: PermissionManager,
    onboardingCoordinator: OnboardingCoordinator,
    bluetoothStatusManager: BluetoothStatusManager,
    locationStatusManager: LocationStatusManager,
    batteryOptimizationManager: BatteryOptimizationManager,
    onCheckBluetoothAndProceed: () -> Unit,
    onCheckLocationAndProceed: () -> Unit,
    onCheckBatteryOptimizationAndProceed: () -> Unit,
    onProceedWithPermissionCheck: () -> Unit,
    onCheckOnboardingStatus: () -> Unit
) {
    val context = LocalContext.current
    val onboardingState by mainViewModel.onboardingState.collectAsState()
    val bluetoothStatus by mainViewModel.bluetoothStatus.collectAsState()
    val locationStatus by mainViewModel.locationStatus.collectAsState()
    val batteryOptimizationStatus by mainViewModel.batteryOptimizationStatus.collectAsState()
    val errorMessage by mainViewModel.errorMessage.collectAsState()
    val isBluetoothLoading by mainViewModel.isBluetoothLoading.collectAsState()
    val isLocationLoading by mainViewModel.isLocationLoading.collectAsState()
    val isBatteryOptimizationLoading by mainViewModel.isBatteryOptimizationLoading.collectAsState()

    DisposableEffect(context, bluetoothStatusManager) {
        val receiver = bluetoothStatusManager.monitorBluetoothState(
            context = context,
            bluetoothStatusManager = bluetoothStatusManager,
            onBluetoothStateChanged = { status ->
                if (status == BluetoothStatus.ENABLED && onboardingState == OnboardingState.BLUETOOTH_CHECK) {
                    onCheckBluetoothAndProceed()
                }
            }
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalStateException) {
                Log.w("BluetoothStatusUI", "Receiver was not registered")
            }
        }
    }

    val onBackPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
                    onCheckBluetoothAndProceed()
                },
                onSkip = {
                    mainViewModel.skipBluetoothCheck()
                    onCheckLocationAndProceed()
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
                    onCheckLocationAndProceed()
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
                    onCheckBatteryOptimizationAndProceed()
                },
                onSkip = {
                    // Skip battery optimization and proceed
                    onProceedWithPermissionCheck()
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

        OnboardingState.CHECKING, OnboardingState.INITIALIZING, OnboardingState.COMPLETE -> {
            // Set up back navigation handling for the chat screen
            if (onBackPressedDispatcherOwner != null) {
                DisposableEffect(onBackPressedDispatcherOwner, lifecycleOwner) {
                    val backCallback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            // Let ChatViewModel handle navigation state
                            val handled = chatViewModel.handleBackPressed()
                            if (!handled) {
                                // If ChatViewModel doesn't handle it, disable this callback
                                // and let the system handle it (which will exit the app)
                                this.isEnabled = false
                                onBackPressedDispatcherOwner.onBackPressedDispatcher.onBackPressed()
                                this.isEnabled = true
                            }
                        }
                    }

                    onBackPressedDispatcherOwner.onBackPressedDispatcher.addCallback(lifecycleOwner, backCallback)

                    onDispose {
                        backCallback.remove()
                    }
                }
            }
            ChatScreen(viewModel = chatViewModel)
        }

        OnboardingState.ERROR -> {
            InitializationErrorScreen(
                modifier = modifier,
                errorMessage = errorMessage,
                onRetry = {
                    mainViewModel.updateOnboardingState(OnboardingState.CHECKING)
                    onCheckOnboardingStatus()
                },
                onOpenSettings = {
                    onboardingCoordinator.openAppSettings()
                }
            )
        }
    }
}
