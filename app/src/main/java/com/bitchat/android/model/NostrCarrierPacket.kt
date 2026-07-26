package com.bitchat.android.model

import com.bitchat.android.nostr.NostrEvent
import java.io.ByteArrayOutputStream

/**
 * Wire payload for MessageType.NOSTR_CARRIER (0x28).
 *
 * The TLV layout and limits intentionally match iOS. Lengths are unsigned
 * 16-bit big-endian values and unknown TLVs are skipped.
 */
data class NostrCarrierPacket(
    val direction: Direction,
    val geohash: String,
    val eventJson: ByteArray
) {
    enum class Direction(val value: Int) {
        TO_GATEWAY(0x01),
        FROM_GATEWAY(0x02),
        TO_BRIDGE(0x03),
        FROM_BRIDGE(0x04);

        companion object {
            fun fromValue(value: Int): Direction? = entries.firstOrNull { it.value == value }
        }
    }

    init {
        require(geohash.toByteArray(Charsets.UTF_8).size in 1..MAX_GEOHASH_LENGTH)
        require(eventJson.size in 1..MAX_EVENT_JSON_BYTES)
    }

    fun event(): NostrEvent? =
        NostrEvent.fromJsonString(String(eventJson, Charsets.UTF_8))

    fun encode(): ByteArray {
        val output = ByteArrayOutputStream(eventJson.size + geohash.length + 12)
        appendTlv(output, TLV_DIRECTION, byteArrayOf(direction.value.toByte()))
        appendTlv(output, TLV_GEOHASH, geohash.toByteArray(Charsets.UTF_8))
        appendTlv(output, TLV_EVENT_JSON, eventJson)
        return output.toByteArray()
    }

    companion object {
        const val MAX_EVENT_JSON_BYTES = 16 * 1024
        const val MAX_GEOHASH_LENGTH = 12

        private const val TLV_DIRECTION = 0x01
        private const val TLV_GEOHASH = 0x02
        private const val TLV_EVENT_JSON = 0x03

        fun fromEvent(direction: Direction, geohash: String, event: NostrEvent): NostrCarrierPacket? =
            runCatching {
                NostrCarrierPacket(
                    direction = direction,
                    geohash = geohash,
                    eventJson = event.toJsonString().toByteArray(Charsets.UTF_8)
                )
            }.getOrNull()

        fun decode(data: ByteArray): NostrCarrierPacket? {
            var offset = 0
            var direction: Direction? = null
            var geohash: String? = null
            var eventJson: ByteArray? = null

            while (offset + 3 <= data.size) {
                val type = data[offset].toInt() and 0xFF
                val length =
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                        (data[offset + 2].toInt() and 0xFF)
                offset += 3
                if (offset + length > data.size) return null
                val value = data.copyOfRange(offset, offset + length)
                offset += length

                when (type) {
                    TLV_DIRECTION -> {
                        if (value.size != 1) return null
                        direction = Direction.fromValue(value[0].toInt() and 0xFF) ?: return null
                    }
                    TLV_GEOHASH -> {
                        geohash = value.toString(Charsets.UTF_8)
                    }
                    TLV_EVENT_JSON -> eventJson = value
                }
            }

            if (offset != data.size) return null
            return runCatching {
                NostrCarrierPacket(
                    direction = direction ?: return null,
                    geohash = geohash ?: return null,
                    eventJson = eventJson ?: return null
                )
            }.getOrNull()
        }

        private fun appendTlv(output: ByteArrayOutputStream, type: Int, value: ByteArray) {
            output.write(type)
            output.write((value.size ushr 8) and 0xFF)
            output.write(value.size and 0xFF)
            output.write(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is NostrCarrierPacket &&
                direction == other.direction &&
                geohash == other.geohash &&
                eventJson.contentEquals(other.eventJson))

    override fun hashCode(): Int =
        31 * (31 * direction.hashCode() + geohash.hashCode()) + eventJson.contentHashCode()
}
