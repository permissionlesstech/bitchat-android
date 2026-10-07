package com.bitchat.android.mesh

/** Process-level group ingress, independent of Activity and transport lifetimes. */
interface GroupMessageReceiver {
    fun invite(peerID: String, authenticatedKey: ByteArray, payload: ByteArray)
    fun keyUpdate(peerID: String, authenticatedKey: ByteArray, payload: ByteArray)
    fun message(payload: ByteArray, timestampMs: Long)
    fun peerAuthenticated(peerID: String)
}

object GroupMessagePort {
    @Volatile var receiver: GroupMessageReceiver? = null
}
