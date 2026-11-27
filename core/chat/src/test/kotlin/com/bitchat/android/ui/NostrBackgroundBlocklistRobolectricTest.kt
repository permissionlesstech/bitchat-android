package com.bitchat.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.GeohashParticipantStore
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.TestMeshStores
import com.bitchat.android.spam.SpamFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * The background processor reloads the block lists only when they change (upstream #1096), so a
 * change made through another DataManager, such as the UI's, must still reach it on the next event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrBackgroundBlocklistRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val stores = TestMeshStores(app)
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nowMs = System.currentTimeMillis()
    private val participantStore = GeohashParticipantStore()
    private val blockedSender = NostrIdentity.fromPrivateKey("11".repeat(32))
    private val otherSender = NostrIdentity.fromPrivateKey("22".repeat(32))
    private val uiDataManager = DataManager(app)

    @After
    fun tearDown() {
        scope.cancel()
        uiDataManager.removeGeohashBlockedUser(blockedSender.publicKeyHex)
    }

    @Test
    fun `unblocking through another DataManager lifts the block on the next event`() {
        uiDataManager.addGeohashBlockedUser(blockedSender.publicKeyHex)
        val processor = newProcessor()

        val blockedBefore = chat(blockedSender, 0)
        val other = chat(otherSender, 0)
        processor.onGeohashMessage(blockedBefore, GEOHASH)
        processor.onGeohashMessage(other, GEOHASH)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(other.id in deliveredIds())
        assertFalse(blockedBefore.id in deliveredIds())

        uiDataManager.removeGeohashBlockedUser(blockedSender.publicKeyHex)
        val unblocked = chat(blockedSender, 1)
        processor.onGeohashMessage(unblocked, GEOHASH)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(unblocked.id in deliveredIds())
    }

    @Test
    fun `blocking through another DataManager after start drops the sender's next event`() {
        val processor = newProcessor()

        uiDataManager.addGeohashBlockedUser(blockedSender.publicKeyHex)
        val blocked = chat(blockedSender, 0)
        processor.onGeohashMessage(blocked, GEOHASH)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(blocked.id in deliveredIds())
    }

    private fun deliveredIds() =
        stores.appStateStore.channelMessages.value["geo:$GEOHASH"].orEmpty().map { it.id }.toSet()

    private fun chat(sender: NostrIdentity, offsetSec: Int) = NostrEvent(
        pubkey = sender.publicKeyHex,
        createdAt = (nowMs / 1_000L).toInt() + offsetSec,
        kind = NostrKind.EPHEMERAL_EVENT,
        tags = listOf(listOf("g", GEOHASH)),
        content = "synthetic message $offsetSec"
    ).sign(sender.privateKeyHex)

    private fun newProcessor() = NostrBackgroundEventProcessor(
        app,
        scope,
        participantStore,
        PoWPreferenceManager(app),
        nostrTransport = mock<NostrTransport>(),
        appStateStore = stores.appStateStore,
        contactDirectory = stores.contactDirectory,
        favorites = stores.favorites,
        peerFingerprints = PeerFingerprintManager(),
        geohashAliases = stores.geohashAliases,
        geohashConversations = GeohashConversationRegistry(),
        nostrIdentity = NostrIdentityBridge(stores.favorites),
        spamFilter = SpamFilter(),
        seenMessageStore = SeenMessageStore(app, stores.appStateStore),
        processingDispatcher = dispatcher,
        signatureVerificationDispatcher = dispatcher,
    )

    private companion object {
        // Synthetic geohash, not a real location of anyone's.
        const val GEOHASH = "u4pruy"
    }
}
