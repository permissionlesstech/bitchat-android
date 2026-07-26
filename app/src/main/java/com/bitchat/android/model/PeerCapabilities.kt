package com.bitchat.android.model

import android.os.Parcelable
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
        val NONE = PeerCapabilities(0)

        /** Noise-encrypted private BitchatFilePacket using payload type 0x20. */
        val PRIVATE_MEDIA = PeerCapabilities(1L shl 8)

        /** Can bridge public mesh traffic through geohash rendezvous relays. */
        val BRIDGE = PeerCapabilities(1L shl 7)

        /** Publishes signed one-time prekeys for forward-secret courier mail. */
        val PREKEYS = PeerCapabilities(1L shl 0)

        /** Capabilities implemented by this Android build. */
        @Deprecated("Use localSupported() so runtime bridge state is included")
        val LOCAL_SUPPORTED = PeerCapabilities(PRIVATE_MEDIA.rawValue or PREKEYS.rawValue)

        @Volatile
        private var bridgeEnabled: Boolean = false

        fun setBridgeEnabled(enabled: Boolean) {
            bridgeEnabled = enabled
        }

        fun localSupported(): PeerCapabilities = PeerCapabilities(
            PRIVATE_MEDIA.rawValue or
                PREKEYS.rawValue or
                if (bridgeEnabled) BRIDGE.rawValue else 0L
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
