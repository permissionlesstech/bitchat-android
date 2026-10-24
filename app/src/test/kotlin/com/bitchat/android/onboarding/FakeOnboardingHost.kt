package com.bitchat.android.onboarding

import kotlinx.coroutines.flow.MutableStateFlow

/** An [OnboardingHost] that counts what onboarding told it. */
class FakeOnboardingHost : OnboardingHost {
    override val initError = MutableStateFlow<String?>(null)
    var finished = 0
    var retried = 0

    override fun onboardingFinished() {
        finished++
    }

    override fun retry() {
        retried++
    }
}
