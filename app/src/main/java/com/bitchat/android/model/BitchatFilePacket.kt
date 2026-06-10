package com.bitchat.android.model

import com.bitchat.android.protocol.TlvLengthSize
import com.bitchat.android.protocol.TlvReader
import com.bitchat.android.protocol.TlvWriter
import com.bitchat.android.protocol.UnknownTlvPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BitchatFilePacket: TLV-encoded file transfer payload for BLE mesh.
 * TLVs:
 *  - 0x01: filename (UTF-8)
 *  - 0x02: file size (4 bytes, UInt32)
 *  - 0x03: mime type (UTF-8)
 *  - 0x04: content (bytes)
 *
 * FILE_NAME, FILE_SIZE, and MIME_TYPE use 2-byte big-endian lengths.
 * CONTENT uses a 4-byte big-endian length so the outer v2 packet can carry
 * payloads larger than 64 KiB before transport fragmentation.
 */
data class BitchatFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    private enum class TLVType(val v: UByte) {
        FILE_NAME(0x01u), FILE_SIZE(0x02u), MIME_TYPE(0x03u), CONTENT(0x04u);
        companion object { fun from(value: UByte) = values().find { it.v == value } }
    }

    fun encode(): ByteArray? {
        return try {
            val nameBytes = fileName.toByteArray(Charsets.UTF_8)
            val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 0xFFFF || mimeBytes.size > 0xFFFF) {
                return null
            }

            val sizeBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(fileSize.toInt())
                .array()

            TlvWriter()
                .put(TLVType.FILE_NAME.v.toInt(), nameBytes, TlvLengthSize.TWO_BYTES)
                .put(TLVType.FILE_SIZE.v.toInt(), sizeBytes, TlvLengthSize.TWO_BYTES)
                .put(TLVType.MIME_TYPE.v.toInt(), mimeBytes, TlvLengthSize.TWO_BYTES)
                .put(TLVType.CONTENT.v.toInt(), content, TlvLengthSize.FOUR_BYTES)
                .toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun decode(data: ByteArray): BitchatFilePacket? {
            try {
                val knownTypes = TLVType.values().map { it.v.toInt() }.toSet()
                val fields = TlvReader.decode(
                    data = data,
                    defaultLengthSize = TlvLengthSize.TWO_BYTES,
                    unknownPolicy = UnknownTlvPolicy.FAIL,
                    knownTypes = knownTypes
                ) { type ->
                    if (type == TLVType.CONTENT.v.toInt()) TlvLengthSize.FOUR_BYTES else TlvLengthSize.TWO_BYTES
                } ?: return null

                var name: String? = null
                var size: Long? = null
                var mime: String? = null
                var contentBytes: ByteArray? = null
                for (field in fields) {
                    val t = TLVType.from(field.type.toUByte()) ?: return null
                    val value = field.value
                    when (t) {
                        TLVType.FILE_NAME -> name = String(value, Charsets.UTF_8)
                        TLVType.FILE_SIZE -> {
                            if (value.size != 4) return null
                            val bb = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
                            size = bb.int.toLong()
                        }
                        TLVType.MIME_TYPE -> mime = String(value, Charsets.UTF_8)
                        TLVType.CONTENT -> {
                            // Expect a single CONTENT TLV
                            if (contentBytes == null) contentBytes = value else {
                                // If multiple CONTENT TLVs appear, concatenate for tolerance
                                contentBytes = (contentBytes!! + value)
                            }
                        }
                    }
                }
                val n = name ?: return null
                val c = contentBytes ?: return null
                val s = size ?: c.size.toLong()
                val m = mime ?: "application/octet-stream"
                return BitchatFilePacket(n, s, m, c)
            } catch (e: Exception) {
                return null
            }
        }
    }
}
