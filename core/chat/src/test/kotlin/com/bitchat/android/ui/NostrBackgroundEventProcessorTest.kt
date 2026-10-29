package com.bitchat.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.nostr.GeohashAliasRegistry
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
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NostrBackgroundEventProcessorTest {
    private val stores = TestMeshStores(RuntimeEnvironment.getApplication())
    private lateinit var scope: CoroutineScope
    private val spamFilter = SpamFilter()

    @Before
    fun setUp() {
        // This test floods one sender to measure throughput; that is exactly what the spam
        // filter hides, so it is covered by SpamFilterTest instead.
        spamFilter.enabled = false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `cold start processes more events than the removed handoff queue capacity`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val processor = NostrBackgroundEventProcessor(
            application,
            scope,
            GeohashParticipantStore(),
            PoWPreferenceManager(application),
            nostrTransport = { mock<NostrTransport>() },
            appStateStore = stores.appStateStore,
            contactDirectory = stores.contactDirectory,
            favorites = Lazy { stores.favorites },
            peerFingerprints = PeerFingerprintManager(),
            geohashAliases = GeohashAliasRegistry,
            geohashConversations = GeohashConversationRegistry,
            nostrIdentity = NostrIdentityBridge(stores.favorites),
            spamFilter = spamFilter,
            seenMessageStore = Lazy(lazy { SeenMessageStore(application, stores.appStateStore) }::value),
        )
        val identity = NostrIdentity.generate()
        val expectedIds = mutableSetOf<String>()

        repeat(300) { index ->
            val event = NostrEvent(
                pubkey = identity.publicKeyHex,
                createdAt = index + 1,
                kind = NostrKind.EPHEMERAL_EVENT,
                tags = listOf(listOf("g", "u4pruy")),
                content = "message-$index"
            ).sign(identity.privateKeyHex)
            expectedIds += event.id

            processor.onGeohashMessage(
                event = event,
                geohash = "u4pruy"
            )
        }

        withTimeout(5_000) {
            while (stores.appStateStore.channelMessages.value["geo:u4pruy"].orEmpty().size < 300) {
                kotlinx.coroutines.yield()
            }
        }

        assertEquals(
            expectedIds,
            stores.appStateStore.channelMessages.value["geo:u4pruy"].orEmpty().map { it.id }.toSet()
        )
    }
}
