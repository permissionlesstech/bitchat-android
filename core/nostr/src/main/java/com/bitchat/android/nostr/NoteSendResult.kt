package com.bitchat.android.nostr

/** What [LocationNotesManager.send] did with a note. */
sealed interface NoteSendResult {
    /** The note was handed to the relays. */
    data object Sent : NoteSendResult

    /** The note was not sent; the manager's error and state say so where they did before. */
    data class Failed(val reason: Reason) : NoteSendResult

    enum class Reason {
        PRIVACY_GATE_CLOSED,
        NO_LOCATION,
        NO_RELAYS,
        NOT_INITIALIZED,
        NO_IDENTITY,
        NOT_SENT,
        ERROR,
    }
}
