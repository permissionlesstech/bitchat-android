package com.bitchat.android.hotspot

import android.net.wifi.p2p.WifiP2pManager

/**
 * Decides how to react to a Wi-Fi P2P group-creation failure.
 *
 * Kept free of Android dependencies so the retry strategy is unit testable.
 */
internal object HotspotStartupPolicy {

    const val MAX_ATTEMPTS = 5
    const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
    const val MAX_RETRY_DELAY_MILLIS = 8_000L

    sealed interface Decision {
        data class Retry(val delayMillis: Long) : Decision
        data class Fail(val error: HotspotError) : Decision
    }

    /**
     * @param reason a [WifiP2pManager] failure reason from `ActionListener.onFailure`
     * @param attempt 1-based attempt that just failed
     * @param p2pState last known [WifiP2pManager.EXTRA_WIFI_STATE], or null if no
     *   state broadcast has arrived yet
     */
    fun decide(reason: Int, attempt: Int, p2pState: Int?): Decision = when {
        reason == WifiP2pManager.P2P_UNSUPPORTED ->
            Decision.Fail(HotspotError.P2P_UNSUPPORTED)

        reason != WifiP2pManager.BUSY -> Decision.Fail(HotspotError.START_FAILED)

        // BUSY is the framework's catch-all reply when the P2P state machine is
        // disabled, so retrying cannot help — surface something actionable instead.
        p2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED ->
            Decision.Fail(HotspotError.P2P_DISABLED)

        attempt >= MAX_ATTEMPTS -> Decision.Fail(HotspotError.P2P_BUSY)

        else -> Decision.Retry(retryDelayMillis(attempt))
    }

    private fun retryDelayMillis(attempt: Int): Long =
        (INITIAL_RETRY_DELAY_MILLIS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
}
