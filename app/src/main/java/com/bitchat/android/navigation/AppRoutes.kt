package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import com.bitchat.android.onboarding.OnboardingState
import kotlinx.serialization.Serializable

/**
 * Top-level destinations.
 *
 * Onboarding is one destination, not eight. Its steps are driven by permission
 * results and adapter state changes rather than by the user navigating, and the
 * app has never supported going back from one step to the previous one. Giving
 * each step its own entry would invent a history that does not exist.
 */
@Serializable
data object OnboardingRoute : NavKey

@Serializable
data object ChatRoute : NavKey

/**
 * Location notes, a full-screen destination.
 *
 * No argument: the notes belong to the building the device is in, which the
 * screen reads from the location manager when it opens.
 */
@Serializable
data object LocationNotesRoute : NavKey

/**
 * Actions on a user, shown as a sheet over chat.
 *
 * [messageId] names the message that was long-pressed, not the message
 * itself: a key is saved to a Bundle, and message text has no business there.
 * The sheet looks the message up and offers only user actions when it is
 * gone.
 */
@Serializable
data class ChatUserRoute(val nickname: String, val messageId: String?) : NavKey

/**
 * QR verification, a full-screen destination.
 *
 * [peerID] is the private conversation it was opened from, for the unverify
 * action. It travels in the key, so it survives process death along with the
 * stack. Opened from the peer list, it is pushed on top of the list, and Back
 * returns there.
 */
@Serializable
data class VerificationRoute(val peerID: String?) : NavKey

/**
 * Location channels, a full-screen destination: it launches the geohash picker
 * for a result.
 */
@Serializable
data object LocationChannelsRoute : NavKey

/**
 * The network view: conversations, channels and nearby people, a full-screen
 * destination. A private chat opened from it is pushed on top of it, and Back
 * returns to the list.
 */
@Serializable
data object MeshPeerListRoute : NavKey

/**
 * A private conversation, a full-screen destination. Opened from the peer list
 * it is pushed on top of the list; from anywhere else it sits directly on chat.
 *
 * [conversationID] is the id the conversation was opened with, canonicalized
 * at the time. It is not rewritten when the conversation later resolves to a
 * contact: the screen's ViewModel canonicalizes it again whenever the
 * selection moves.
 */
@Serializable
data class PrivateChatRoute(val conversationID: String) : NavKey

/**
 * Session security and fingerprint for a private conversation, a sheet
 * stacked over it.
 */
@Serializable
data class SecurityVerificationRoute(val conversationID: String) : NavKey

/**
 * The destination that should be at the root for a given onboarding state.
 *
 * CHECKING and INITIALIZING map to chat, matching the behaviour this replaced:
 * the app shows the chat screen while it verifies its own readiness rather than
 * flashing an onboarding step.
 */
fun rootRouteFor(state: OnboardingState): NavKey = when (state) {
    OnboardingState.CHECKING,
    OnboardingState.INITIALIZING,
    OnboardingState.COMPLETE -> ChatRoute

    else -> OnboardingRoute
}
