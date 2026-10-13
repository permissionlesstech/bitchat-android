package com.bitchat.android.features.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's screens claim and release the visible conversation as they come
 * and go, in whatever order a transition runs them.
 */
class LiveVoiceVisibilityTest {

    private val peerA = "0123456789abcdef"
    private val peerB = "fedcba9876543210"
    private val visibility = LiveVoiceVisibility()
    private val chat = Any()
    private val privateChatA = Any()
    private val privateChatB = Any()

    private fun meshVisible() = visibility.isVisible(LiveVoiceScope.PUBLIC_MESH, peerA)
    private fun dmVisible(peerID: String) =
        visibility.isVisible(LiveVoiceScope.DIRECT_MESSAGE, peerID)

    @Test
    fun aClaimedPrivateChatIsVisibleAndHidesThePublicMesh() {
        visibility.claimPublicMesh(chat)
        visibility.claimDirectMessage(privateChatA, peerA)

        assertTrue(dmVisible(peerA))
        assertFalse(dmVisible(peerB))
        assertFalse(meshVisible())
    }

    /** Chat to the peer list: chat leaves composition and nothing is on screen. */
    @Test
    fun chatLeavingForTheListLeavesNothingVisible() {
        visibility.claimPublicMesh(chat)

        visibility.release(chat)

        assertFalse(meshVisible())
    }

    /** A chat begun with /msg is not read while only the list shows. */
    @Test
    fun aMsgChatInChatIsNotVisibleOnceChatLeaves() {
        visibility.claimDirectMessage(chat, peerA)

        visibility.release(chat)

        assertFalse(dmVisible(peerA))
    }

    /**
     * Chat to a private chat: the private chat claims as it slides in, chat
     * re-claims the same conversation on its way out, then is disposed.
     */
    @Test
    fun chatDisposedAfterThePrivateChatClaimedLeavesTheChatVisible() {
        visibility.claimPublicMesh(chat)
        visibility.claimDirectMessage(privateChatA, peerA)
        visibility.claimDirectMessage(chat, peerA)

        visibility.release(chat)

        assertTrue(dmVisible(peerA))
    }

    /** Back from a private chat: chat comes back, then the private chat goes. */
    @Test
    fun backToChatEndsOnTheMeshWhicheverLeavesFirst() {
        visibility.claimDirectMessage(privateChatA, peerA)
        // Chat re-enters while the selection still trails the closed chat.
        visibility.claimDirectMessage(chat, peerA)
        visibility.release(privateChatA)
        // The chat ends, the selection clears, and chat claims the mesh.
        visibility.claimPublicMesh(chat)

        assertTrue(meshVisible())
        assertFalse(dmVisible(peerA))
    }

    @Test
    fun aPrivateChatLeavingBeforeChatReturnsFallsBackToNothingThenTheMesh() {
        visibility.claimDirectMessage(privateChatA, peerA)
        visibility.release(privateChatA)
        assertFalse(dmVisible(peerA))

        visibility.claimPublicMesh(chat)

        assertTrue(meshVisible())
    }

    /** A private chat over the peer list, then Back to the list. */
    @Test
    fun aPrivateChatPoppedToTheListLeavesNothingVisible() {
        visibility.claimDirectMessage(privateChatA, peerA)

        visibility.release(privateChatA)

        assertFalse(dmVisible(peerA))
        assertFalse(meshVisible())
    }

    /** A replaced chat is released after the new one has claimed. */
    @Test
    fun aLateReleaseOfAReplacedChatKeepsTheNewOneVisible() {
        visibility.claimDirectMessage(privateChatA, peerA)
        visibility.claimDirectMessage(privateChatB, peerB)

        visibility.release(privateChatA)

        assertTrue(dmVisible(peerB))
        assertFalse(dmVisible(peerA))
    }

    /** The single-slot calls the watch uses still replace and clear. */
    @Test
    fun unownedCallsReplaceAndClear() {
        visibility.showPublicMesh()
        visibility.showDirectMessage(peerA)
        assertTrue(dmVisible(peerA))
        assertFalse(meshVisible())

        visibility.clear()

        assertFalse(dmVisible(peerA))
        assertFalse(meshVisible())
    }
}
