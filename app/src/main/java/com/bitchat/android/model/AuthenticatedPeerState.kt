package com.bitchat.android.model

/**
 * Canonical payload carried inside Noise payload type 0x21.
 *
 * Wire format:
 * `[version=0x01][type=0x01][len=1...8][minimal LE capabilities]`
 * `[type=0x02][len=32][Ed25519 public key]`
 *
 * Unknown TLVs are skipped. Both known fields must occur exactly once.
 */
data class AuthenticatedPeerState(
    val capabilities: PeerCapabilities,
    val signingPublicKey: ByteArray
) {
    init {
        require(signingPublicKey.size == SIGNING_PUBLIC_KEY_SIZE) {
            "Ed25519 public key must be 32 bytes"
        }
    }

    fun encode(): ByteArray {
        val capabilityBytes = capabilities.encoded()
        return buildList<Byte>(1 + 2 + capabilityBytes.size + 2 + signingPublicKey.size) {
            add(VERSION.toByte())
            add(CAPABILITIES_TLV.toByte())
            add(capabilityBytes.size.toByte())
            addAll(capabilityBytes.toList())
            add(SIGNING_PUBLIC_KEY_TLV.toByte())
            add(SIGNING_PUBLIC_KEY_SIZE.toByte())
            addAll(signingPublicKey.toList())
        }.toByteArray()
    }

    companion object {
        const val VERSION = 0x01
        private const val CAPABILITIES_TLV = 0x01
        private const val SIGNING_PUBLIC_KEY_TLV = 0x02
        private const val SIGNING_PUBLIC_KEY_SIZE = 32

        fun decode(data: ByteArray): AuthenticatedPeerState? {
            if (data.firstOrNull()?.toInt()?.and(0xFF) != VERSION) return null
            var offset = 1
            var capabilities: PeerCapabilities? = null
            var signingPublicKey: ByteArray? = null

            while (offset < data.size) {
                if (offset + 2 > data.size) return null
                val type = data[offset].toInt() and 0xFF
                val length = data[offset + 1].toInt() and 0xFF
                offset += 2
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    CAPABILITIES_TLV -> {
                        if (capabilities != null || length !in 1..8) return null
                        val decoded = PeerCapabilities.decode(value)
                        if (!decoded.encoded().contentEquals(value)) return null
                        capabilities = decoded
                    }

                    SIGNING_PUBLIC_KEY_TLV -> {
                        if (signingPublicKey != null || length != SIGNING_PUBLIC_KEY_SIZE) return null
                        signingPublicKey = value
                    }

                    else -> Unit
                }
            }

            val decodedCapabilities = capabilities ?: return null
            val decodedSigningKey = signingPublicKey ?: return null
            return AuthenticatedPeerState(decodedCapabilities, decodedSigningKey)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AuthenticatedPeerState &&
                capabilities == other.capabilities &&
                signingPublicKey.contentEquals(other.signingPublicKey))

    override fun hashCode(): Int = 31 * capabilities.hashCode() + signingPublicKey.contentHashCode()
}
