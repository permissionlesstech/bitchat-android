package com.bitchat.android.model

import android.os.Parcelable
import com.bitchat.android.protocol.MeshDiagnosticsConstants
import kotlinx.parcelize.Parcelize

/**
 * Feature bits advertised in IdentityAnnouncement TLV 0x05.
 *
 * The wire representation matches iOS: a minimal little-endian bitfield that
 * always contains at least one byte. Unknown bits in the low 64 bits are kept
 * so a decode/re-encode cycle does not erase capabilities added by newer
 * clients.
 */
@Parcelize
data class PeerCapabilities(val rawValue: Long) : Parcelable {
    fun contains(capability: PeerCapabilities): Boolean =
        (rawValue and capability.rawValue) == capability.rawValue

    fun encoded(): ByteArray {
        var remaining = rawValue
        val bytes = mutableListOf<Byte>()
        do {
            bytes += remaining.toByte()
            remaining = remaining ushr 8
        } while (remaining != 0L)
        return bytes.toByteArray()
    }

    companion object {
        private const val VOUCH_BIT_INDEX = 5
        private const val PRIVATE_MEDIA_BIT_INDEX = 8
        val NONE = PeerCapabilities(0)

        val WIFI_BULK = PeerCapabilities(1L shl 1)
        val GATEWAY = PeerCapabilities(1L shl 2)
        val GROUPS = PeerCapabilities(1L shl 3)
        val BOARD = PeerCapabilities(1L shl 4)
        val MESH_DIAGNOSTICS = PeerCapabilities(1L shl 6)

        /** Noise-encrypted private BitchatFilePacket using payload type 0x20. */
        val PRIVATE_MEDIA = PeerCapabilities(1L shl PRIVATE_MEDIA_BIT_INDEX)

        /** Transitive verification attestations over authenticated Noise. */
        val VOUCH = PeerCapabilities(1L shl VOUCH_BIT_INDEX)

        val PRIVATE_MEDIA_RECEIPTS = PeerCapabilities(1L shl 9)

        /** Reserved by iOS; decode it but do not advertise or act on it. */
        val NON_DESTRUCTIVE_NOISE_REPLACEMENT = PeerCapabilities(1L shl 10)

        /** Can bridge public mesh traffic through geohash rendezvous relays. */
        val BRIDGE = PeerCapabilities(1L shl 7)

        /** Publishes signed one-time prekeys for forward-secret courier mail. */
        val PREKEYS = PeerCapabilities(1L shl 0)


        /** Capabilities implemented by this Android build. */
        @Deprecated("Use localSupported() so runtime bridge state is included")
        val LOCAL_SUPPORTED = PeerCapabilities(PRIVATE_MEDIA.rawValue or BOARD.rawValue or VOUCH.rawValue or MESH_DIAGNOSTICS.rawValue)

        @Volatile
        private var bridgeEnabled: Boolean = false
        @Volatile private var gatewayEnabled: Boolean = false
        fun setGatewayEnabled(enabled: Boolean) { gatewayEnabled = enabled }

        @Volatile private var phoneFeaturesEnabled = false

        fun setPhoneFeaturesEnabled(enabled: Boolean) { phoneFeaturesEnabled = enabled }

        fun setBridgeEnabled(enabled: Boolean) {
            bridgeEnabled = enabled
        }

        fun localSupported(): PeerCapabilities = PeerCapabilities(
            LOCAL_SUPPORTED.rawValue or
                (if (phoneFeaturesEnabled) PREKEYS.rawValue or GROUPS.rawValue or PRIVATE_MEDIA_RECEIPTS.rawValue else 0L) or
                (if (bridgeEnabled) BRIDGE.rawValue else 0L) or
                (if (gatewayEnabled) GATEWAY.rawValue else 0L)
        )

        /**
         * Decode the low 64 bits and ignore any future extension bytes, which
         * is the same forward-compatible behavior used by iOS.
         */
        fun decode(data: ByteArray): PeerCapabilities {
            var rawValue = 0L
            data.take(8).forEachIndexed { index, byte ->
                rawValue = rawValue or ((byte.toLong() and 0xFF) shl (8 * index))
            }
            return PeerCapabilities(rawValue)
        }
    }
}

/** An announcement TLV not understood by this build, retained verbatim. */
@Parcelize
class UnknownAnnouncementTLV(
    val type: Int,
    val value: ByteArray
) : Parcelable {
    init {
        require(type in 0..0xFF) { "TLV type must fit in one byte" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is UnknownAnnouncementTLV && type == other.type && value.contentEquals(other.value))

    override fun hashCode(): Int = 31 * type + value.contentHashCode()
}
