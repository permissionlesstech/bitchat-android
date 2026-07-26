package com.bitchat.android.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitchat.android.model.BitchatMessage

/**
 * Consecutive-message grouping for the chat surface.
 *
 * The redesign suppresses the `@sender` label on runs of messages from the same author so the
 * eye only has to register a name when the speaker actually changes. Grouped messages sit
 * closer together; a new group gets extra breathing room above it.
 */
object MessageGrouping {

    /** Space above the first message of a group (i.e. one that renders its sender label). */
    val NEW_GROUP_SPACING: Dp = 10.dp

    /** Space above a continuation message inside an existing group. */
    val GROUPED_SPACING: Dp = 4.dp

    /** Gap between the sender label and the first line of the body. */
    val SENDER_TO_BODY_SPACING: Dp = 2.dp

    /**
     * Messages further apart than this always start a new group, even from the same sender:
     * a reply an hour later is a new thought, not a continuation.
     */
    const val GROUPING_WINDOW_MS: Long = 5 * 60 * 1000L

    private const val SYSTEM_SENDER = "system"

    /**
     * Whether [current] should be rendered as a continuation of [previous], hiding its sender
     * label.
     *
     * [previous] is the message immediately *before* [current] in chronological order. Note the
     * message list renders with `reverseLayout = true`, so callers must be careful to resolve
     * the chronological predecessor rather than the visually preceding item.
     */
    fun shouldGroup(previous: BitchatMessage?, current: BitchatMessage): Boolean {
        if (previous == null) return false

        // System/action lines never participate in grouping in either direction: they are
        // narration, and folding a real message into them would attribute it to "system".
        if (previous.sender == SYSTEM_SENDER || current.sender == SYSTEM_SENDER) return false

        // Never group across the public/private boundary or between different channels.
        if (previous.isPrivate != current.isPrivate) return false
        if (previous.channel != current.channel) return false

        if (!isSameSender(previous, current)) return false

        val elapsed = current.timestamp.time - previous.timestamp.time
        return elapsed in 0..GROUPING_WINDOW_MS
    }

    /**
     * Identity comparison. Peer IDs are authoritative when both messages carry one, because two
     * different peers can share a nickname. Falls back to the display name (which includes the
     * `#abcd` suffix) when peer IDs are unavailable, e.g. for locally injected messages.
     */
    private fun isSameSender(previous: BitchatMessage, current: BitchatMessage): Boolean {
        val previousPeerID = previous.senderPeerID
        val currentPeerID = current.senderPeerID
        return if (previousPeerID != null && currentPeerID != null) {
            previousPeerID.equals(currentPeerID, ignoreCase = true)
        } else {
            previous.sender == current.sender
        }
    }

    /** Top padding for a message given whether it continues the previous author's run. */
    fun topSpacingFor(isGrouped: Boolean, isFirstInList: Boolean): Dp = when {
        isFirstInList -> 0.dp
        isGrouped -> GROUPED_SPACING
        else -> NEW_GROUP_SPACING
    }
}
