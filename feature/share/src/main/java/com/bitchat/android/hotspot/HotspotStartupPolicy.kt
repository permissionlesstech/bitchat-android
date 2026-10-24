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
    const val P2P_BUSY_MESSAGE = "Wi-Fi Direct is busy. Please try again in a moment."
    const val GENERIC_FAILURE_MESSAGE = "Failed to start the hotspot. Please try again."

    sealed interface Decision {
        data class Retry(val delayMillis: Long) : Decision
        data class Fail(val message: String) : Decision
    }

    sealed interface StartAction {
        data object Create : StartAction
        data object RemoveStaleGroupThenCreate : StartAction

        /** A group we cannot show is ours is up; ask the user before disturbing it. */
        data object ConfirmReplaceExisting : StartAction

        data class Fail(val message: String) : StartAction
    }

    /**
     * Decides what to do before the first group-creation attempt.
     *
     * A P2P group outlives the process that created it, so an app killed while
     * hosting leaves an orphan behind. The framework rejects createGroup with BUSY
     * while any group exists, and no amount of retrying clears it.
     *
     * Wi-Fi Direct is shared with Cast, Android Auto and Quick Share. A group we can
     * show is ours is removed silently; anything else needs the user's explicit
     * go-ahead before it is touched. Consent is bound to the group it was given
     * for: a group with any other name — one that appeared after the dialog, or
     * mid-retry — asks again instead of riding on stale approval.
     *
     * @param existingGroupName network name of the group already present, or null
     * @param ownedGroupName last group name this app recorded creating, or null
     * @param confirmedGroupName group the user agreed to disconnect, or null
     */
    fun startAction(
        p2pState: Int?,
        existingGroupName: String?,
        ownedGroupName: String?,
        confirmedGroupName: String?
    ): StartAction = when {
        p2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED -> StartAction.Fail(P2P_DISABLED_MESSAGE)
        existingGroupName == null -> StartAction.Create
        isOurs(existingGroupName, ownedGroupName) -> StartAction.RemoveStaleGroupThenCreate
        existingGroupName == confirmedGroupName -> StartAction.RemoveStaleGroupThenCreate
        else -> StartAction.ConfirmReplaceExisting
    }

    /**
     * Only the exact name this device recorded creating counts as ours. A prefix
     * match is not ownership: this device can be connected to another phone's
     * bitchat group — same prefix, their suffix — and silently removing it would
     * disconnect that session. An orphan predating the record simply goes through
     * the confirmation dialog once.
     */
    private fun isOurs(existingGroupName: String, ownedGroupName: String?): Boolean =
        ownedGroupName != null && existingGroupName == ownedGroupName

    /** Only an exact owner-role name match authorizes device-scoped removal. */
    fun isExpectedHostedGroup(
        existingGroupName: String?,
        isGroupOwner: Boolean,
        expectedGroupName: String?
    ): Boolean =
        isGroupOwner &&
            expectedGroupName != null &&
            existingGroupName == expectedGroupName

    /** A stale teardown must not erase the ownership marker of a newer session. */
    fun shouldClearOwnedGroupName(
        storedGroupName: String?,
        removedGroupName: String?
    ): Boolean =
        removedGroupName != null && storedGroupName == removedGroupName

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
