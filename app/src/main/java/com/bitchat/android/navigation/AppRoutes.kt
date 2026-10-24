package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import com.bitchat.android.AppPhase
import kotlinx.serialization.Serializable

/**
 * The host's own destination. Every other route lives in a feature's api module.
 *
 * Onboarding is one destination, not eight. Its steps are driven by permission
 * results and adapter state changes rather than by the user navigating, and the
 * app has never supported going back from one step to the previous one. Giving
 * each step its own entry would invent a history that does not exist.
 */
@Serializable
data object OnboardingRoute : NavKey

/**
 * The destination that should be at the root for a given app phase.
 *
 * CHECKING and INITIALIZING map to chat, matching the behaviour this replaced:
 * the app shows the chat screen while it verifies its own readiness rather than
 * flashing an onboarding step. An init failure shows on onboarding's error step.
 */
fun rootRouteFor(phase: AppPhase): NavKey = when (phase) {
    AppPhase.CHECKING,
    AppPhase.INITIALIZING,
    AppPhase.READY -> ChatRoute

    AppPhase.ONBOARDING,
    AppPhase.INIT_FAILED -> OnboardingRoute
}
