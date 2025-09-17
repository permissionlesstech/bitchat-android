package com.bitchat.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BitchatFilePacket: TLV-encoded file transfer payload for BLE mesh.
 * TLVs:
 *  - 0x01: filename (UTF-8)
 *  - 0x02: file size (8 bytes, UInt64)
 *  - 0x03: mime type (UTF-8)
 *  - 0x04: content (bytes)
 * Length field for TLV is 2 bytes (UInt16, big-endian).
 * Keep total payload <= 64 KiB to satisfy base protocol header (will be fragmented automatically afterwards).
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
        try {
            android.util.Log.d("BitchatFilePacket", "🔄 Encoding: name=$fileName, size=$fileSize, mime=$mimeType")
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        // Validate bounds for 2-byte TLV lengths
        if (nameBytes.size > 0xFFFF || mimeBytes.size > 0xFFFF || content.size > 0xFFFF) {
                android.util.Log.e("BitchatFilePacket", "❌ TLV field too large: name=${nameBytes.size}, mime=${mimeBytes.size}, content=${content.size} (max: 65535)")
                return null
            }
            android.util.Log.d("BitchatFilePacket", "📏 TLV sizes OK: name=${nameBytes.size}, mime=${mimeBytes.size}, content=${content.size}")
        val sizeFieldLen = 8 // UInt64
        val capacity = 1 + 2 + nameBytes.size + 1 + 2 + sizeFieldLen + 1 + 2 + mimeBytes.size + 1 + 2 + content.size
        val buf = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

        // FILE_NAME
        buf.put(TLVType.FILE_NAME.v.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)

        // FILE_SIZE (8 bytes)
        buf.put(TLVType.FILE_SIZE.v.toByte())
        buf.putShort(sizeFieldLen.toShort())
        buf.putLong(fileSize)

        // MIME_TYPE
        buf.put(TLVType.MIME_TYPE.v.toByte())
        buf.putShort(mimeBytes.size.toShort())
        buf.put(mimeBytes)

        // CONTENT
        buf.put(TLVType.CONTENT.v.toByte())
        buf.putShort(content.size.toShort())
        buf.put(content)

        val result = buf.array()
            android.util.Log.d("BitchatFilePacket", "✅ Encoded successfully: ${result.size} bytes total")
            return result
        } catch (e: Exception) {
            android.util.Log.e("BitchatFilePacket", "❌ Encoding failed: ${e.message}", e)
            return null
        }
    }

    companion object {
        fun decode(data: ByteArray): BitchatFilePacket? {
            android.util.Log.d("BitchatFilePacket", "🔄 Decoding ${data.size} bytes")
            try {
                var off = 0
                var name: String? = null
                var size: Long? = null
                var mime: String? = null
                var content: ByteArray? = null
                while (off + 3 <= data.size) {
                    val t = TLVType.from(data[off].toUByte()) ?: return null
                    off += 1
                    val len = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                    off += 2
                    if (off + len > data.size) return null
                    val value = data.copyOfRange(off, off + len)
                    off += len
                    when (t) {
                        TLVType.FILE_NAME -> name = String(value, Charsets.UTF_8)
                        TLVType.FILE_SIZE -> {
                            if (len != 8) return null
                            val bb = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN)
                            size = bb.long
                        }
                        TLVType.MIME_TYPE -> mime = String(value, Charsets.UTF_8)
                        TLVType.CONTENT -> content = value
                    }
                }
                val n = name ?: return null
                val s = size ?: content?.size?.toLong() ?: return null
                val m = mime ?: "application/octet-stream"
                val c = content ?: return null
                val result = BitchatFilePacket(n, s, m, c)
                android.util.Log.d("BitchatFilePacket", "✅ Decoded: name=$n, size=$s, mime=$m, content=${c.size} bytes")
                return result
            } catch (e: Exception) {
                android.util.Log.e("BitchatFilePacket", "❌ Decoding failed: ${e.message}", e)
                return null
            }
        }
    }
}

