package com.bitchat.android.service

import android.os.Build
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class TransportBridgeServiceTest {
    private val targetId = "test-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        TransportBridgeService.unregister(targetId)
    }

    @Test
    fun `bridged prepared plan retains exact payloads with decremented TTL`() {
        var captured: RoutedPacket? = null
        TransportBridgeService.register(
            targetId,
            object : TransportBridgeService.TransportLayer {
                override fun send(packet: RoutedPacket) {
                    captured = packet
                }

                override suspend fun sendAndReport(packet: RoutedPacket): Boolean {
                    captured = packet
                    return true
                }
            }
        )
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = ByteArray(8) { 1 },
            recipientID = ByteArray(8) { 2 },
            timestamp = System.nanoTime().toULong(),
            payload = byteArrayOf(3, 4, 5),
            signature = ByteArray(64) { 6 },
            ttl = 7u
        )
        val prepared = listOf(
            packet.copy(type = MessageType.FRAGMENT.value, payload = byteArrayOf(10)),
            packet.copy(type = MessageType.FRAGMENT.value, payload = byteArrayOf(11))
        )

        TransportBridgeService.broadcast(
            sourceId = "source-${UUID.randomUUID()}",
            packet = RoutedPacket(packet, preparedPackets = prepared)
        )

        val forwarded = captured
        assertNotNull(forwarded)
        assertEquals(6u.toUByte(), forwarded!!.packet.ttl)
        assertEquals(2, forwarded.preparedPackets?.size)
        forwarded.preparedPackets!!.zip(prepared).forEach { (actual, original) ->
            assertEquals(6u.toUByte(), actual.ttl)
            assertTrue(actual.payload.contentEquals(original.payload))
            assertEquals(original.type, actual.type)
        }
    }

    @Test
    fun `rejected bridge send remains eligible after transport reconnects`() = runTest {
        var transportConnected = false
        var attempts = 0
        TransportBridgeService.register(
            targetId,
            object : TransportBridgeService.TransportLayer {
                override fun send(packet: RoutedPacket) = Unit

                override suspend fun sendAndReport(packet: RoutedPacket): Boolean {
                    attempts += 1
                    return transportConnected
                }
            }
        )
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = ByteArray(8) { 1 },
            recipientID = ByteArray(8) { 2 },
            timestamp = System.nanoTime().toULong(),
            payload = byteArrayOf(3, 4, 5),
            signature = ByteArray(64) { 6 },
            ttl = 7u
        )
        val sourceId = "source-${UUID.randomUUID()}"

        assertFalse(
            TransportBridgeService.broadcastAndReport(sourceId, RoutedPacket(packet))
        )
        transportConnected = true
        assertTrue(
            TransportBridgeService.broadcastAndReport(sourceId, RoutedPacket(packet))
        )
        assertEquals(2, attempts)
    }
}
