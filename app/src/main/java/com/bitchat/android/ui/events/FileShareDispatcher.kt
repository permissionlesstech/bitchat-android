package com.bitchat.android.ui.events

/**
 * Lightweight dispatcher so lower-level UI (MessageInput) can trigger
 * file sending without holding a direct reference to ChatViewModel.
 */
object FileShareDispatcher {
    @Volatile private var handler: ((String?, String?, String) -> Unit)? = null

    fun setHandler(h: ((String?, String?, String) -> Unit)?) {
        handler = h
    }

    fun dispatch(peerIdOrNull: String?, channelOrNull: String?, path: String) {
        handler?.invoke(peerIdOrNull, channelOrNull, path)
    }
}
