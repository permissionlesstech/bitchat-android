package com.bitchat.android.model

import com.bitchat.android.sync.SyncDefaults

/**
 * REQUEST_SYNC payload using GCS (Golomb-Coded Set) parameters.
 * TLV (type, length16, value), types:
 *  - 0x01: P (uint8) — Golomb-Rice parameter
 *  - 0x02: M (uint32, big-endian) — hash range (N * 2^P)
 *  - 0x03: data (opaque) — GR bitstream bytes
 */
data class RequestSyncPacket(
    val p: Int,
    val m: Long,
    val data: ByteArray
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
        // P
        putTLV(0x01, byteArrayOf(p.toByte()))
        // M (uint32)
        val m32 = m.coerceAtMost(0xffff_ffffL)
        putTLV(
            0x02,
            byteArrayOf(
                ((m32 ushr 24) and 0xFF).toByte(),
                ((m32 ushr 16) and 0xFF).toByte(),
                ((m32 ushr 8) and 0xFF).toByte(),
                (m32 and 0xFF).toByte()
            )
        )
        // data
        putTLV(0x03, data)
        return out.toByteArray()
    }

    companion object {
        // Receiver-side safety limit (configurable constant)
        const val MAX_ACCEPT_FILTER_BYTES: Int = SyncDefaults.MAX_ACCEPT_FILTER_BYTES

        fun decode(data: ByteArray): RequestSyncPacket? {
            var off = 0
            var p: Int? = null
            var m: Long? = null
            var payload: ByteArray? = null

            while (off + 3 <= data.size) {
                val t = (data[off].toInt() and 0xFF); off += 1
                val len = ((data[off].toInt() and 0xFF) shl 8) or (data[off+1].toInt() and 0xFF); off += 2
                if (off + len > data.size) return null
                val v = data.copyOfRange(off, off + len); off += len
                when (t) {
                    0x01 -> if (len == 1) p = (v[0].toInt() and 0xFF)
                    0x02 -> if (len == 4) {
                        val mm = ((v[0].toLong() and 0xFF) shl 24) or
                                 ((v[1].toLong() and 0xFF) shl 16) or
                                 ((v[2].toLong() and 0xFF) shl 8) or
                                 (v[3].toLong() and 0xFF)
                        m = mm
                    }
                    0x03 -> {
                        if (v.size > MAX_ACCEPT_FILTER_BYTES) return null
                        payload = v
                    }
                }
            }

            val pp = p ?: return null
            val mm = m ?: return null
            val dd = payload ?: return null
            if (pp < 1 || mm <= 0L) return null
            return RequestSyncPacket(pp, mm, dd)
        }
    }
}
