package com.bitchat.android.model

import com.bitchat.android.BuildConfig

/**
 * Coordinated rollout gate for Nostr double-ratchet transport.
 *
 * Compatible mutual favorites negotiate this transport over authenticated Noise.
 * Peers without the capability retain the existing relay envelope.
 */
object NdrFeatureGate {
    @Volatile
    private var debugTestOverride: Boolean? = null

    fun isEnabled(): Boolean =
        if (BuildConfig.DEBUG) debugTestOverride ?: BuildConfig.NDR_ROLLOUT_ENABLED
        else BuildConfig.NDR_ROLLOUT_ENABLED

    internal fun setEnabledForTests(enabled: Boolean) {
        check(BuildConfig.DEBUG) { "The NDR test override is unavailable in release builds" }
        debugTestOverride = enabled
    }
}
