package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.GeohashParticipantStore
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.services.TestMeshStores
import com.bitchat.android.spam.SpamFilter
import dagger.Lazy
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

/**
 * A geohash send outlives the session it started in, and not the identity it signs for. A panic's
 * release of the relays subscribes nothing again for the identity being wiped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeohashSessionSendTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val application: Application = RuntimeEnvironment.getApplication()
    private val stores = TestMeshStores(application)
    private val relayManager = mock<NostrRelayManager>()
    private val backgroundRuntime = mock<NostrBackgroundRuntime> { on { participants } doReturn GeohashParticipantStore() }
    // Synthetic channel near the coordinate origin; no location provider or relay is used.
    private val channel = GeohashChannel(GeohashChannelLevel.CITY, "s0000")
    private val identity = NostrIdentity.fromSeed("geohash-send-fixture")
    private val event = NostrEvent(pubkey = "synthetic", createdAt = 0, kind = NostrKind.EPHEMERAL_EVENT, tags = emptyList(), content = "synthetic")
    private lateinit var stateScope: CoroutineScope
    private lateinit var identityScope: CoroutineScope
    private lateinit var session: GeohashSession

    @Before
    fun setUp() {
        ShadowLog.clear()
        Dispatchers.setMain(dispatcher)
        stateScope = CoroutineScope(SupervisorJob() + dispatcher)
        identityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val locationChannels = mock<LocationChannelManager> {
            on { selectedChannel } doReturn MutableStateFlow<ChannelID>(ChannelID.Mesh)
            on { teleported } doReturn MutableStateFlow(false)
            on { canUseSelectedLocationChannel(any()) } doReturn true
        }
        session = GeohashSession(
            application = application,
            state = ChatState(stateScope, stores.appStateStore),
            messageManager = mock(),
            dataManager = mock(),
            notificationManager = mock(),
            nostrBackgroundRuntime = backgroundRuntime,
            liveLocationPrivacyGate = mock(),
            powPreferences = mock<PoWPreferenceManager> {
                on { getCurrentSettings() } doReturn PoWPreferenceManager(application).getCurrentSettings()
            },
            geohashAliases = GeohashAliasRegistry(),
            spamFilter = SpamFilter(),
            geohashConversations = GeohashConversationRegistry(),
            nostrIdentity = mock<NostrIdentityBridge> {
                on { deriveIdentity(any(), any()) } doReturn identity
            },
            locationChannelManagerSource = Provider { locationChannels },
            relayManagerSource = Lazy { relayManager },
            locationNotesManagerSource = Provider { mock() },
            identityScope = identityScope,
            // Signing or mining in flight: it suspends until virtual time passes.
            createGeohashEvent = { _, _, _, _, _ ->
                delay(1_000)
                event
            },
        )
        session.initialize()
        scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        stateScope.cancel()
        identityScope.cancel()
        Dispatchers.resetMain()
    }

    private fun verifyPublished(times: Int) = verify(relayManager, times(times))
        .sendEventToGeohash(eq(event), eq(channel.geohash), any(), any(), anyOrNull())

    @Test
    fun `a geohash send in flight when the session ends still publishes`() {
        session.sendGeohashMessage("synthetic", channel, "self", "anon0000")
        scheduler.runCurrent()
        verifyPublished(0)

        session.shutdownUiSubscriptions()
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()

        verifyPublished(1)
    }

    @Test
    fun `a geohash send in flight when the identity ends does not publish`() {
        session.sendGeohashMessage("synthetic", channel, "self", "anon0000")
        scheduler.runCurrent()

        identityScope.cancel()
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()

        verify(relayManager, never()).sendEventToGeohash(any(), any(), any(), any(), anyOrNull())
        val failures = ShadowLog.getLogsForTag("GeohashSession").filter { "Failed to send" in it.msg }
        assertEquals("a cancelled send is not a failed one", emptyList<Any>(), failures)
    }

    @Test
    fun `ending the session stops everything else on its scope`() {
        session.shutdownUiSubscriptions()

        val field = GeohashSession::class.java.getDeclaredField("scope").apply { isAccessible = true }
        assertTrue((field.get(session) as CoroutineScope).coroutineContext[kotlinx.coroutines.Job]!!.isCancelled)
    }

    @Test
    fun `a geohash send in flight when a panic starts does not publish`() {
        session.sendGeohashMessage("synthetic", channel, "self", "anon0000")
        scheduler.runCurrent()

        // The panic's start, with the identity still alive through the slow wipe that follows.
        session.cancelSendsInFlight()
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()

        verifyPublished(0)
    }

    @Test
    fun `a send after the in-flight ones were cancelled still works`() {
        session.cancelSendsInFlight()

        session.sendGeohashMessage("synthetic", channel, "self", "anon0000")
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()

        verifyPublished(1)
    }

    @Test
    fun `ending the sampling after the session ended sends nothing to the relays`() {
        session.beginGeohashSampling(emptyList(), listOf(channel.geohash))
        // The foreground is what subscribes, and a JVM test's process never reaches it by itself.
        session.onStart(mock())
        scheduler.runCurrent()
        session.shutdownUiSubscriptions()
        org.mockito.Mockito.clearInvocations(relayManager)

        session.endGeohashSampling()
        scheduler.runCurrent()

        verifyNoInteractions(relayManager)
    }

    @Test
    fun `the panic's release of the relays only unsubscribes, even as time passes`() {
        session.beginGeohashSampling(emptyList(), listOf(channel.geohash))
        session.onStart(mock())
        scheduler.runCurrent()
        val subscribed = org.mockito.Mockito.mockingDetails(relayManager).invocations.map { it.method.name }
        assertTrue("the fixture subscribes before the release", subscribed.any { it.startsWith("subscribe") })
        org.mockito.Mockito.clearInvocations(relayManager)

        session.panicReleaseRelays()
        scheduler.advanceTimeBy(10 * 60_000L)
        scheduler.runCurrent()

        val calls = org.mockito.Mockito.mockingDetails(relayManager).invocations.map { it.method.name }
        assertEquals(listOf("unsubscribeOwner", "clearAllOnPanic"), calls)
        verify(backgroundRuntime).releaseSubscriptions()
    }
}
