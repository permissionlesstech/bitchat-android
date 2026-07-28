package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FragmentingPacketSenderConfirmedSendTest {
    @Test
    fun confirmedSendStaysPendingWhenExactRouteDisappearsBetweenFragments() = runTest {
        val fragments = listOf(packet(1), packet(2), packet(3))
        val sender = FragmentingPacketSender(
            scope = this,
            fragmentManager = null,
            logTag = "FragmentingPacketSenderTest",
            interFragmentDelayMs = 0
        )
        var preflightCalls = 0
        val sent = mutableListOf<Int>()
        var admitted: Boolean? = null

        sender.sendConfirmed(
            routed = RoutedPacket(
                packet = fragments.first(),
                preparedPackets = fragments
            ),
            description = "exact generation",
            preflight = {
                preflightCalls += 1
                preflightCalls == 1
            },
            sendSingle = {
                sent += it.packet.payload.single().toInt()
                true
            },
            completion = { admitted = it }
        )
        advanceUntilIdle()

        assertEquals(listOf(1), sent)
        assertFalse(admitted ?: true)
    }

    @Test
    fun confirmedSendAcknowledgesOnlyAfterEveryFragmentIsAdmitted() = runTest {
        val fragments = listOf(packet(1), packet(2), packet(3))
        val sender = FragmentingPacketSender(
            scope = this,
            fragmentManager = null,
            logTag = "FragmentingPacketSenderTest",
            interFragmentDelayMs = 0
        )
        val sent = mutableListOf<Int>()
        var admitted: Boolean? = null

        sender.sendConfirmed(
            routed = RoutedPacket(
                packet = fragments.first(),
                preparedPackets = fragments
            ),
            description = "exact generation",
            preflight = { true },
            sendSingle = {
                sent += it.packet.payload.single().toInt()
                true
            },
            completion = { admitted = it }
        )
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), sent)
        assertTrue(admitted == true)
    }

    private fun packet(value: Int) = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 1 },
        recipientID = ByteArray(8) { 2 },
        timestamp = 1u,
        payload = byteArrayOf(value.toByte()),
        ttl = 1u
    )
}
