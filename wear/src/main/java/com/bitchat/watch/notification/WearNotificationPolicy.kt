package com.bitchat.watch.notification

/**
 * Pure notification decisions shared by the watch service and unit tests.
 */
object WearNotificationPolicy {
    fun shouldNotifyPrivateMessage(
        senderPeerID: String,
        senderIsSystem: Boolean,
        appInForeground: Boolean,
        openDmPeer: String?
    ): Boolean {
        if (senderIsSystem) return false
        return !appInForeground || openDmPeer != senderPeerID
    }

    fun activePeerCount(peers: Collection<String>): Int = peers.distinct().size
}
