package com.bitchat.android.ui

/**
 * What a Back press should unwind next on the chat screen.
 *
 * Sheets and screens are back-stack entries and pop themselves. What remains
 * is state the chat screen shows in place: the join-password dialog, a channel,
 * and a private chat started with /msg, which selects a conversation without
 * opening its screen.
 */
enum class BackAction {
    DismissPasswordPrompt,
    ExitPrivateChat,
    ExitChannel,

    /** Nothing is open, so the press belongs to the system and usually exits. */
    None
}

/**
 * The unwind order, over plain values.
 *
 * The declaration order of these branches *is* the order Back unwinds in, and is
 * what ChatStateBackNavigationTest pins. It takes values rather than reading
 * [ChatState] so the same decision can be reached from the current values or from
 * a flow.
 */
internal fun backActionFor(
    showPasswordPrompt: Boolean,
    selectedPrivateChatPeer: String?,
    currentChannel: String?
): BackAction = when {
    showPasswordPrompt -> BackAction.DismissPasswordPrompt
    selectedPrivateChatPeer != null -> BackAction.ExitPrivateChat
    currentChannel != null -> BackAction.ExitChannel
    else -> BackAction.None
}

/**
 * Names what Back would unwind without unwinding it.
 *
 * Kept out of [ChatBackUnwinder.handle] so the ordering is decidable from
 * state alone, without the session's managers the unwinder needs.
 */
fun ChatState.pendingBackAction(): BackAction = backActionFor(
    showPasswordPrompt = getShowPasswordPromptValue(),
    selectedPrivateChatPeer = getSelectedPrivateChatPeerValue(),
    currentChannel = getCurrentChannelValue()
)
