package com.bitchat.android.mesh

object BlePacketBudget {
    private const val ATT_PAYLOAD_OVERHEAD_BYTES = 3
    private const val DEFAULT_PACKET_LIMIT_BYTES = 182
    private const val MIN_PACKET_LIMIT_BYTES = 20

    fun packetLimitBytesForMtu(mtu: Int?): Int {
        val payloadBytes = (mtu ?: (DEFAULT_PACKET_LIMIT_BYTES + ATT_PAYLOAD_OVERHEAD_BYTES)) -
            ATT_PAYLOAD_OVERHEAD_BYTES
        return payloadBytes.coerceAtLeast(MIN_PACKET_LIMIT_BYTES)
    }
}
