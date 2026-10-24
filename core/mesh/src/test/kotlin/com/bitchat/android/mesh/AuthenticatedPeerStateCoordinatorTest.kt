package com.bitchat.android.mesh

import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoisePeerIdentity
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedPeerStateCoordinatorTest {
    private class MemoryStore : AuthenticatedPeerStateStore {
        val states = mutableMapOf<String, AuthenticatedPeerState>()
        val pins = mutableSetOf<String>()

        override fun load(fingerprint: String): AuthenticatedPeerState? = states[fingerprint]
        override fun persist(
            fingerprint: String,
            state: AuthenticatedPeerState,
            onCommitted: () -> Unit
        ): Boolean {
            states[fingerprint] = state
            if (state.capabilities.contains(PeerCapabilities.PRIVATE_MEDIA)) pins += fingerprint
            onCommitted()
            return true
        }
        override fun isPrivateMediaPinned(fingerprint: String): Boolean = fingerprint in pins
    }

    private val remoteStatic = ByteArray(32) { 0x33 }
    private val peerID = NoisePeerIdentity.derivePeerID(remoteStatic)!!
    private val firstSession = AuthenticatedNoiseSession(
        remoteStatic,
        ByteArray(32) { 0x71 }
    )
    private val secondSession = AuthenticatedNoiseSession(
        remoteStatic,
        ByteArray(32) { 0x72 }
    )
    private val localState = AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, ByteArray(32) { 0x44 })
    private val remoteState = AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, ByteArray(32) { 0x55 })

    @Test
    fun `every generation emits and first valid proof echoes exactly once`() {
        val store = MemoryStore()
        val sent = CopyOnWriteArrayList<AuthenticatedPeerState>()
        val applied = CopyOnWriteArrayList<AuthenticatedPeerState>()
        var activeSession = firstSession
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            authenticatedSessionProvider = { activeSession },
            withAuthenticatedSession = { _, expected, action ->
                if (expected == activeSession) action() else false
            },
            store = store,
            localStateProvider = { localState },
            applyAuthenticatedState = { _, key, state ->
                assertArrayEquals(remoteStatic, key)
                applied += state
            },
            sendState = { _, state, _ -> sent.add(state) },
            onResolution = {},
            proofTimeoutMs = 5_000
        )

        coordinator.onSessionAuthenticated(peerID, remoteStatic, firstSession.sessionToken)
        assertEquals(AuthenticatedPeerStateStatus.Awaiting, coordinator.status(peerID, firstSession))
        assertEquals(1, sent.size)
        assertTrue(coordinator.receive(peerID, remoteState, firstSession))
        assertEquals(2, sent.size)
        assertTrue(coordinator.receive(peerID, remoteState, firstSession))
        assertEquals("Repeated proof must not echo again", 2, sent.size)
        assertEquals(1, applied.size)

        val different = AuthenticatedPeerState(PeerCapabilities.NONE, ByteArray(32) { 0x66 })
        assertFalse(
            "A generation cannot replace its first proof",
            coordinator.receive(peerID, different, firstSession)
        )

        activeSession = secondSession
        // Policy can observe the crypto swap before its completion callback. It must adopt the
        // new token as Awaiting instead of reusing gen-N Proven state.
        assertEquals(AuthenticatedPeerStateStatus.Awaiting, coordinator.status(peerID, secondSession))
        assertEquals(3, sent.size)
        coordinator.onSessionAuthenticated(peerID, remoteStatic, secondSession.sessionToken)
        assertEquals(3, sent.size)
        assertEquals(AuthenticatedPeerStateStatus.Awaiting, coordinator.status(peerID, secondSession))
        assertFalse(coordinator.receive(peerID, remoteState, firstSession))
        assertTrue(coordinator.receive(peerID, different, secondSession))
        assertEquals(4, sent.size)
        assertEquals(2, applied.size)
    }

    @Test
    fun `live generation is adopted once and watchdog is never reset by policy reads`() = runBlocking {
        val sent = CopyOnWriteArrayList<AuthenticatedPeerState>()
        val resolutions = CopyOnWriteArrayList<String>()
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = this,
            authenticatedSessionProvider = { firstSession },
            withAuthenticatedSession = { _, expected, action ->
                if (expected == firstSession) action() else false
            },
            store = MemoryStore(),
            localStateProvider = { localState },
            applyAuthenticatedState = { _, _, _ -> },
            sendState = { _, state, _ -> sent += state; true },
            onResolution = resolutions::add,
            proofTimeoutMs = 20
        )

        assertEquals(AuthenticatedPeerStateStatus.Awaiting, coordinator.status(peerID, firstSession))
        delay(10)
        assertEquals(AuthenticatedPeerStateStatus.Awaiting, coordinator.status(peerID, firstSession))
        delay(25)

        assertEquals(AuthenticatedPeerStateStatus.TimedOut, coordinator.status(peerID, firstSession))
        assertEquals(1, sent.size)
        assertEquals(listOf(peerID), resolutions)
    }

    @Test
    fun `delayed old completion cannot replace a newer tracked generation`() {
        val sentTokens = CopyOnWriteArrayList<ByteArray>()
        var activeSession = secondSession
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            authenticatedSessionProvider = { activeSession },
            withAuthenticatedSession = { _, expected, action ->
                if (expected == activeSession) action() else false
            },
            store = MemoryStore(),
            localStateProvider = { localState },
            applyAuthenticatedState = { _, _, _ -> },
            sendState = { _, _, session -> sentTokens += session.sessionToken; true },
            onResolution = {},
            proofTimeoutMs = 5_000
        )

        coordinator.onSessionAuthenticated(peerID, remoteStatic, secondSession.sessionToken)
        coordinator.onSessionAuthenticated(peerID, remoteStatic, firstSession.sessionToken)

        assertEquals(AuthenticatedPeerStateStatus.Awaiting, coordinator.status(peerID, secondSession))
        assertEquals(1, sentTokens.size)
        assertArrayEquals(secondSession.sessionToken, sentTokens.single())
    }

    @Test
    fun `watchdog resolves no-proof generation without trusting persisted prior state`() = runBlocking {
        val store = MemoryStore()
        store.states[fingerprint(remoteStatic)] = remoteState
        val resolutions = CopyOnWriteArrayList<String>()
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = this,
            authenticatedSessionProvider = { firstSession },
            withAuthenticatedSession = { _, expected, action ->
                if (expected == firstSession) action() else false
            },
            store = store,
            localStateProvider = { localState },
            applyAuthenticatedState = { _, _, _ -> },
            sendState = { _, _, _ -> true },
            onResolution = resolutions::add,
            proofTimeoutMs = 20
        )

        coordinator.onSessionAuthenticated(peerID, remoteStatic, firstSession.sessionToken)
        delay(50)

        assertEquals(AuthenticatedPeerStateStatus.TimedOut, coordinator.status(peerID, firstSession))
        assertEquals(listOf(peerID), resolutions)
    }

    @Test
    fun `copied-static preannounce Ed key is replaced by authenticated proof`() {
        val peerManager = PeerManager()
        val attackerEd = ByteArray(32) { 0x11 }
        val victimEd = ByteArray(32) { 0x22 }
        peerManager.updatePeerInfoFromVerifiedAnnouncement(
            peerID,
            "attacker-name",
            remoteStatic,
            attackerEd,
            true,
            PeerCapabilities.PRIVATE_MEDIA
        )
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            authenticatedSessionProvider = { firstSession },
            withAuthenticatedSession = { _, expected, action ->
                if (expected == firstSession) action() else false
            },
            store = MemoryStore(),
            localStateProvider = { localState },
            applyAuthenticatedState = peerManager::applyAuthenticatedPeerState,
            sendState = { _, _, _ -> true },
            onResolution = {},
            proofTimeoutMs = 5_000
        )
        coordinator.onSessionAuthenticated(peerID, remoteStatic, firstSession.sessionToken)

        assertTrue(
            coordinator.receive(
                peerID,
                AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, victimEd),
                firstSession
            )
        )
        val peer = peerManager.getPeerInfo(peerID)!!
        assertArrayEquals(victimEd, peer.signingPublicKey)
        assertEquals(peerID, peer.nickname)
        assertFalse("Copied self-signed nickname must lose verified status", peer.isVerifiedNickname)
        assertFalse(peer.hasVerifiedAnnouncement)
    }

    @Test
    fun `failed durable persistence cannot publish peer state in memory`() {
        val applied = CopyOnWriteArrayList<AuthenticatedPeerState>()
        val store = object : AuthenticatedPeerStateStore {
            override fun load(fingerprint: String): AuthenticatedPeerState? = null
            override fun persist(
                fingerprint: String,
                state: AuthenticatedPeerState,
                onCommitted: () -> Unit
            ): Boolean = false
            override fun isPrivateMediaPinned(fingerprint: String): Boolean = false
        }
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            authenticatedSessionProvider = { firstSession },
            withAuthenticatedSession = { _, expected, action ->
                if (expected == firstSession) action() else false
            },
            store = store,
            localStateProvider = { localState },
            applyAuthenticatedState = { _, _, state -> applied += state },
            sendState = { _, _, _ -> true },
            onResolution = {},
            proofTimeoutMs = 5_000
        )
        coordinator.onSessionAuthenticated(peerID, remoteStatic, firstSession.sessionToken)

        assertFalse(coordinator.receive(peerID, remoteState, firstSession))
        assertEquals(AuthenticatedPeerStateStatus.Awaiting, coordinator.status(peerID, firstSession))
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `stale decryption lease cannot persist or apply after generation replacement`() {
        var activeSession = firstSession
        var persistCalls = 0
        var applyCalls = 0
        val store = object : AuthenticatedPeerStateStore {
            override fun load(fingerprint: String): AuthenticatedPeerState? = null
            override fun persist(
                fingerprint: String,
                state: AuthenticatedPeerState,
                onCommitted: () -> Unit
            ): Boolean {
                persistCalls += 1
                onCommitted()
                return true
            }
            override fun isPrivateMediaPinned(fingerprint: String): Boolean = false
        }
        val coordinator = AuthenticatedPeerStateCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            authenticatedSessionProvider = { activeSession },
            withAuthenticatedSession = { _, expected, action ->
                if (expected == activeSession) action() else false
            },
            store = store,
            localStateProvider = { localState },
            applyAuthenticatedState = { _, _, _ -> applyCalls += 1 },
            sendState = { _, _, _ -> true },
            onResolution = {},
            proofTimeoutMs = 5_000
        )
        coordinator.onSessionAuthenticated(peerID, remoteStatic, firstSession.sessionToken)
        activeSession = secondSession

        assertFalse(coordinator.receive(peerID, remoteState, firstSession))
        assertEquals(0, persistCalls)
        assertEquals(0, applyCalls)
    }

    private fun fingerprint(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
}
