package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSecurityStateTest {
    @Test
    fun `active mesh peer session wins for canonical conversation`() {
        val result = resolveConversationSessionState(
            conversationID = "contact-alice",
            activeMeshPeerID = "mesh-alice",
            peerSessionStates = mapOf(
                "contact-alice" to "uninitialized",
                "mesh-alice" to "established"
            )
        )

        assertEquals("established", result)
    }

    @Test
    fun `conversation session is fallback when live identity has no state`() {
        val result = resolveConversationSessionState(
            conversationID = "contact-alice",
            activeMeshPeerID = "mesh-alice",
            peerSessionStates = mapOf("contact-alice" to "handshaking")
        )

        assertEquals("handshaking", result)
    }

    @Test
    fun `conversation session resolves when mesh and conversation IDs are identical`() {
        val result = resolveConversationSessionState(
            conversationID = "mesh-alice",
            activeMeshPeerID = "mesh-alice",
            peerSessionStates = mapOf("mesh-alice" to "established")
        )

        assertEquals("established", result)
    }
}
