package com.bitchat.android.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.bitchat.android.MainViewModel
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.OnboardingFlowScreen


@Composable
fun BitchatNavDisplay(
    backStack: NavBackStack<NavKey>,
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
    onCheckOnboardingStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<BitchatRoute.Main> {
                OnboardingFlowScreen(
                    modifier = Modifier.fillMaxSize(),
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
                    onCheckOnboardingStatus = onCheckOnboardingStatus,
                )
            }
        },
    )
}