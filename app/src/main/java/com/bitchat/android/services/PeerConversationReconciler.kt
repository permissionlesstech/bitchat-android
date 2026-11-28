package com.bitchat.android.services

/** A serialized peer observer only reconciles identities whose routing aliases changed. */
internal class PeerConversationReconciler(
    private val merge: (List<PeerAliases>) -> Unit
) {
    data class PeerAliases(val peerID: String, val conversationID: String, val aliases: Set<String>)

    private var previous = emptyMap<String, PeerAliases>()

    fun reconcile(peers: List<PeerAliases>) {
        val current = peers.associateBy { it.peerID }
        val changed = current.values.filter { previous[it.peerID] != it }
        if (changed.isNotEmpty()) merge(changed)
        previous = current
    }
}
