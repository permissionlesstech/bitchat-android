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
}
