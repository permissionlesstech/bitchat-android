package com.bitchat.domain.model

enum class NoisePayloadType(val value: UByte) {
    PRIVATE_MESSAGE(0x01u),
    READ_RECEIPT(0x02u),
    DELIVERED(0x03u)
}

data class NoisePayload(
    val type: NoisePayloadType,
    val data: ByteArray
)

data class PrivateMessagePacket(
    val messageId: String,
    val content: String
)

data class ReadReceipt(
    val originalMessageId: String,
    val readerPeerId: String? = null
)

