package com.bitchat.android.onboarding

import kotlinx.coroutines.flow.StateFlow

/** What onboarding tells the app, and the app's init failure it shows. */
interface OnboardingHost {

    /** Why the app's initialization failed, or null. Onboarding shows it as its error step. */
    val initError: StateFlow<String?>

    /** Onboarding is satisfied: the app initializes. */
    fun onboardingFinished()

    /** Try again on the error step: back to checking, then the checks. */
    fun retry()

    /**
     * True once after Bluetooth turning off while the app was ready sent it back
     * to onboarding. Onboarding then opens on the Bluetooth step whatever the
     * session skip and the debug switch say, as it always has (T12).
     */
    fun takeBluetoothTurnedOff(): Boolean

    /** Location services came on during onboarding: the app starts Wi-Fi Aware if it can. */
    fun locationServicesEnabled()
}
