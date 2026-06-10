package com.bitchat.android.model

import com.bitchat.android.protocol.TlvLengthSize
import com.bitchat.android.protocol.TlvReader
import com.bitchat.android.protocol.TlvWriter
import com.bitchat.android.protocol.UnknownTlvPolicy
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
        val m32 = m.coerceAtMost(0xffff_ffffL)
        return TlvWriter()
            .put(0x01, byteArrayOf(p.toByte()), TlvLengthSize.TWO_BYTES)
            .put(
                0x02,
                byteArrayOf(
                    ((m32 ushr 24) and 0xFF).toByte(),
                    ((m32 ushr 16) and 0xFF).toByte(),
                    ((m32 ushr 8) and 0xFF).toByte(),
                    (m32 and 0xFF).toByte()
                ),
                TlvLengthSize.TWO_BYTES
            )
            .put(0x03, data, TlvLengthSize.TWO_BYTES)
            .toByteArray()
    }

    companion object {
        // Receiver-side safety limit (configurable constant)
        const val MAX_ACCEPT_FILTER_BYTES: Int = SyncDefaults.MAX_ACCEPT_FILTER_BYTES

        fun decode(data: ByteArray): RequestSyncPacket? {
            var p: Int? = null
            var m: Long? = null
            var payload: ByteArray? = null

            val fields = TlvReader.decode(
                data = data,
                defaultLengthSize = TlvLengthSize.TWO_BYTES,
                unknownPolicy = UnknownTlvPolicy.SKIP,
                knownTypes = setOf(0x01, 0x02, 0x03)
            ) ?: return null

            for (field in fields) {
                when (field.type) {
                    0x01 -> if (field.value.size == 1) p = (field.value[0].toInt() and 0xFF)
                    0x02 -> if (field.value.size == 4) {
                        val mm = ((field.value[0].toLong() and 0xFF) shl 24) or
                                 ((field.value[1].toLong() and 0xFF) shl 16) or
                                 ((field.value[2].toLong() and 0xFF) shl 8) or
                                 (field.value[3].toLong() and 0xFF)
                        m = mm
                    }
                    0x03 -> {
                        if (field.value.size > MAX_ACCEPT_FILTER_BYTES) return null
                        payload = field.value
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
