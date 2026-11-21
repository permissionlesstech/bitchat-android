package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AnnounceAcceptanceRobolectricTest {
    private val fixture = javaClass.getResourceAsStream("/contracts/announce-acceptance-v1.json")!!
        .bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    private val vectors = fixture.getAsJsonObject("vectors")
    private val peerID = fixture.get("peerId").asString
    private val noiseKey = fixture.get("noisePublicKeyHex").asString.hexBytes()
    private lateinit var peers: PeerManager
    private lateinit var handler: MessageHandler
    private lateinit var verifier: EncryptionService
    private var authenticatedKey: ByteArray? = null
    private var verificationCalls = 0
    private var freshnessCalls = 0
    private var identityUpdates = 0

    @Before
    fun setup() {
        val nowMs = fixture.get("nowMs").asLong
        MeshGraphService.resetForTesting()
        peers = PeerManager()
        // Disable only key/prefs initialization; verifyEd25519Signature is the production method.
        // No persisted identity, generated signing key, or Noise session is read by these tests.
        verifier = object : EncryptionService(RuntimeEnvironment.getApplication()) {
            override fun initialize() = Unit
        }
        handler = MessageHandler("0000000000000000", RuntimeEnvironment.getApplication()) { nowMs }
        // Only unrelated callbacks are mocked. All acceptance decisions and state use real code.
        handler.delegate = object : MessageHandlerDelegate by mock<MessageHandlerDelegate>() {
            override fun getPeerInfo(peerID: String) = peers.getPeerInfo(peerID)

            override fun getAuthenticatedSigningKey(noisePublicKey: ByteArray): ByteArray? =
                authenticatedKey?.copyOf().takeIf { noisePublicKey.contentEquals(noiseKey) }

            override fun verifyEd25519Signature(
                signature: ByteArray,
                data: ByteArray,
                publicKey: ByteArray
            ): Boolean {
                verificationCalls++
                return verifier.verifyEd25519Signature(signature, data, publicKey)
            }

            override fun acceptVerifiedAnnounceTimestamp(peerID: String, timestamp: ULong): Boolean {
                freshnessCalls++
                return peers.acceptVerifiedAnnounceTimestamp(peerID, timestamp)
            }

            override fun updatePeerInfoFromVerifiedAnnouncement(
                peerID: String,
                nickname: String,
                noisePublicKey: ByteArray,
                signingPublicKey: ByteArray,
                isVerified: Boolean,
                capabilities: PeerCapabilities?
            ): Boolean {
                identityUpdates++
                return peers.updatePeerInfoFromVerifiedAnnouncement(
                    peerID, nickname, noisePublicKey, signingPublicKey, isVerified, capabilities
                )
            }
        }
    }

    @After
    fun tearDown() {
        handler.shutdown()
        peers.shutdown()
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `literal wire packets match independently signed preimages`() {
        for ((name, value) in vectors.entrySet()) {
            val vector = value.asJsonObject
            val packet = packet(name)
            assertEquals(
                name,
                vector.get("signatureValid").asBoolean,
                verifier.verifyEd25519Signature(
                    packet.signature!!,
                    packet.toBinaryDataForSigning()!!,
                    vector.get("signingKeyHex").asString.hexBytes()
                )
            )
        }
    }

    @Test
    fun `signed older is rejected while equal and newer remain accepted`() = runBlocking {
        accept("current", isFirst = true)
        val currentPeer = peers.getPeerInfo(peerID)!!.copy()
        val currentGraph = graph()

        reject("older")
        assertEquals(currentPeer, peers.getPeerInfo(peerID))
        assertEquals(currentGraph, graph())
        assertEquals(1, identityUpdates)

        accept("equal")
        assertEquals("equal", peers.getPeerNickname(peerID))
        // Equality applies identity but must not replace the strictly-newer topology label.
        assertEquals(currentGraph, graph())

        accept("newer")
        assertEquals("newer", peers.getPeerNickname(peerID))
        assertEquals("newer", graph().nodes.single().nickname)
        assertEquals(4, verificationCalls)
        assertEquals(4, freshnessCalls)
        assertEquals(3, identityUpdates)
    }

    @Test
    fun `invalid signed future vector cannot create identity graph or watermark`() = runBlocking {
        reject("invalid_newer")
        assertEquals(null, peers.getPeerInfo(peerID))
        assertTrue(graph().nodes.isEmpty())
        assertEquals(0, freshnessCalls)
        assertEquals(0, identityUpdates)

        // The invalid packet's higher timestamp must not poison a later valid acceptance.
        accept("older", isFirst = true)
        assertEquals("older", peers.getPeerNickname(peerID))
        assertEquals(2, verificationCalls)
        assertEquals(1, freshnessCalls)
    }

    @Test
    fun `only authenticated signing identity replacement resets signed acceptance watermark`() = runBlocking {
        accept("current", isFirst = true)
        val before = peers.getPeerInfo(peerID)!!.copy()
        val beforeGraph = graph()

        // Valid self-signature with the same Noise key does not authorize replacing the Ed key.
        reject("replacement")
        assertEquals(before, peers.getPeerInfo(peerID))
        assertEquals(beforeGraph, graph())
        assertEquals(1, freshnessCalls)
        assertEquals(1, identityUpdates)

        authenticate("signingKeyBHex")
        assertFalse(peers.isPeerVerified(peerID))
        assertEquals(peerID, peers.getPeerNickname(peerID))
        accept("replacement")
        assertEquals("replacement", peers.getPeerNickname(peerID))
        assertTrue(peers.isPeerVerified(peerID))
        assertArrayEquals(authenticatedKey, peers.getPeerInfo(peerID)!!.signingPublicKey)
        // The separate graph watermark is deliberately not reset by identity replacement.
        assertEquals(beforeGraph, graph())

        val replacement = peers.getPeerInfo(peerID)!!.copy()
        reject("newer")
        assertEquals(replacement, peers.getPeerInfo(peerID))
        assertEquals(4, verificationCalls)
        assertEquals(2, freshnessCalls)
        assertEquals(2, identityUpdates)
    }

    @Test
    fun `authenticated state with unchanged Ed key preserves signed watermark`() = runBlocking {
        accept("current", isFirst = true)
        authenticate("signingKeyAHex")
        val before = peers.getPeerInfo(peerID)!!.copy()

        reject("older")

        assertEquals(before, peers.getPeerInfo(peerID))
        assertEquals(2, verificationCalls)
        assertEquals(2, freshnessCalls)
        assertEquals(1, identityUpdates)
    }

    @Test
    fun `peer removal resets signed acceptance watermark`() = runBlocking {
        accept("current", isFirst = true)
        peers.removePeer(peerID)

        accept("older", isFirst = true)

        assertEquals("older", peers.getPeerNickname(peerID))
        assertEquals(2, verificationCalls)
        assertEquals(2, identityUpdates)
    }

    @Test
    fun `clearing peers resets signed acceptance watermark`() = runBlocking {
        accept("current", isFirst = true)
        peers.clearAllPeers()

        accept("older", isFirst = true)

        assertEquals("older", peers.getPeerNickname(peerID))
        assertEquals(2, verificationCalls)
        assertEquals(2, identityUpdates)
    }

    private fun authenticate(keyField: String) {
        // Model the already-authenticated Noise 0x21 delivery, not a hardware/handshake proof.
        authenticatedKey = fixture.get(keyField).asString.hexBytes()
        peers.applyAuthenticatedPeerState(
            peerID, noiseKey.copyOf(), AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, authenticatedKey!!)
        )
    }

    private suspend fun accept(name: String, isFirst: Boolean = false) {
        assertEquals(
            name, AnnounceHandlingResult.Accepted(isFirst),
            handler.handleAnnounceWithResult(RoutedPacket(packet(name), peerID, "synthetic-link"))
        )
        assertTrue(peers.getPeerInfo(peerID)!!.hasVerifiedAnnouncement)
        assertEquals(PeerCapabilities.PRIVATE_MEDIA, peers.getPeerInfo(peerID)!!.capabilities)
    }

    private suspend fun reject(name: String) {
        assertEquals(
            name, AnnounceHandlingResult.Rejected,
            handler.handleAnnounceWithResult(RoutedPacket(packet(name), peerID, "synthetic-link"))
        )
    }

    private fun packet(name: String): BitchatPacket {
        val vector = vectors.getAsJsonObject(name)
        // Never call an encoder or signer to construct the received packet.
        val packet = BitchatPacket.fromBinaryData(vector.get("wireHex").asString.hexBytes())
        assertNotNull(name, packet)
        packet!!
        assertEquals(name, vector.get("timestamp").asString.toULong(), packet.timestamp)
        assertArrayEquals(name, peerID.hexBytes(), packet.senderID)
        assertArrayEquals(name, vector.get("signingDataHex").asString.hexBytes(), packet.toBinaryDataForSigning())
        return packet
    }

    private fun graph() = MeshGraphService.getInstance().graphState.value

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
