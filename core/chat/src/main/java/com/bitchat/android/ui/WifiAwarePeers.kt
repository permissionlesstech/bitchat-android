package com.bitchat.android.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * The peers currently connected over Wi-Fi Aware, keyed by peer ID.
 *
 * Injected rather than read from the transport's controller directly, so the
 * screens that show it do not depend on where the transport lives.
 */
interface WifiAwarePeers {
    val connected: StateFlow<Map<String, *>>
}
