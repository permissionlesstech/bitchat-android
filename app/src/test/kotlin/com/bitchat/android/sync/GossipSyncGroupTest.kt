package com.bitchat.android.sync

import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GossipSyncGroupTest {
    @Test
    fun `typed group request serves only opaque group packets`() {
        val fixture = fixture()
        val publicPacket = packet(MessageType.MESSAGE, byteArrayOf(1))
        val groupPacket = packet(MessageType.GROUP_MESSAGE, byteArrayOf(2))
        fixture.manager.onPublicPacketSeen(publicPacket)
        fixture.manager.onPublicPacketSeen(groupPacket)

        fixture.manager.handleRequestSync(
            PEER_ID,
            RequestSyncPacket(
                p = 5,
                m = 1,
                data = ByteArray(0),
                types = SyncTypeFlags.GROUP_MESSAGE
            )
        )

        assertEquals(listOf(MessageType.GROUP_MESSAGE), fixture.delegate.sentTypes)
        fixture.scope.cancel()
    }

    @Test
    fun `initial request combines public and group sync bits`() {
        val fixture = fixture()
        fixture.manager.scheduleInitialSyncToPeer(PEER_ID, delayMs = 0)

        assertTrue(fixture.delegate.requestLatch.await(2, TimeUnit.SECONDS))
        val request = RequestSyncPacket.decode(fixture.delegate.lastRequestPayload!!)!!
        val types = requireNotNull(request.types)
        assertTrue(types.contains(MessageType.ANNOUNCE))
        assertTrue(types.contains(MessageType.MESSAGE))
        assertTrue(types.contains(MessageType.GROUP_MESSAGE))
        fixture.scope.cancel()
    }

    private fun fixture(): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = GossipSyncManager(
            myPeerID = MY_ID,
            scope = scope,
            configProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity() = 100
                override fun gcsMaxBytes() = 400
                override fun gcsTargetFpr() = 0.01
            }
        )
        val delegate = RecordingSyncDelegate()
        manager.delegate = delegate
        return Fixture(scope, manager, delegate)
    }

    private fun packet(type: MessageType, payload: ByteArray) = BitchatPacket(
        type = type.value,
        senderID = ByteArray(8) { 0x11 },
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = 1u,
        payload = payload,
        ttl = 1u
    )

    private data class Fixture(
        val scope: CoroutineScope,
        val manager: GossipSyncManager,
        val delegate: RecordingSyncDelegate
    )

    companion object {
        private const val MY_ID = "0011223344556677"
        private const val PEER_ID = "8899aabbccddeeff"
    }
}

private class RecordingSyncDelegate : GossipSyncManager.Delegate {
    val sentTypes = mutableListOf<MessageType>()
    val requestLatch = CountDownLatch(1)
    @Volatile
    var lastRequestPayload: ByteArray? = null

    override fun sendPacket(packet: BitchatPacket) {
        record(packet)
    }

    override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
        record(packet)
    }

    override fun signPacketForBroadcast(packet: BitchatPacket) = packet

    private fun record(packet: BitchatPacket) {
        val type = MessageType.fromValue(packet.type) ?: return
        if (type == MessageType.REQUEST_SYNC) {
            lastRequestPayload = packet.payload
            requestLatch.countDown()
        } else {
            sentTypes += type
        }
    }
}
