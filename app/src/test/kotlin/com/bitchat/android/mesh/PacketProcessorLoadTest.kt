package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class PacketProcessorLoadTest {
    @Test
    fun `overload bounds queued packets and preserves accepted order`() = runTest {
        val handled = mutableListOf<ULong>()
        val delegate = recordingDelegate(handled)
        val processor = PacketProcessor("1111111111111111", StandardTestDispatcher(testScheduler))
        processor.delegate = delegate
        try {
            repeat(2_000) { processor.processPacket(packet(it)) }
            runCurrent()

            assertEquals((0 until PacketProcessor.QUEUE_CAPACITY).map { it.toULong() }, handled)
            processor.processPacket(packet(2_001))
            runCurrent()
            assertEquals(2_001uL, handled.last())
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `global payload budget is released after processing and shutdown rejects ingress`() = runTest {
        val handled = mutableListOf<ULong>()
        val processor = PacketProcessor("1111111111111111", StandardTestDispatcher(testScheduler))
        processor.delegate = recordingDelegate(handled)
        try {
            val large = ByteArray(PacketProcessor.MAX_PENDING_PAYLOAD_BYTES / 2)
            repeat(4) { processor.processPacket(packet(it, large, "synthetic-peer-$it")) }
            runCurrent()
            assertEquals(listOf(0uL, 1uL), handled.sorted())

            processor.processPacket(packet(4, large))
            runCurrent()
            assertEquals(3, handled.size)
            processor.shutdown()
            processor.processPacket(packet(5))
            runCurrent()
            assertEquals(3, handled.size)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `one handler failure does not kill the queue or cause concurrent fallback`() = runTest {
        val handled = mutableListOf<ULong>()
        val processor = PacketProcessor("1111111111111111", StandardTestDispatcher(testScheduler))
        val delegate = recordingDelegate(handled)
        doAnswer { invocation ->
            val routed = invocation.getArgument<RoutedPacket>(0)
            if (routed.packet.timestamp == 0uL) error("synthetic handler failure")
            handled += routed.packet.timestamp
            Unit
        }.whenever(delegate).handleMessage(any())
        processor.delegate = delegate
        try {
            processor.processPacket(packet(0))
            processor.processPacket(packet(1))
            runCurrent()
            assertEquals(listOf(1uL), handled)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `suspended handler retains its payload budget and serializes subsequent packets`() = runTest {
        val handled = mutableListOf<ULong>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val base = recordingDelegate(handled)
        val processor = PacketProcessor("1111111111111111", StandardTestDispatcher(testScheduler))
        processor.delegate = object : PacketProcessorDelegate by base {
            override suspend fun handleMessage(routed: RoutedPacket) {
                if (routed.packet.timestamp == 0uL) gate.await()
                handled += routed.packet.timestamp
            }
        }
        try {
            val large = ByteArray(PacketProcessor.MAX_PENDING_PAYLOAD_BYTES / 2)
            processor.processPacket(packet(0, large))
            runCurrent()
            processor.processPacket(packet(1, large))
            processor.processPacket(packet(2, large, "synthetic-other-peer"))
            runCurrent()
            assertEquals(emptyList<ULong>(), handled)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(0uL, 1uL), handled)
            processor.processPacket(packet(3, large))
            runCurrent()
            assertEquals(listOf(0uL, 1uL, 3uL), handled)
        } finally {
            processor.shutdown()
        }
    }

    private suspend fun recordingDelegate(handled: MutableList<ULong>): PacketProcessorDelegate {
        val delegate = mock<PacketProcessorDelegate>()
        whenever(delegate.validatePacketSecurity(any(), any())).thenReturn(true)
        doAnswer { invocation ->
            handled += invocation.getArgument<RoutedPacket>(0).packet.timestamp
            Unit
        }.whenever(delegate).handleMessage(any())
        return delegate
    }

    private fun packet(
        sequence: Int,
        payload: ByteArray = byteArrayOf(1),
        peerID: String = "2222222222222222",
    ) = RoutedPacket(
        BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = ByteArray(8) { 2 },
            timestamp = sequence.toULong(),
            payload = payload,
            ttl = 0u,
        ),
        peerID = peerID,
    )
}
