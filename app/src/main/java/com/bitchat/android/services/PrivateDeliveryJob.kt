package com.bitchat.android.services

/** Persisted delivery intent. Payloads (including relay events) are encrypted by the repository. */
data class PrivateDeliveryJob(
    val id: String,
    val conversationID: String,
    val messageID: String,
    val kind: Kind,
    val content: String = "",
    val nickname: String = "",
    val recipientPubkey: String? = null,
    val sourceGeohash: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val nextAttemptAt: Long = 0,
    val attempts: Int = 0,
    val localMessageID: String? = null,
    val sourceIdentityPubkey: String? = null,
    val eventJson: String? = null
) {
    enum class Kind { MESSAGE, FAVORITE, DELIVERED, READ }

    companion object {
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val MESH_ACK_TIMEOUT_MS = 30_000L
        const val MAX_PER_CONTACT = 100
        const val MAX_TOTAL = 500
    }
}
