package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.panic.testPanicMarker
import com.bitchat.android.services.TestMeshStores
import dagger.Lazy
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A panic releases the background runtime's relay subscriptions before the wipe, and the identity
 * being wiped must not be subscribed for again while the runtime's jobs live on until its end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NostrBackgroundRuntimeReleaseTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val application: Application = RuntimeEnvironment.getApplication()
    private val relayManager = mock<NostrRelayManager>()
    private val teardown = IdentityTeardown()
    // Synthetic bookmarked channel near the coordinate origin; no location provider is used.
    private val channel = GeohashChannel(GeohashChannelLevel.CITY, "s0000")
    private val identity = NostrIdentity.fromSeed("background-release-fixture")

    @After
    fun tearDown() {
        teardown.runAll()
    }

    private fun startedRuntime(): NostrBackgroundRuntime {
        val locationChannels = mock<LocationChannelManager> {
            on { selectedChannel } doReturn MutableStateFlow<ChannelID>(ChannelID.Location(channel))
            on { availableChannels } doReturn MutableStateFlow(emptyList())
        }
        val store = TestMeshStores(application).appStateStore
        val holder = IdentityHolder({ error("no identity is built here") }, { emptyList() }, testPanicMarker())
        val powerManager = PowerManager(application, holder, Provider { store }, Dispatchers.Default)
        val runtime = NostrBackgroundRuntime(
            ioDispatcher = dispatcher,
            locationChannelsSource = Lazy { locationChannels },
            relayManager = Lazy { relayManager },
            liveLocationGate = LiveLocationPrivacyGate(),
            nostrIdentity = mock {
                on { getCurrentNostrIdentity(any()) } doReturn identity
                on { deriveIdentity(any(), any()) } doReturn identity
            },
            geohashAliases = GeohashAliasRegistry(),
            geohashConversations = GeohashConversationRegistry(),
            powerManagerSource = Lazy { powerManager },
            teardown = teardown,
        )
        runtime.initialize(application) { _, _ -> mock() }
        scheduler.runCurrent()
        return runtime
    }

    private fun relayCalls(): List<String> =
        Mockito.mockingDetails(relayManager).invocations.map { it.method.name }

    @Test
    fun `releasing unsubscribes the runtime's subscriptions and subscribes nothing again`() {
        val runtime = startedRuntime()
        assertTrue("the fixture subscribes before the release", relayCalls().any { it.startsWith("subscribe") })
        Mockito.clearInvocations(relayManager)

        runtime.releaseSubscriptions()
        scheduler.advanceTimeBy(10 * 60_000L)
        scheduler.runCurrent()

        assertEquals(listOf("unsubscribeOwner"), relayCalls())
        Mockito.verify(relayManager).unsubscribeOwner(NostrRelayManager.OWNER_BACKGROUND)
    }
}
