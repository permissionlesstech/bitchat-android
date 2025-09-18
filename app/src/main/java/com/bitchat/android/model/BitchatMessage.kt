package com.bitchat.android.model

import android.os.Parcelable
import com.google.gson.GsonBuilder
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@Parcelize
enum class BitchatMessageType : Parcelable {
    Message,
    Audio,
    Image,
    File
}

/**
 * Delivery status for messages - exact same as iOS version
 */
sealed class DeliveryStatus : Parcelable {
    @Parcelize
    object Sending : DeliveryStatus()

    @Parcelize
    object Sent : DeliveryStatus()

    @Parcelize
    data class Delivered(val to: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Read(val by: String, val at: Date) : DeliveryStatus()

    @Parcelize
    data class Failed(val reason: String) : DeliveryStatus()

    @Parcelize
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()

    fun getDisplayText(): String {
        return when (this) {
            is Sending -> "Sending..."
            is Sent -> "Sent"
            is Delivered -> "Delivered to ${this.to}"
            is Read -> "Read by ${this.by}"
            is Failed -> "Failed: ${this.reason}"
            is PartiallyDelivered -> "Delivered to ${this.reached}/${this.total}"
        }
    }
}

/**
 * BitchatMessage - 100% compatible with iOS version
 */
@Parcelize
data class BitchatMessage(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val sender: String,
    val content: String,
    val type: BitchatMessageType = BitchatMessageType.Message,
    val timestamp: Date,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerID: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatus? = null,
    val powDifficulty: Int? = null
) : Parcelable {

    /**
     * Convert message to binary payload format - exactly same as iOS version
     */
    fun toBinaryPayload(): ByteArray? {
        try {
            val buffer = ByteBuffer.allocate(4096).apply { order(ByteOrder.BIG_ENDIAN) }

            // Message format:
            // - Flags: 1 byte (bit flags for optional fields)
            // - Timestamp: 8 bytes (milliseconds since epoch, big-endian)
            // - ID length: 1 byte + ID data
            // - Sender length: 1 byte + sender data
            // - Content length: 2 bytes + content data (or encrypted content)
            // Optional fields based on flags...

            var flags: UByte = 0u
            if (isRelay) flags = flags or 0x01u
            if (isPrivate) flags = flags or 0x02u
            if (originalSender != null) flags = flags or 0x04u
            if (recipientNickname != null) flags = flags or 0x08u
            if (senderPeerID != null) flags = flags or 0x10u
            if (mentions != null && mentions.isNotEmpty()) flags = flags or 0x20u
            if (channel != null) flags = flags or 0x40u
            if (isEncrypted) flags = flags or 0x80u

            buffer.put(flags.toByte())

            // Timestamp (in milliseconds, 8 bytes big-endian)
            val timestampMillis = timestamp.time
            buffer.putLong(timestampMillis)

            // ID
            val idBytes = id.toByteArray(Charsets.UTF_8)
            buffer.put(minOf(idBytes.size, 255).toByte())
            buffer.put(idBytes.take(255).toByteArray())

            // Sender
            val senderBytes = sender.toByteArray(Charsets.UTF_8)
            buffer.put(minOf(senderBytes.size, 255).toByte())
            buffer.put(senderBytes.take(255).toByteArray())

            // Content or encrypted content
            if (isEncrypted && encryptedContent != null) {
                val length = minOf(encryptedContent.size, 65535)
                buffer.putShort(length.toShort())
                buffer.put(encryptedContent.take(length).toByteArray())
            } else {
                val contentBytes = content.toByteArray(Charsets.UTF_8)
                val length = minOf(contentBytes.size, 65535)
                buffer.putShort(length.toShort())
                buffer.put(contentBytes.take(length).toByteArray())
            }

            // Optional fields
            originalSender?.let { origSender ->
                val origBytes = origSender.toByteArray(Charsets.UTF_8)
                buffer.put(minOf(origBytes.size, 255).toByte())
                buffer.put(origBytes.take(255).toByteArray())
            }

            recipientNickname?.let { recipient ->
                val recipBytes = recipient.toByteArray(Charsets.UTF_8)
                buffer.put(minOf(recipBytes.size, 255).toByte())
                buffer.put(recipBytes.take(255).toByteArray())
            }

            senderPeerID?.let { peerID ->
                val peerBytes = peerID.toByteArray(Charsets.UTF_8)
                buffer.put(minOf(peerBytes.size, 255).toByte())
                buffer.put(peerBytes.take(255).toByteArray())
            }

            // Mentions array
            mentions?.let { mentionList ->
                buffer.put(minOf(mentionList.size, 255).toByte())
                mentionList.take(255).forEach { mention ->
                    val mentionBytes = mention.toByteArray(Charsets.UTF_8)
                    buffer.put(minOf(mentionBytes.size, 255).toByte())
                    buffer.put(mentionBytes.take(255).toByteArray())
                }
            }

            // Channel hashtag
            channel?.let { channelName ->
                val channelBytes = channelName.toByteArray(Charsets.UTF_8)
                buffer.put(minOf(channelBytes.size, 255).toByte())
                buffer.put(channelBytes.take(255).toByteArray())
            }

            val result = ByteArray(buffer.position())
            buffer.rewind()
            buffer.get(result)
            return result

        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        /**
         * Parse message from binary payload - exactly same logic as iOS version
         */
        fun fromBinaryPayload(data: ByteArray): BitchatMessage? {
            try {
                if (data.size < 13) return null

                val buffer = ByteBuffer.wrap(data).apply { order(ByteOrder.BIG_ENDIAN) }

                // Flags
                val flags = buffer.get().toUByte()
                val isRelay = (flags and 0x01u) != 0u.toUByte()
                val isPrivate = (flags and 0x02u) != 0u.toUByte()
                val hasOriginalSender = (flags and 0x04u) != 0u.toUByte()
                val hasRecipientNickname = (flags and 0x08u) != 0u.toUByte()
                val hasSenderPeerID = (flags and 0x10u) != 0u.toUByte()
                val hasMentions = (flags and 0x20u) != 0u.toUByte()
                val hasChannel = (flags and 0x40u) != 0u.toUByte()
                val isEncrypted = (flags and 0x80u) != 0u.toUByte()

                // Timestamp
                val timestampMillis = buffer.getLong()
                val timestamp = Date(timestampMillis)

                // ID
                val idLength = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < idLength) return null
                val idBytes = ByteArray(idLength)
                buffer.get(idBytes)
                val id = String(idBytes, Charsets.UTF_8)

                // Sender
                val senderLength = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < senderLength) return null
                val senderBytes = ByteArray(senderLength)
                buffer.get(senderBytes)
                val sender = String(senderBytes, Charsets.UTF_8)

                // Content
                val contentLength = buffer.getShort().toInt() and 0xFFFF
                if (buffer.remaining() < contentLength) return null

                val content: String
                val encryptedContent: ByteArray?

                if (isEncrypted) {
                    val encryptedBytes = ByteArray(contentLength)
                    buffer.get(encryptedBytes)
                    encryptedContent = encryptedBytes
                    content = "" // Empty placeholder
                } else {
                    val contentBytes = ByteArray(contentLength)
                    buffer.get(contentBytes)
                    content = String(contentBytes, Charsets.UTF_8)
                    encryptedContent = null
                }

                // Optional fields
                val originalSender = if (hasOriginalSender && buffer.hasRemaining()) {
                    val length = buffer.get().toInt() and 0xFF
                    if (buffer.remaining() >= length) {
                        val bytes = ByteArray(length)
                        buffer.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else null
                } else null

                val recipientNickname = if (hasRecipientNickname && buffer.hasRemaining()) {
                    val length = buffer.get().toInt() and 0xFF
                    if (buffer.remaining() >= length) {
                        val bytes = ByteArray(length)
                        buffer.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else null
                } else null

                val senderPeerID = if (hasSenderPeerID && buffer.hasRemaining()) {
                    val length = buffer.get().toInt() and 0xFF
                    if (buffer.remaining() >= length) {
                        val bytes = ByteArray(length)
                        buffer.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else null
                } else null

                // Mentions array
                val mentions = if (hasMentions && buffer.hasRemaining()) {
                    val mentionCount = buffer.get().toInt() and 0xFF
                    val mentionList = mutableListOf<String>()
                    repeat(mentionCount) {
                        if (buffer.hasRemaining()) {
                            val length = buffer.get().toInt() and 0xFF
                            if (buffer.remaining() >= length) {
                                val bytes = ByteArray(length)
                                buffer.get(bytes)
                                mentionList.add(String(bytes, Charsets.UTF_8))
                            }
                        }
                    }
                    if (mentionList.isNotEmpty()) mentionList else null
                } else null

                // Channel
                val channel = if (hasChannel && buffer.hasRemaining()) {
                    val length = buffer.get().toInt() and 0xFF
                    if (buffer.remaining() >= length) {
                        val bytes = ByteArray(length)
                        buffer.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else null
                } else null

                return BitchatMessage(
                    id = id,
                    sender = sender,
                    content = content,
                    type = BitchatMessageType.Message,
                    timestamp = timestamp,
                    isRelay = isRelay,
                    originalSender = originalSender,
                    isPrivate = isPrivate,
                    recipientNickname = recipientNickname,
                    senderPeerID = senderPeerID,
                    mentions = mentions,
                    channel = channel,
                    encryptedContent = encryptedContent,
                    isEncrypted = isEncrypted
                )

            } catch (e: Exception) {
                return null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BitchatMessage

        if (id != other.id) return false
        if (sender != other.sender) return false
        if (content != other.content) return false
        if (type != other.type) return false
        if (timestamp != other.timestamp) return false
        if (isRelay != other.isRelay) return false
        if (originalSender != other.originalSender) return false
        if (isPrivate != other.isPrivate) return false
        if (recipientNickname != other.recipientNickname) return false
        if (senderPeerID != other.senderPeerID) return false
        if (mentions != other.mentions) return false
        if (channel != other.channel) return false
        if (encryptedContent != null) {
            if (other.encryptedContent == null) return false
            if (!encryptedContent.contentEquals(other.encryptedContent)) return false
        } else if (other.encryptedContent != null) return false
        if (isEncrypted != other.isEncrypted) return false
        if (deliveryStatus != other.deliveryStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sender.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isRelay.hashCode()
        result = 31 * result + (originalSender?.hashCode() ?: 0)
        result = 31 * result + isPrivate.hashCode()
        result = 31 * result + (recipientNickname?.hashCode() ?: 0)
        result = 31 * result + (senderPeerID?.hashCode() ?: 0)
        result = 31 * result + (mentions?.hashCode() ?: 0)
        result = 31 * result + (channel?.hashCode() ?: 0)
        result = 31 * result + (encryptedContent?.contentHashCode() ?: 0)
        result = 31 * result + isEncrypted.hashCode()
        result = 31 * result + (deliveryStatus?.hashCode() ?: 0)
        return result
    }
}


