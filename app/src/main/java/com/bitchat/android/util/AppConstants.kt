package com.bitchat.android.util

/**
 * Centralized application-wide constants.
 */
object AppConstants {
    // Peer staleness threshold (ms). Keep consistent across mesh and sync layers.
    const val STALE_PEER_TIMEOUT_MS: Long = 180_000L // 3 minutes
}

