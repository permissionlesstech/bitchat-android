package com.bitchat.android.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LocationVerifyPacket {
    private const val PAYLOAD_SIZE = 9

    enum class Action(val value: Byte) {
        REQUEST(0x01),
        ACCEPT(0x02),
        REJECT(0x03);

        companion object {
            fun fromValue(value: Byte): Action? {
                return values().find { it.value == value }
            }
        }
    }

    data class VerifyPayload(
        val action: Action,
        val timestampSeconds: Long
    )

    fun encode(action: Action, timestampMillis: Long = System.currentTimeMillis()): ByteArray {
        return ByteBuffer.allocate(PAYLOAD_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(action.value)
            .putLong(timestampMillis / 1000L)
            .array()
    }

    fun decode(payload: ByteArray): VerifyPayload? {
        if (payload.size != PAYLOAD_SIZE) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val actionByte = buffer.get()
        val action = Action.fromValue(actionByte) ?: return null
        val timestampSeconds = buffer.getLong()
        return VerifyPayload(action = action, timestampSeconds = timestampSeconds)
    }

    fun buildPacket(
        myPeerID: String,
        targetPeerID: String,
        action: Action,
        timestampMillis: Long = System.currentTimeMillis()
    ): BitchatPacket {
        return BitchatPacket(
            version = 1u,
            type = MessageType.LOCATION_VERIFY.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(targetPeerID),
            timestamp = timestampMillis.toULong(),
            payload = encode(action, timestampMillis),
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
