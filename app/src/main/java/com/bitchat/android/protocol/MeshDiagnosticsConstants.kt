package com.bitchat.android.protocol

object MeshDiagnosticsConstants {
    val PING_TYPE: UByte = 0x26u
    val PONG_TYPE: UByte = 0x27u
    val TTL: UByte = 7u
    const val NONCE_SIZE = 8
    const val PAYLOAD_SIZE = NONCE_SIZE + 1
    const val TIMEOUT_MILLIS = 10_000L
    const val INBOUND_RATE_LIMIT = 5
    const val INBOUND_RATE_WINDOW_MILLIS = 10_000L
    const val RELAY_JITTER_MIN_MILLIS = 20L
    const val RELAY_JITTER_MAX_MILLIS = 60L
    const val CAPABILITY_BIT = 6
}
