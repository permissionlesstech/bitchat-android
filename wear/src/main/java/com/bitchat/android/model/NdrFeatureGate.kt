package com.bitchat.android.model

import com.bitchat.watch.BuildConfig

/**
 * Wear OS does not currently ship the Nostr double-ratchet runtime.
 *
 * Keep shared mesh consumers fail-closed while retaining the debug-only override used by the
 * shared JVM tests. Production Wear builds can never advertise or accept NDR through this gate.
 */
object NdrFeatureGate {
    @Volatile
    private var debugTestOverride = false

    fun isEnabled(): Boolean = BuildConfig.DEBUG && debugTestOverride

    internal fun setEnabledForTests(enabled: Boolean) {
        check(BuildConfig.DEBUG) { "The NDR test override is unavailable in release builds" }
        debugTestOverride = enabled
    }
}
