package com.bitchat.android.onboarding

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An [OnboardingHost] that counts what onboarding told it. Starting Wi-Fi Aware
 * is appended to [effects], with everything else onboarding did.
 */
class FakeOnboardingHost(private val effects: MutableList<String> = mutableListOf()) : OnboardingHost {
    override val initError = MutableStateFlow<String?>(null)
    var finished = 0
    var retried = 0

    /** What [retry] answers: whether the gate's checks found onboarding still needed. */
    var retryShowsOnboarding = false

    /** Bluetooth turned off while the app was ready, until onboarding takes it. */
    var bluetoothTurnedOff = false

    override fun onboardingFinished() {
        finished++
    }

    override suspend fun retry(): Boolean {
        retried++
        initError.value = null
        return retryShowsOnboarding
    }

    override fun takeBluetoothTurnedOff(): Boolean = bluetoothTurnedOff.also { bluetoothTurnedOff = false }

    override fun locationServicesEnabled() {
        effects += "startWifiAwareIfPossible"
    }
}
