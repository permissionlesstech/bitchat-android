package com.bitchat.watch.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearNotificationPolicyTest {

    @Test
    fun `system private messages never notify`() {
        assertFalse(
            WearNotificationPolicy.shouldNotifyPrivateMessage(
                senderPeerID = "peer-a",
                senderIsSystem = true,
                appInForeground = false,
                openDmPeer = null
            )
        )
    }

    @Test
    fun `visible matching dm suppresses notification`() {
        assertFalse(
            WearNotificationPolicy.shouldNotifyPrivateMessage(
                senderPeerID = "peer-a",
                senderIsSystem = false,
                appInForeground = true,
                openDmPeer = "peer-a"
            )
        )
    }

    @Test
    fun `backgrounded matching dm still notifies`() {
        assertTrue(
            WearNotificationPolicy.shouldNotifyPrivateMessage(
                senderPeerID = "peer-a",
                senderIsSystem = false,
                appInForeground = false,
                openDmPeer = "peer-a"
            )
        )
    }

    @Test
    fun `different visible dm still notifies`() {
        assertTrue(
            WearNotificationPolicy.shouldNotifyPrivateMessage(
                senderPeerID = "peer-a",
                senderIsSystem = false,
                appInForeground = true,
                openDmPeer = "peer-b"
            )
        )
    }

    @Test
    fun `peer count is distinct`() {
        assertEquals(2, WearNotificationPolicy.activePeerCount(listOf("peer-a", "peer-a", "peer-b")))
    }
}
