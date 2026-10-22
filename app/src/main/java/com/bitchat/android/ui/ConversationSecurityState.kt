package com.bitchat.android.ui

/**
 * Resolves the Noise session shown for a private conversation.
 *
 * Persistent conversations use a canonical contact ID, while live Noise sessions are keyed by
 * the currently connected mesh peer ID. Prefer that live identity and retain the conversation ID
 * as a fallback for peers whose IDs are already identical.
 */
internal fun resolveConversationSessionState(
    conversationID: String,
    activeMeshPeerID: String?,
    peerSessionStates: Map<String, String>
): String? {
    return activeMeshPeerID?.let(peerSessionStates::get)
        ?: peerSessionStates[conversationID]
}
