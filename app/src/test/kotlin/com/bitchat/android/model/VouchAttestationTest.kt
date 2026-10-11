package com.bitchat.android.model

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class VouchAttestationTest {
    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKey: Ed25519PublicKeyParameters = privateKey.generatePublicKey()
    private val fingerprint = ByteArray(VouchAttestation.FINGERPRINT_SIZE) { FINGERPRINT_BYTE }
    private val voucheeKey = ByteArray(VouchAttestation.SIGNING_KEY_SIZE) { SIGNING_KEY_BYTE }

    @Test
    fun `attestation round trips with iOS wire format and verifies`() {
        val attestation = buildAttestation()
        val decoded = VouchAttestation.decode(attestation.encode())!!

        assertEquals(attestation, decoded)
        assertTrue(verify(decoded.signature, decoded.signableBytes(), publicKey.encoded))
        assertArrayEquals(fingerprint, decoded.voucheeFingerprint)
    }

    @Test
    fun `unknown TLV is skipped but truncation is rejected`() {
        val encoded = buildAttestation().encode()
        val withUnknown = encoded + byteArrayOf(UNKNOWN_TLV_TYPE, UNKNOWN_TLV_LENGTH, UNKNOWN_TLV_VALUE)

        assertEquals(buildAttestation(), VouchAttestation.decode(withUnknown))
        assertEquals(null, VouchAttestation.decode(encoded.copyOf(encoded.size - TRUNCATED_BYTE_COUNT)))
    }

    @Test
    fun `tampering and expiry are rejected`() {
        val attestation = buildAttestation()
        val tampered = attestation.signableBytes().copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor TAMPER_MASK).toByte()
        }
        assertFalse(verify(attestation.signature, tampered, publicKey.encoded))
        assertTrue(attestation.isExpired(TEST_TIMESTAMP_MS + VouchAttestation.MAX_AGE_MS + EXPIRY_DELTA_MS))
        assertTrue(attestation.isExpired(TEST_TIMESTAMP_MS - VouchAttestation.MAX_CLOCK_SKEW_MS - EXPIRY_DELTA_MS))
    }

    @Test
    fun `batch caps encoding and decoding`() {
        val attestations = List(VouchAttestation.MAX_BATCH_COUNT) { buildAttestation() }
        val encoded = VouchAttestation.encodeList(attestations)!!
        assertEquals(VouchAttestation.MAX_BATCH_COUNT, VouchAttestation.decodeList(encoded).size)
        assertEquals(null, VouchAttestation.encodeList(attestations + buildAttestation()))

        val dishonestCount = encoded.copyOf().also { it[BATCH_COUNT_OFFSET] = UByte.MAX_VALUE.toByte() }
        assertEquals(VouchAttestation.MAX_BATCH_COUNT, VouchAttestation.decodeList(dishonestCount).size)
    }

    private fun buildAttestation(): VouchAttestation =
        requireNotNull(VouchAttestation.build(fingerprint, voucheeKey, TEST_TIMESTAMP_MS, ::sign))

    private fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(data, 0, data.size)
        return verifier.verifySignature(signature)
    }

    companion object {
        private const val TEST_TIMESTAMP_MS = 1_700_000_000_000L
        private const val FINGERPRINT_BYTE: Byte = 0x11
        private const val SIGNING_KEY_BYTE: Byte = 0x22
        private const val UNKNOWN_TLV_TYPE: Byte = 0x7F
        private const val UNKNOWN_TLV_LENGTH: Byte = 0x01
        private const val UNKNOWN_TLV_VALUE: Byte = 0x42
        private const val TRUNCATED_BYTE_COUNT = 1
        private const val TAMPER_MASK = 0x01
        private const val EXPIRY_DELTA_MS = 1L
        private const val BATCH_COUNT_OFFSET = 0
    }
}
