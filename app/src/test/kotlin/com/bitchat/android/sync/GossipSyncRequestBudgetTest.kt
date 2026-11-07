package com.bitchat.android.sync

import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * REQUEST_SYNC is a public packet handled straight off the wire, so an unauthenticated neighbor
 * decides how often we decode a filter, hash every stored packet, and transmit what it claims to
 * lack. These cover the budget that bounds that without changing what an honest peer receives.
 */
class GossipSyncRequestBudgetTest {

    private companion object {
        const val CAPACITY = 5
        const val MY_PEER = "aabbccddeeff0011"
        const val REQUESTER = "1122334455667788"
    }

    /** A filter that claims to hold nothing, so every stored packet counts as missing. */
    private val emptyFilter = RequestSyncPacket(p = 1, m = 1L, data = ByteArray(0))

    private var clock = 1_000_000L
    private val sentTo = mutableListOf<Pair<String, BitchatPacket>>()

    private lateinit var manager: GossipSyncManager

    private val delegate = object : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) = Unit
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            sentTo.add(peerID to packet)
        }

        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket = packet
    }

    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = CAPACITY
        override fun gcsMaxBytes(): Int = 400
        override fun gcsTargetFpr(): Double = 0.01
    }

    @Before
    fun setUp() {
        sentTo.clear()
        clock = 1_000_000L
    }

    private fun TestScope.prepare() {
        manager = GossipSyncManager(
            myPeerID = MY_PEER,
            scope = this,
            configProvider = config,
            nowMs = { clock }
        )
        manager.delegate = delegate
    }

    private fun TestScope.request(
        peerID: String,
        filter: RequestSyncPacket = emptyFilter,
        ingressLinkID: String? = null
    ) {
        manager.handleRequestSync(peerID, filter, ingressLinkID)
        advanceUntilIdle()
    }

    private fun broadcastMessage(body: String): BitchatPacket = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x11 },
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = clock.toULong(),
        payload = body.toByteArray(),
        ttl = 3u
    )

    private fun storeMessages(count: Int) {
        repeat(count) { manager.onPublicPacketSeen(broadcastMessage("m$it")) }
    }

    @Test
    fun `an honest peer still receives every packet it is missing`() = runTest {
        prepare()
        storeMessages(CAPACITY)

        request(REQUESTER)

        assertEquals(CAPACITY, sentTo.size)
        assertTrue(sentTo.all { it.first == REQUESTER })
    }

    @Test
    fun `back to back requests stop being serviced after the burst allowance`() = runTest {
        prepare()
        storeMessages(CAPACITY)

        // No time passes between these, so nothing refills.
        repeat(100) { request(REQUESTER) }

        // Before the fix every one of the 100 requests replayed all CAPACITY packets.
        assertEquals(CAPACITY, sentTo.size)
    }

    @Test
    fun `a flood cannot pull more packets than the refill window allows`() = runTest {
        prepare()
        storeMessages(CAPACITY)

        // Hammer for a simulated minute, far faster than any honest peer syncs.
        repeat(600) {
            clock += 100L
            request(REQUESTER)
        }

        // 60s of hammering earns two windows' worth of packets, not 600 replays.
        val windows = 60_000L / AppConstants.Sync.RESPONSE_REFILL_WINDOW_MS
        assertTrue(
            "sent ${sentTo.size} packets, expected at most ${CAPACITY * (windows + 1)}",
            sentTo.size <= CAPACITY * (windows + 1)
        )
        assertTrue("honest sync must still make progress", sentTo.isNotEmpty())
    }

    @Test
    fun `the budget refills so a peer can keep syncing`() = runTest {
        prepare()
        storeMessages(CAPACITY)

        request(REQUESTER)
        assertEquals(CAPACITY, sentTo.size)

        sentTo.clear()
        clock += AppConstants.Sync.RESPONSE_REFILL_WINDOW_MS
        request(REQUESTER)

        assertEquals(CAPACITY, sentTo.size)
    }

    @Test
    fun `one flooding peer does not consume another peer's budget`() = runTest {
        prepare()
        storeMessages(CAPACITY)
        repeat(50) { request(REQUESTER) }
        sentTo.clear()

        request("99aabbccddeeff00")

        assertEquals(CAPACITY, sentTo.size)
    }

    @Test
    fun `requester bookkeeping stays bounded when peer ids are spoofed`() = runTest {
        prepare()
        storeMessages(1)

        repeat(AppConstants.Sync.MAX_TRACKED_REQUESTERS * 4) { i ->
            request("%016x".format(i))
        }

        assertEquals(
            AppConstants.Sync.MAX_TRACKED_REQUESTERS,
            manager.trackedRequesterCount()
        )
    }

    @Test
    fun `rotating the sender id does not mint a fresh budget on one link`() = runTest {
        prepare()
        storeMessages(CAPACITY)

        // REQUEST_SYNC is not in the set SecurityManager.verifyPacketSignature authenticates, so
        // the sender ID is free for a flooder to rotate. Cycling more IDs than the LRU tracks
        // would hand out a full allowance per request if the budget were keyed on it.
        repeat(AppConstants.Sync.MAX_TRACKED_REQUESTERS * 2) { i ->
            request("%016x".format(i), ingressLinkID = "link-1")
        }

        assertEquals(CAPACITY, sentTo.size)
        assertEquals(1, manager.trackedRequesterCount())
    }

    @Test
    fun `separate links keep separate budgets`() = runTest {
        prepare()
        storeMessages(CAPACITY)

        request(REQUESTER, ingressLinkID = "link-1")
        repeat(50) { request(REQUESTER, ingressLinkID = "link-1") }
        sentTo.clear()

        // A different physical link is a different neighbour and must not inherit the throttle.
        request(REQUESTER, ingressLinkID = "link-2")

        assertEquals(CAPACITY, sentTo.size)
    }

    @Test
    fun `a backwards clock jump does not hand out free budget`() = runTest {
        prepare()
        storeMessages(CAPACITY)

        repeat(100) {
            clock -= 5_000L
            request(REQUESTER)
        }

        assertEquals(CAPACITY, sentTo.size)
    }

    @Test
    fun `clearing sync state also clears the budgets`() = runTest {
        prepare()
        storeMessages(CAPACITY)
        request(REQUESTER)

        manager.clear()

        assertEquals(0, manager.trackedRequesterCount())
    }
}
