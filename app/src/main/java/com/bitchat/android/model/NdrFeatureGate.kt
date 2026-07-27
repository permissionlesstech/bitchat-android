package com.bitchat.android.model

import com.bitchat.android.BuildConfig

/**
 * Coordinated rollout gate for Nostr double-ratchet transport.
 *
 * Production builds stay fail-closed until the pairwise NDR implementations
 * are reviewed and ready to be enabled together on Apple and Android.
 */
object NdrFeatureGate {
    @Volatile
    private var debugTestOverride = false

    fun isEnabled(): Boolean =
        BuildConfig.NDR_ROLLOUT_ENABLED || (BuildConfig.DEBUG && debugTestOverride)

    internal fun setEnabledForTests(enabled: Boolean) {
        check(BuildConfig.DEBUG) { "The NDR test override is unavailable in release builds" }
        debugTestOverride = enabled
    }
}
