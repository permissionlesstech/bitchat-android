package com.bitchat.android.model

/**
 * REQUEST_SYNC payload with TLV (type, length16, value) encoding.
 * Fields:
 *  - 0x01: filter size in bytes (uint16)
 *  - 0x02: k (number of hash functions) (uint8)
 *  - 0x03: bloom filter bits (opaque bytes)
 */
data class RequestSyncPacket(
    val mBytes: Int,
    val k: Int,
    val bits: ByteArray
) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>()
        fun putTLV(t: Int, v: ByteArray) {
            out.add(t.toByte())
            val len = v.size
            out.add(((len ushr 8) and 0xFF).toByte())
            out.add((len and 0xFF).toByte())
            out.addAll(v.toList())
        }
        // mBytes
        putTLV(0x01, byteArrayOf(((mBytes ushr 8) and 0xFF).toByte(), (mBytes and 0xFF).toByte()))
        // k
        putTLV(0x02, byteArrayOf(k.toByte()))
        // bloom bits
        putTLV(0x03, bits)
        return out.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): RequestSyncPacket? {
            var off = 0
            var mBytes: Int? = null
            var k: Int? = null
            var bits: ByteArray? = null

            while (off + 3 <= data.size) {
                val t = (data[off].toInt() and 0xFF); off += 1
                val len = ((data[off].toInt() and 0xFF) shl 8) or (data[off+1].toInt() and 0xFF); off += 2
                if (off + len > data.size) return null
                val v = data.copyOfRange(off, off + len); off += len
                when (t) {
                    0x01 -> if (len == 2) mBytes = ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
                    0x02 -> if (len == 1) k = (v[0].toInt() and 0xFF)
                    0x03 -> bits = v
                }
            }

            val mb = mBytes ?: return null
            val kk = k ?: return null
            val bb = bits ?: return null
            return RequestSyncPacket(mb, kk, bb)
        }
    }
}

