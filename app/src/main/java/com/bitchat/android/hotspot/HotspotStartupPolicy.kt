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

    const val P2P_DISABLED_MESSAGE =
        "Wi-Fi Direct is unavailable. Turn Wi-Fi off and back on, then try again."
    /** Marks groups this app creates. Shared with [HotspotManager] so the two cannot drift. */
    const val SSID_PREFIX = "DIRECT-BC-" // BC for BitChat

    const val P2P_UNSUPPORTED_MESSAGE = "Wi-Fi Direct is not supported on this device."
    const val FOREIGN_GROUP_MESSAGE =
        "Another app is using Wi-Fi Direct. Close it and try again."
    const val P2P_BUSY_MESSAGE = "Wi-Fi Direct is busy. Please try again in a moment."
    const val GENERIC_FAILURE_MESSAGE = "Failed to start the hotspot. Please try again."

    sealed interface Decision {
        data class Retry(val delayMillis: Long) : Decision
        data class Fail(val message: String) : Decision
    }

    sealed interface StartAction {
        data object Create : StartAction
        data object RemoveStaleGroupThenCreate : StartAction
        data class Fail(val message: String) : StartAction
    }

    /**
     * Decides what to do before the first group-creation attempt.
     *
     * A P2P group outlives the process that created it, so an app killed while
     * hosting leaves an orphan behind. The framework rejects createGroup with BUSY
     * while any group exists, and no amount of retrying clears it.
     *
     * Wi-Fi Direct is shared with Cast, Android Auto and Quick Share, so only groups
     * we can show are ours get torn down.
     *
     * @param existingGroupName network name of the group already present, or null
     * @param ownedGroupName last group name this app recorded creating, or null
     */
    fun startAction(
        p2pState: Int?,
        existingGroupName: String?,
        ownedGroupName: String?
    ): StartAction = when {
        p2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED -> StartAction.Fail(P2P_DISABLED_MESSAGE)
        existingGroupName == null -> StartAction.Create
        isOurs(existingGroupName, ownedGroupName) -> StartAction.RemoveStaleGroupThenCreate
        else -> StartAction.Fail(FOREIGN_GROUP_MESSAGE)
    }

    /**
     * Primary signal is the name we recorded creating. The SSID prefix is only a
     * fallback, covering orphans left by builds that predate that record.
     */
    private fun isOurs(existingGroupName: String, ownedGroupName: String?): Boolean =
        existingGroupName == ownedGroupName || existingGroupName.startsWith(SSID_PREFIX)

    /**
     * @param reason a [WifiP2pManager] failure reason from `ActionListener.onFailure`
     * @param attempt 1-based attempt that just failed
     * @param p2pState last known [WifiP2pManager.EXTRA_WIFI_STATE], or null if no
     *   state broadcast has arrived yet
     */
    fun decide(reason: Int, attempt: Int, p2pState: Int?): Decision = when {
        reason == WifiP2pManager.P2P_UNSUPPORTED -> Decision.Fail(P2P_UNSUPPORTED_MESSAGE)

        reason != WifiP2pManager.BUSY -> Decision.Fail(GENERIC_FAILURE_MESSAGE)

        // BUSY is the framework's catch-all reply when the P2P state machine is
        // disabled, so retrying cannot help — surface something actionable instead.
        p2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED -> Decision.Fail(P2P_DISABLED_MESSAGE)

        attempt >= MAX_ATTEMPTS -> Decision.Fail(P2P_BUSY_MESSAGE)

        else -> Decision.Retry(retryDelayMillis(attempt))
    }

    private fun retryDelayMillis(attempt: Int): Long =
        (INITIAL_RETRY_DELAY_MILLIS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
}
