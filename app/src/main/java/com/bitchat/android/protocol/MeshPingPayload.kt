package com.bitchat.android.protocol

import java.security.SecureRandom

/**
 * iOS-compatible mesh diagnostics payload.
 *
 * Wire format: 8-byte nonce followed by the TTL used to launch the ping.
 * Decoding deliberately accepts trailing bytes for forward compatibility.
 */
data class MeshPingPayload(
    val nonce: ByteArray,
    val originTtl: UByte,
) {
    init {
        require(nonce.size == MeshDiagnosticsConstants.NONCE_SIZE) {
            "Mesh ping nonce must be ${MeshDiagnosticsConstants.NONCE_SIZE} bytes"
        }
    }

    fun encode(): ByteArray = nonce + byteArrayOf(originTtl.toByte())

    fun hopCount(receivedTtl: UByte): Int =
        (originTtl.toInt() - receivedTtl.toInt() + 1).coerceAtLeast(1)

    override fun equals(other: Any?): Boolean =
        other is MeshPingPayload && nonce.contentEquals(other.nonce) && originTtl == other.originTtl

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + originTtl.hashCode()

    companion object {
        fun create(originTtl: UByte): MeshPingPayload =
            MeshPingPayload(
                ByteArray(MeshDiagnosticsConstants.NONCE_SIZE).also(SecureRandom()::nextBytes),
                originTtl,
            )

        fun decode(data: ByteArray): MeshPingPayload? {
            if (data.size < MeshDiagnosticsConstants.PAYLOAD_SIZE) return null
            return MeshPingPayload(
                data.copyOfRange(0, MeshDiagnosticsConstants.NONCE_SIZE),
                data[MeshDiagnosticsConstants.NONCE_SIZE].toUByte(),
            )
        }
    }
}
