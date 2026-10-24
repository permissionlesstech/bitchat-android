package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import com.bitchat.android.AppPhase

/**
 * The destination that should be at the root for a given app phase.
 *
 * For a returning user CHECKING and INITIALIZING map to chat, matching the
 * behaviour this replaced: the app shows the chat screen while it verifies its
 * own readiness rather than flashing an onboarding step. On first launch, and
 * after Try again, CHECKING stays on onboarding, which shows a spinner until its
 * step is known, so chat does not flash before it (F8). An init failure shows
 * on onboarding's error step.
 */
fun rootRouteFor(phase: AppPhase, firstLaunch: Boolean, retrying: Boolean): NavKey = when (phase) {
    AppPhase.CHECKING -> if (firstLaunch || retrying) OnboardingRoute else ChatRoute

    AppPhase.INITIALIZING,
    AppPhase.READY -> ChatRoute

    AppPhase.ONBOARDING,
    AppPhase.INIT_FAILED -> OnboardingRoute
}
