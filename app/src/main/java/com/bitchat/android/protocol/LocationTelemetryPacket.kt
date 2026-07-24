package com.bitchat.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LocationTelemetryPacket {
    private const val PAYLOAD_SIZE = 12

    data class Telemetry(
        val latitude: Float,
        val longitude: Float,
        val timestampSeconds: Long
    )

    fun encode(latitude: Double, longitude: Double, timestampMillis: Long = System.currentTimeMillis()): ByteArray {
        return ByteBuffer.allocate(PAYLOAD_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putFloat(latitude.toFloat())
            .putFloat(longitude.toFloat())
            .putInt((timestampMillis / 1000L).toInt())
            .array()
    }

    fun decode(payload: ByteArray): Telemetry? {
        if (payload.size != PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return Telemetry(
            latitude = buffer.getFloat(),
            longitude = buffer.getFloat(),
            timestampSeconds = buffer.getInt().toLong() and 0xFFFF_FFFFL
        )
    }

    fun buildPacket(
        myPeerID: String,
        latitude: Double,
        longitude: Double,
        timestampMillis: Long = System.currentTimeMillis()
    ): BitchatPacket {
        return BitchatPacket(
            version = 1u,
            type = MessageType.LOCATION.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = timestampMillis.toULong(),
            payload = encode(latitude, longitude, timestampMillis),
            signature = null,
            ttl = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS
        )
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
        var index = 0
        var cursor = hexString
        while (cursor.length >= 2 && index < 8) {
            result[index] = cursor.substring(0, 2).toIntOrNull(16)?.toByte() ?: 0
            cursor = cursor.substring(2)
            index++
        }
        return result
    }
}
