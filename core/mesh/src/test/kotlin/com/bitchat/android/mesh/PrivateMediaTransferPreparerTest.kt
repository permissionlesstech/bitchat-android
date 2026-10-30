package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.noise.AuthenticatedNoiseSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class PrivateMediaTransferPreparerTest {
    private val senderID = hex("0011223344556677")
    private val recipientID = hex("8877665544332211")
    private val authenticatedSession = AuthenticatedNoiseSession(
        ByteArray(32) { 0x21 },
        ByteArray(32) { 0x22 }
    )
    private val fragmentManagers = mutableListOf<FragmentManager>()

    @After
    fun tearDown() {
        fragmentManagers.forEach(FragmentManager::shutdown)
    }

    @Test
    fun `encrypted mode emits deployed Noise 0x20 and signs before fragmentation`() {
        var encryptedPlaintext: ByteArray? = null
        val preparer = preparer(
            policy = PrivateMediaPolicyDecision.Encrypted(authenticatedSession),
            encrypt = { bytes, _, _ ->
                encryptedPlaintext = bytes
                PrivateMediaEncryptionResult.Success(byteArrayOf(0x41) + bytes)
            }
        )

        val outcome = preparer.prepare("peer", recipientID, file(64), false)

        val ready = outcome as PrivateMediaBuildOutcome.Ready
        assertEquals(MessageType.NOISE_ENCRYPTED.value, ready.built.packet.type)
        assertNotNull(ready.built.packet.signature)
        assertEquals(PrivateMediaWireMode.ENCRYPTED_NOISE_0X20, ready.built.wireMode)
        val decoded = NoisePayload.decode(encryptedPlaintext!!)
        assertEquals(NoisePayloadType.FILE_TRANSFER, decoded?.type)
        assertEquals(0x20u.toUByte(), encryptedPlaintext!![0].toUByte())
    }

    @Test
    fun `prerelease iOS Noise 0x09 decodes as file transfer but re-encodes canonical 0x20`() {
        val filePayload = file(3).encode()!!
        val prereleasePayload = byteArrayOf(0x09) + filePayload

        val decoded = NoisePayload.decode(prereleasePayload)

        assertEquals(NoisePayloadType.FILE_TRANSFER, decoded?.type)
        assertTrue(filePayload.contentEquals(decoded?.data))
        assertEquals(0x20u.toUByte(), decoded!!.encode()[0].toUByte())
    }

    @Test
    fun `legacy mode requires consent and signing failure aborts`() {
        val noConsent = preparer(policy = PrivateMediaPolicyDecision.RequiresLegacyConsent)
            .prepare("peer", recipientID, file(64), false)
        assertTrue(noConsent is PrivateMediaBuildOutcome.RequiresLegacyConsent)

        val signingFailure = preparer(
            policy = PrivateMediaPolicyDecision.RequiresLegacyConsent,
            finalizer = { null }
        ).prepare("peer", recipientID, file(64), true)
        assertTrue(signingFailure is PrivateMediaBuildOutcome.Rejected)
        assertTrue((signingFailure as PrivateMediaBuildOutcome.Rejected).reason.contains("nothing was sent"))

        val malformedSignature = preparer(
            policy = PrivateMediaPolicyDecision.RequiresLegacyConsent,
            finalizer = { packet -> packet.copy(signature = ByteArray(0)) }
        ).prepare("peer", recipientID, file(64), true)
        assertTrue(malformedSignature is PrivateMediaBuildOutcome.Rejected)
    }

    @Test
    fun `consented legacy mode is signed directed raw 0x22`() {
        val outcome = preparer(policy = PrivateMediaPolicyDecision.RequiresLegacyConsent)
            .prepare("peer", recipientID, file(64), true)

        val ready = outcome as PrivateMediaBuildOutcome.Ready
        assertEquals(MessageType.FILE_TRANSFER.value, ready.built.packet.type)
        assertTrue(ready.built.packet.recipientID!!.contentEquals(recipientID))
        assertNotNull(ready.built.packet.signature)
        assertEquals(PrivateMediaWireMode.SIGNED_DIRECTED_RAW_0X22, ready.built.wireMode)
    }

    @Test
    fun `handshake requirement returns before encoding signing encryption or fragmentation`() {
        var encrypted = false
        var finalized = false
        var fragmented = false
        val fragmentManager = FragmentManager().also { fragmentManagers += it }
        val preparer = PrivateMediaTransferPreparer(
            senderID = senderID,
            ttl = 7u,
            policyProvider = { PrivateMediaPolicyDecision.NeedsHandshake },
            encrypt = { _, _, _ ->
                encrypted = true
                PrivateMediaEncryptionResult.Success(byteArrayOf(1))
            },
            finalizeRoutedAndSigned = {
                finalized = true
                it
            },
            fragment = { packet, maxFragments ->
                fragmented = true
                fragmentManager.createFragments(packet, maxFragments)
            }
        )

        val outcome = preparer.prepare("peer", recipientID, file(64), false)

        assertEquals(PrivateMediaBuildOutcome.NeedsHandshake, outcome)
        assertTrue(!encrypted)
        assertTrue(!finalized)
        assertTrue(!fragmented)
    }

    @Test
    fun `peer-state wait returns before encoding signing encryption or fragmentation`() {
        var encrypted = false
        var finalized = false
        var fragmented = false
        val fragmentManager = FragmentManager().also { fragmentManagers += it }
        val preparer = PrivateMediaTransferPreparer(
            senderID = senderID,
            ttl = 7u,
            policyProvider = { PrivateMediaPolicyDecision.AwaitingPeerState },
            encrypt = { _, _, _ ->
                encrypted = true
                PrivateMediaEncryptionResult.Success(byteArrayOf(1))
            },
            finalizeRoutedAndSigned = {
                finalized = true
                it
            },
            fragment = { packet, maxFragments ->
                fragmented = true
                fragmentManager.createFragments(packet, maxFragments)
            }
        )

        val outcome = preparer.prepare("peer", recipientID, file(64), false)

        assertEquals(PrivateMediaBuildOutcome.AwaitingPeerState, outcome)
        assertTrue(!encrypted)
        assertTrue(!finalized)
        assertTrue(!fragmented)
    }

    @Test
    fun `impossible content size rejects before policy encryption signing or fragmentation`() {
        var policyChecked = false
        var encrypted = false
        var finalized = false
        var fragmented = false
        val absolutePayloadUpperBound =
            com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID *
                com.bitchat.android.util.AppConstants.Fragmentation.MAX_FRAGMENT_SIZE
        val preparer = PrivateMediaTransferPreparer(
            senderID = senderID,
            ttl = 7u,
            policyProvider = {
                policyChecked = true
                PrivateMediaPolicyDecision.Encrypted(authenticatedSession)
            },
            encrypt = { _, _, _ ->
                encrypted = true
                PrivateMediaEncryptionResult.Success(byteArrayOf(1))
            },
            finalizeRoutedAndSigned = {
                finalized = true
                it
            },
            fragment = { _, _ ->
                fragmented = true
                emptyList()
            }
        )

        val outcome = preparer.prepare(
            "peer",
            recipientID,
            file(absolutePayloadUpperBound + 1),
            false
        )

        assertTrue(outcome is PrivateMediaBuildOutcome.Rejected)
        assertTrue((outcome as PrivateMediaBuildOutcome.Rejected).reason.contains("256"))
        assertTrue(!policyChecked)
        assertTrue(!encrypted)
        assertTrue(!finalized)
        assertTrue(!fragmented)
    }

    @Test
    fun `no route accepts 256 final fragments and rejects 257`() {
        assertExactBoundary(route = null)
    }

    @Test
    fun `source route accepts 256 final fragments and rejects 257`() {
        assertExactBoundary(
            route = listOf(
                hex("1021324354657687"),
                hex("2031425364758697"),
                hex("30415263748596a7")
            )
        )
    }

    @Test
    fun `generation churn re-runs policy once instead of losing the send intent`() {
        val replacementSession = AuthenticatedNoiseSession(
            authenticatedSession.remoteStaticKey,
            ByteArray(32) { 0x23 }
        )
        var policyCalls = 0
        val fragmentManager = FragmentManager().also { fragmentManagers += it }
        val preparer = PrivateMediaTransferPreparer(
            senderID = senderID,
            ttl = 7u,
            policyProvider = {
                policyCalls += 1
                PrivateMediaPolicyDecision.Encrypted(
                    if (policyCalls == 1) authenticatedSession else replacementSession
                )
            },
            encrypt = { bytes, _, session ->
                if (session == authenticatedSession) {
                    PrivateMediaEncryptionResult.GenerationChanged
                } else {
                    PrivateMediaEncryptionResult.Success(byteArrayOf(0x41) + bytes)
                }
            },
            finalizeRoutedAndSigned = { it.copy(signature = ByteArray(64) { 0x33 }) },
            fragment = fragmentManager::createFragments
        )

        val outcome = preparer.prepare("peer", recipientID, file(64), false)

        assertTrue(outcome is PrivateMediaBuildOutcome.Ready)
        assertEquals(2, policyCalls)
    }

    @Test
    fun `repeated generation churn remains retryable`() {
        val fragmentManager = FragmentManager().also { fragmentManagers += it }
        val preparer = PrivateMediaTransferPreparer(
            senderID = senderID,
            ttl = 7u,
            policyProvider = { PrivateMediaPolicyDecision.Encrypted(authenticatedSession) },
            encrypt = { _, _, _ -> PrivateMediaEncryptionResult.GenerationChanged },
            finalizeRoutedAndSigned = { it.copy(signature = ByteArray(64) { 0x33 }) },
            fragment = fragmentManager::createFragments
        )

        assertEquals(
            PrivateMediaBuildOutcome.AwaitingPeerState,
            preparer.prepare("peer", recipientID, file(64), false)
        )
    }

    private fun assertExactBoundary(route: List<ByteArray>?) {
        val randomContent = ByteArray(180 * 1024).also { Random(0xB17C4A7).nextBytes(it) }
        val preparer = preparer(
            policy = PrivateMediaPolicyDecision.Encrypted(authenticatedSession),
            finalizer = { packet ->
                packet.copy(
                    version = if (route == null) packet.version else 2u,
                    route = route,
                    signature = ByteArray(64) { 0x5A }
                )
            }
        )

        fun outcome(contentSize: Int): PrivateMediaBuildOutcome = preparer.prepare(
            "peer",
            recipientID,
            BitchatFilePacket(
                fileName = "boundary.bin",
                fileSize = contentSize.toLong(),
                mimeType = "application/octet-stream",
                content = randomContent.copyOf(contentSize)
            ),
            false
        )

        var low = 1
        var high = randomContent.size
        while (low < high) {
            val mid = low + (high - low) / 2
            if (outcome(mid) is PrivateMediaBuildOutcome.Rejected) high = mid else low = mid + 1
        }

        val accepted = outcome(low - 1) as PrivateMediaBuildOutcome.Ready
        val rejected = outcome(low)
        assertEquals(256, accepted.built.fragments.size)
        assertTrue(rejected is PrivateMediaBuildOutcome.Rejected)
        assertTrue((rejected as PrivateMediaBuildOutcome.Rejected).reason.contains("256"))
    }

    private fun preparer(
        policy: PrivateMediaPolicyDecision,
        encrypt: (
            ByteArray,
            String,
            AuthenticatedNoiseSession
        ) -> PrivateMediaEncryptionResult = { bytes, _, _ ->
            PrivateMediaEncryptionResult.Success(byteArrayOf(0x01) + bytes)
        },
        finalizer: (BitchatPacket) -> BitchatPacket? = { packet ->
            packet.copy(signature = ByteArray(64) { 0x33 })
        }
    ): PrivateMediaTransferPreparer {
        val fragmentManager = FragmentManager().also { fragmentManagers += it }
        return PrivateMediaTransferPreparer(
            senderID = senderID,
            ttl = 7u,
            policyProvider = { policy },
            encrypt = encrypt,
            finalizeRoutedAndSigned = finalizer,
            fragment = fragmentManager::createFragments,
            now = { 1_700_000_000_000uL }
        )
    }

    private fun file(size: Int) = BitchatFilePacket(
        fileName = "test.bin",
        fileSize = size.toLong(),
        mimeType = "application/octet-stream",
        content = ByteArray(size) { it.toByte() }
    )

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
