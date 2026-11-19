package com.bitchat.android.model

import com.bitchat.android.util.AppConstants

/**
 * Canonical payload carried inside Noise payload type 0x21.
 *
 * Wire format:
 * `[version=0x01][type=0x01][len=1...8][minimal LE capabilities]`
 * `[type=0x02][len=32][Ed25519 public key]`
 * `[type=0x03][len=2][reassembly fragment ceiling, big-endian]` (optional)
 *
 * Unknown TLVs are skipped. Both required fields must occur exactly once.
 */
data class AuthenticatedPeerState(
    val capabilities: PeerCapabilities,
    val signingPublicKey: ByteArray,
    /**
     * How many BLE fragments this peer will reassemble for one packet.
     *
     * The private-media capability bit says a peer understands encrypted
     * media; it says nothing about how much of it that peer can hold. A
     * sender that has only the bit has to guess the ceiling from the packet
     * type, and that guess is wrong for this client: `FragmentManager`
     * rejects any stream above `MAX_FRAGMENTS_PER_ID` regardless of type,
     * while a sender assuming the encrypted path implies a large reassembler
     * will plan far more than that and see no failure.
     *
     * `null` means the peer did not advertise one, which is what every client
     * released before this TLV sends.
     */
    val maxReassemblyFragments: Int? = null
) {
    init {
        require(signingPublicKey.size == SIGNING_PUBLIC_KEY_SIZE) {
            "Ed25519 public key must be 32 bytes"
        }
        require(maxReassemblyFragments == null || maxReassemblyFragments in 1..0xFFFF) {
            "Reassembly ceiling must fit the 2-byte wire field and admit at least one fragment"
        }
    }

    fun encode(): ByteArray {
        val capabilityBytes = capabilities.encoded()
        val ceilingBytes = if (maxReassemblyFragments == null) 0 else 2 + CEILING_SIZE
        return buildList<Byte>(
            1 + 2 + capabilityBytes.size + 2 + signingPublicKey.size + ceilingBytes
        ) {
            add(VERSION.toByte())
            add(CAPABILITIES_TLV.toByte())
            add(capabilityBytes.size.toByte())
            addAll(capabilityBytes.toList())
            add(SIGNING_PUBLIC_KEY_TLV.toByte())
            add(SIGNING_PUBLIC_KEY_SIZE.toByte())
            addAll(signingPublicKey.toList())
            if (maxReassemblyFragments != null) {
                add(MAX_REASSEMBLY_FRAGMENTS_TLV.toByte())
                add(CEILING_SIZE.toByte())
                add(((maxReassemblyFragments shr 8) and 0xFF).toByte())
                add((maxReassemblyFragments and 0xFF).toByte())
            }
        }.toByteArray()
    }

    companion object {
        const val VERSION = 0x01
        private const val CAPABILITIES_TLV = 0x01
        private const val SIGNING_PUBLIC_KEY_TLV = 0x02
        private const val SIGNING_PUBLIC_KEY_SIZE = 32
        private const val MAX_REASSEMBLY_FRAGMENTS_TLV = 0x03
        private const val CEILING_SIZE = 2

        /**
         * This client's own state, as advertised to an authenticated peer.
         *
         * Built here rather than at each call site so the mesh services
         * cannot drift apart, and so the advertised ceiling is pinned to the
         * constant the reassembler enforces instead of being restated by
         * hand next to a capability set.
         */
        fun local(signingPublicKey: ByteArray): AuthenticatedPeerState =
            AuthenticatedPeerState(
                PeerCapabilities.LOCAL_SUPPORTED,
                signingPublicKey,
                AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
            )

        fun decode(data: ByteArray): AuthenticatedPeerState? {
            if (data.firstOrNull()?.toInt()?.and(0xFF) != VERSION) return null
            var offset = 1
            var capabilities: PeerCapabilities? = null
            var signingPublicKey: ByteArray? = null
            var maxReassemblyFragments: Int? = null

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

                    MAX_REASSEMBLY_FRAGMENTS_TLV -> {
                        // Rejected rather than skipped like an unknown type: a
                        // peer that meant to constrain the sender but sent a
                        // field it cannot read must not be treated as having
                        // said nothing, because "said nothing" falls back to
                        // the permissive type guess.
                        if (maxReassemblyFragments != null || length != CEILING_SIZE) return null
                        val decoded = ((value[0].toInt() and 0xFF) shl 8) or
                            (value[1].toInt() and 0xFF)
                        if (decoded == 0) return null
                        maxReassemblyFragments = decoded
                    }

                    else -> Unit
                }
            }

            val decodedCapabilities = capabilities ?: return null
            val decodedSigningKey = signingPublicKey ?: return null
            return AuthenticatedPeerState(
                decodedCapabilities,
                decodedSigningKey,
                maxReassemblyFragments
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is AuthenticatedPeerState &&
                capabilities == other.capabilities &&
                signingPublicKey.contentEquals(other.signingPublicKey) &&
                maxReassemblyFragments == other.maxReassemblyFragments)

    override fun hashCode(): Int {
        var result = 31 * capabilities.hashCode() + signingPublicKey.contentHashCode()
        result = 31 * result + (maxReassemblyFragments ?: 0)
        return result
    }
}
