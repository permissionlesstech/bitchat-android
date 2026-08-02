package com.bitchat.android.services

import com.bitchat.android.ui.ChatState

object ConversationAliasResolver {

    fun resolveCanonicalPeerID(
        selectedPeerID: String,
        connectedPeers: List<String>,
        meshNoiseKeyForPeer: (String) -> ByteArray?,
        nostrPubHexForAlias: (String) -> String?,
        findNoiseKeyForNostr: (String) -> ByteArray?
    ): String {
        var peer = selectedPeerID
        try {
            if (ContactIdentityResolver.isNostrAlias(peer)) {
                val pubHex = nostrPubHexForAlias(peer)
                if (pubHex != null) {
                    val noiseKey = findNoiseKeyForNostr(pubHex)
                    if (noiseKey != null) {
                        val noiseHex = ContactIdentityResolver.noiseKeyHex(noiseKey)
                        val meshPeer = connectedPeers.firstOrNull { pid ->
                            meshNoiseKeyForPeer(pid)?.contentEquals(noiseKey) == true
                        }
                        peer = meshPeer ?: noiseHex
                    }
                }
            } else if (ContactIdentityResolver.isNoiseKeyHex(peer)) {
                val noiseKey = ContactIdentityResolver.bytesFromHex(peer) ?: return peer
                val meshPeer = connectedPeers.firstOrNull { pid ->
                    meshNoiseKeyForPeer(pid)?.contentEquals(noiseKey) == true
                }
                if (meshPeer != null) {
                    peer = meshPeer
                }
            }
        } catch (_: Exception) { /* no-op */ }
        return ContactDirectory.canonicalConversationId(peer)
    }

    fun unifyChatsIntoPeer(
        state: ChatState,
        targetPeerID: String,
        keysToMerge: List<String>
    ) {
        if (keysToMerge.isEmpty()) return
        val targetConversationID = ContactDirectory.canonicalConversationId(targetPeerID)
        val mergeKeys = (keysToMerge + targetPeerID)
            .flatMap { ContactDirectory.aliasesForConversation(it) }
            .distinct()
        AppStateStore.unifyPrivateChatsIntoPeer(targetConversationID, mergeKeys)

        val currentChats = state.getPrivateChatsValue().toMutableMap()
        val targetList = currentChats[targetConversationID]?.toMutableList() ?: mutableListOf()
        var didMerge = false
        mergeKeys.forEach { key ->
            if (key == targetConversationID) return@forEach
            val list = currentChats[key]
            if (!list.isNullOrEmpty()) {
                targetList.addAll(list)
                currentChats.remove(key)
                didMerge = true
            }
        }
        if (didMerge) {
            currentChats[targetConversationID] = targetList
                .distinctBy { it.id }
            state.setPrivateChats(ContactDirectory.canonicalizePrivateChats(currentChats))

            // Move unread flags
            val unread = state.getUnreadPrivateMessagesValue().toMutableSet()
            var hadUnread = false
            mergeKeys.forEach { key -> if (unread.remove(key)) hadUnread = true }
            if (hadUnread) unread.add(targetConversationID)
            state.setUnreadPrivateMessages(unread)

            // Switch selection if currently viewing an alias that got merged
            val selected = state.getSelectedPrivateChatPeerValue()
            if (selected != null && mergeKeys.contains(selected)) {
                state.setSelectedPrivateChatPeer(targetConversationID)
            }
            
            // Switch sheet peer if currently viewing an alias that got merged
            val sheetPeer = state.getPrivateChatSheetPeerValue()
            if (sheetPeer != null && mergeKeys.contains(sheetPeer)) {
                state.setPrivateChatSheetPeer(targetConversationID)
            }
        }
    }
}
