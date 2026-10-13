package com.bitchat.android.features.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceVisibilityTest {

    private val peerA = "0123456789abcdef"
    private val peerB = "fedcba9876543210"
    private val visibility = LiveVoiceVisibility()

    @Test
    fun aShownPrivateChatIsVisibleAndHidesThePublicMesh() {
        visibility.showPublicMesh()
        visibility.showDirectMessage(peerA)

        assertTrue(visibility.isVisible(LiveVoiceScope.DIRECT_MESSAGE, peerA))
        assertFalse(visibility.isVisible(LiveVoiceScope.DIRECT_MESSAGE, peerB))
        assertFalse(visibility.isVisible(LiveVoiceScope.PUBLIC_MESH, peerB))
    }

    @Test
    fun leavingThePrivateChatOnScreenClearsIt() {
        visibility.showDirectMessage(peerA)

        visibility.leaveDirectMessage(peerA)

        assertFalse(visibility.isVisible(LiveVoiceScope.DIRECT_MESSAGE, peerA))
        assertFalse(visibility.isVisible(LiveVoiceScope.PUBLIC_MESH, peerA))
    }

    /** A chat replaced by another is disposed after the new one has shown itself. */
    @Test
    fun aLateLeaveOfAReplacedChatKeepsTheNewOneVisible() {
        visibility.showDirectMessage(peerA)
        visibility.showDirectMessage(peerB)

        visibility.leaveDirectMessage(peerA)

        assertTrue(visibility.isVisible(LiveVoiceScope.DIRECT_MESSAGE, peerB))
    }

    /** Back to the timeline: chat may show the mesh before the private chat is disposed. */
    @Test
    fun aLateLeaveAfterThePublicMeshIsShownKeepsTheMeshVisible() {
        visibility.showDirectMessage(peerA)
        visibility.showPublicMesh()

        visibility.leaveDirectMessage(peerA)

        assertTrue(visibility.isVisible(LiveVoiceScope.PUBLIC_MESH, peerA))
    }

    @Test
    fun clearHidesEverything() {
        visibility.showPublicMesh()

        visibility.clear()

        assertFalse(visibility.isVisible(LiveVoiceScope.PUBLIC_MESH, peerA))
    }
}
