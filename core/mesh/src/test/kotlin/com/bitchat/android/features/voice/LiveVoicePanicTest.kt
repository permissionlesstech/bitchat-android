package com.bitchat.android.features.voice

import com.bitchat.android.identity.scope.DefaultIdentityEnder
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityComponentBuilder
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityLifetimeEntryPoint
import com.bitchat.android.panic.testPanicMarker
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.TestMeshStores
import dagger.hilt.internal.GeneratedComponent
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A live voice burst that is still arriving when the identity ends must not finish afterwards:
 * its assembly timer would otherwise write the recording and its bubble once the wipe has run.
 * Synthetic frames only; the identities are built and ended by the real holder and ender.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LiveVoicePanicTest {
    private val application = RuntimeEnvironment.getApplication()
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val identities = ArrayList<Identity>()

    private inner class Identity : IdentityComponent, GeneratedComponent, IdentityLifetimeEntryPoint {
        private val teardown = com.bitchat.android.identity.scope.IdentityTeardown()
        private val identityScope = CoroutineScope(SupervisorJob() + dispatcher)
        val store: AppStateStore = TestMeshStores(application).appStateStore
        val liveVoice = LiveVoiceManager(application, store, dispatcher, teardown)

        override fun teardown() = teardown
        override fun scope() = identityScope
    }

    private val holder = IdentityHolder(
        Provider {
            object : IdentityComponentBuilder {
                override fun build(): IdentityComponent = Identity().also { identities += it }
            }
        },
        DefaultIdentityEnder(),
        testPanicMarker(),
    )

    private val incoming = File(application.cacheDir, "files/incoming")
    private val burstID = ByteArray(VoiceBurstPacket.BURST_ID_SIZE) { (it + 1).toByte() }

    private fun packet(sequence: Int, kind: VoiceBurstPacket.Kind): ByteArray =
        VoiceBurstPacket.create(burstID, sequence, kind)!!.encode()

    private fun frames(sequence: Int) =
        packet(sequence, VoiceBurstPacket.Kind.Frames(listOf(ByteArray(24) { sequence.toByte() })))

    @Before
    fun setUp() {
        incoming.deleteRecursively()
    }

    @Test
    fun aBurstCutOffByTheEndOfTheIdentityLeavesNothingBehind() {
        val old = holder.current as Identity
        val live = old.liveVoice
        assertTrue(live.handleFrame(PEER, "synthetic", LiveVoiceScope.PUBLIC_MESH, packet(0, VoiceBurstPacket.Kind.Start(VoiceBurstCodec.AAC_LC_16K_MONO)), NOW))
        assertTrue(live.handleFrame(PEER, "synthetic", LiveVoiceScope.PUBLIC_MESH, frames(1), NOW))
        assertTrue(live.handleFrame(PEER, "synthetic", LiveVoiceScope.PUBLIC_MESH, frames(2), NOW))
        assertEquals("the burst is being assembled", 1, live.liveMessageIDs.value.size)

        val replaced = holder.replace { true }
        assertTrue(replaced.built)
        // Past the gap skip and the assembly's idle timeout, which would finalize the burst.
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()

        val next = holder.current as Identity
        assertFalse(old.scope().isActive)
        assertTrue(next.store.publicMessages.value.isEmpty())
        assertTrue(next.store.privateMessages.value.isEmpty())
        assertTrue(next.liveVoice.liveMessageIDs.value.isEmpty())
        assertTrue("the old store kept no bubble either", old.store.publicMessages.value.isEmpty())
        assertTrue(live.liveMessageIDs.value.isEmpty())
        assertEquals(emptyList<String>(), incoming.listFiles().orEmpty().map { it.name })
    }

    private companion object {
        const val PEER = "0011223344556677"
        const val NOW = 1_700_000_000_000L
    }
}
