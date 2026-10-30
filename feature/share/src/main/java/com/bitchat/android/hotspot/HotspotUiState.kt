package com.bitchat.android.hotspot

/** What the hotspot screen shows. */
sealed interface HotspotUiState {
    data object Intro : HotspotUiState
    data object Starting : HotspotUiState

    /** A foreign Wi-Fi Direct group is up; waiting for the user's go-ahead. */
    data object ConfirmDisconnect : HotspotUiState

    data class Active(
        val ssid: String,
        val password: String,
        val ipAddress: String,
        val port: Int,
        val connectedPeers: Int
    ) : HotspotUiState

    data class Error(val message: String) : HotspotUiState
}

/** What the user (or the Activity) asks the hotspot screen to do. */
sealed interface HotspotAction {
    /** Start, once the required permissions are granted. */
    data object StartClicked : HotspotAction

    /** The user agreed that starting may disconnect the existing Wi-Fi Direct group. */
    data object ConfirmDisconnect : HotspotAction

    /** The disconnect dialog was cancelled or dismissed. */
    data object CancelDisconnect : HotspotAction

    /** "Try Again" on the error screen. */
    data object RetryClicked : HotspotAction

    /** The top bar's close button or the error screen's "Close". */
    data object CloseClicked : HotspotAction

    /** The Activity's `STOP_HOTSPOT` intent. */
    data object StopRequested : HotspotAction
}

/** One-shot work the Activity does for the ViewModel. */
sealed interface HotspotEvent {
    data object Close : HotspotEvent
}
