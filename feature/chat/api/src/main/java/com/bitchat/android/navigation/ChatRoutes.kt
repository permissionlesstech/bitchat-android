package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The chat screen, the root of the stack once onboarding is done. */
@Serializable
data object ChatRoute : NavKey

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
