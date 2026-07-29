package com.bitchat.watch.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide UI state for the watch app: unread DM counters and the currently open DM thread.
 */
object WearChatState {
    private val _unreadDms = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadDms: StateFlow<Map<String, Int>> = _unreadDms.asStateFlow()

    @Volatile
    var appInForeground: Boolean = false
        private set

    @Volatile
    var openDmPeer: String? = null

    @Synchronized
    fun onPrivateMessageArrived(peerID: String) {
        if (appInForeground && openDmPeer == peerID) return
        _unreadDms.value = _unreadDms.value + (peerID to ((_unreadDms.value[peerID] ?: 0) + 1))
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
    }

    @Synchronized
    fun openDm(peerID: String) {
        openDmPeer = peerID
        _unreadDms.value = _unreadDms.value - peerID
    }

    @Synchronized
    fun closeDm() {
        openDmPeer = null
    }

    fun unreadCount(peerID: String): Int = _unreadDms.value[peerID] ?: 0
}
