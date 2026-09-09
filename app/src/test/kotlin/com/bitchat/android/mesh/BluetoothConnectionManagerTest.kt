package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BluetoothConnectionManagerTest {
    private lateinit var manager: BluetoothConnectionManager

    @Before
    fun setUp() {
        manager = BluetoothConnectionManager(
            RuntimeEnvironment.getApplication(),
            "0011223344556677"
        )
    }

    @After
    fun tearDown() {
        manager.stopServices()
    }

    @Test
    fun `inactive manager rejects a broadcast instead of reporting it queued`() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            recipientID = null,
            timestamp = 1uL,
            payload = byteArrayOf(1),
            ttl = 7u
        )

        assertFalse(manager.broadcastPacket(RoutedPacket(packet)))
    }
}
