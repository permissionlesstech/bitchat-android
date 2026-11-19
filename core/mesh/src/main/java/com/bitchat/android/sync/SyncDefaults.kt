package com.bitchat.android.sync

object SyncDefaults {
    // Default values used when debug prefs are unavailable
    const val DEFAULT_FILTER_BYTES: Int = 256
    const val DEFAULT_FPR_PERCENT: Double = 1.0

    // Receiver-side hard cap to avoid DoS (also enforced in RequestSyncPacket)
    const val MAX_ACCEPT_FILTER_BYTES: Int = 1024
}

