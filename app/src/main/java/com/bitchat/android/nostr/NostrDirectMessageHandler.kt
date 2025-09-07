package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.PrivateChatManager
import kotlinx.coroutines.CoroutineScope

class NostrDirectMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val scope: CoroutineScope,
    private val geohashRepo: GeohashRepository
) {
    fun onGiftWrap(giftWrap: NostrEvent) { /* TODO: implement processing */ }
}

