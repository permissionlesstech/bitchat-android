package com.bitchat.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Privacy-preserving, unsigned presence payload for message type 0x2C. */
data class AnnounceV2(
    val epoch: UInt,
    val recognitionTags: ByteArray,
    val capabilities: PeerCapabilities,
    val bridgeGeohash: String? = null
) {
    init {
        require(recognitionTags.size == RECOGNITION_TAGS_SIZE) { "Recognition tags must be 64 bytes" }
        require(bridgeGeohash == null || bridgeGeohash.toByteArray(Charsets.UTF_8).size <= MAX_GEOHASH_SIZE) {
            "Bridge geohash must be at most 12 UTF-8 bytes"
        }
    }

    fun encode(): ByteArray {
        val epochBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(epoch.toInt()).array()
        val capabilityBytes = capabilities.encoded()
        val geohashBytes = bridgeGeohash?.toByteArray(Charsets.UTF_8)
        return buildList<Byte> {
            addTlv(EPOCH_TLV, epochBytes)
            addTlv(RECOGNITION_TAGS_TLV, recognitionTags)
            addTlv(CAPABILITIES_TLV, capabilityBytes)
            if (geohashBytes != null) addTlv(BRIDGE_GEOHASH_TLV, geohashBytes)
        }.toByteArray()
    }

    companion object {
        private const val EPOCH_TLV = 0x01
        private const val RECOGNITION_TAGS_TLV = 0x02
        private const val CAPABILITIES_TLV = 0x03
        private const val BRIDGE_GEOHASH_TLV = 0x04
        private const val RECOGNITION_TAGS_SIZE = 64
        private const val MAX_GEOHASH_SIZE = 12

        fun decode(data: ByteArray): AnnounceV2? {
            var offset = 0
            var epoch: UInt? = null
            var tags: ByteArray? = null
            var capabilities: PeerCapabilities? = null
            var geohash: String? = null
            var sawGeohash = false

            while (offset < data.size) {
                if (offset + 2 > data.size) return null
                val type = data[offset].toInt() and 0xFF
                val length = data[offset + 1].toInt() and 0xFF
                offset += 2
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length
                when (type) {
                    EPOCH_TLV -> {
                        if (epoch != null || length != 4) return null
                        epoch = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).int.toUInt()
                    }
                    RECOGNITION_TAGS_TLV -> {
                        if (tags != null || length != RECOGNITION_TAGS_SIZE) return null
                        tags = value
                    }
                    CAPABILITIES_TLV -> {
                        if (capabilities != null || length !in 1..8) return null
                        val decoded = PeerCapabilities.decode(value)
                        if (!decoded.encoded().contentEquals(value)) return null
                        capabilities = decoded
                    }
                    BRIDGE_GEOHASH_TLV -> {
                        if (sawGeohash || length > MAX_GEOHASH_SIZE) return null
                        sawGeohash = true
                        geohash = value.toString(Charsets.UTF_8)
                    }
                    else -> Unit
                }
            }
            return AnnounceV2(epoch ?: return null, tags ?: return null, capabilities ?: return null, geohash)
        }

        private fun MutableList<Byte>.addTlv(type: Int, value: ByteArray) {
            add(type.toByte())
            add(value.size.toByte())
            addAll(value.toList())
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is AnnounceV2 && epoch == other.epoch &&
            recognitionTags.contentEquals(other.recognitionTags) && capabilities == other.capabilities &&
            bridgeGeohash == other.bridgeGeohash)

    override fun hashCode(): Int = 31 * (31 * (31 * epoch.hashCode() + recognitionTags.contentHashCode()) +
        capabilities.hashCode()) + (bridgeGeohash?.hashCode() ?: 0)
}
