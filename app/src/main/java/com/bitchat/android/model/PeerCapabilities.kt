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

        val PREKEYS = PeerCapabilities(1L shl 0)
        val WIFI_BULK = PeerCapabilities(1L shl 1)
        val GATEWAY = PeerCapabilities(1L shl 2)
        val GROUPS = PeerCapabilities(1L shl 3)
        val BOARD = PeerCapabilities(1L shl 4)
        val VOUCH = PeerCapabilities(1L shl 5)
        val MESH_DIAGNOSTICS = PeerCapabilities(1L shl 6)
        val BRIDGE = PeerCapabilities(1L shl 7)

        /** Noise-encrypted private BitchatFilePacket using payload type 0x20. */
        val PRIVATE_MEDIA = PeerCapabilities(1L shl 8)

        val PRIVATE_MEDIA_RECEIPTS = PeerCapabilities(1L shl 9)

        /** Reserved by iOS; decode it but do not advertise or act on it. */
        val NON_DESTRUCTIVE_NOISE_REPLACEMENT = PeerCapabilities(1L shl 10)

        /** Capabilities implemented by this Android build. */
        val LOCAL_SUPPORTED = PRIVATE_MEDIA

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
