package com.bitchat.android.services

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Persistent store for message IDs we've already acknowledged as delivered, read locally, or
 * admitted to a completed read-receipt send window.
 *
 * Local read state must not be used as proof that a read-receipt packet reached the sender.
 * Transport delivery is best-effort and retryable, while local read state drives unread UI.
 * Limits to last MAX_IDS entries per set to avoid memory bloat.
 */
class SeenMessageStore private constructor(private val context: Context) {
    companion object {
        private const val TAG = "SeenMessageStore"
        private const val STORAGE_KEY = "seen_message_store_v1"
        private const val MAX_IDS = com.bitchat.android.util.AppConstants.Services.SEEN_MESSAGE_MAX_IDS

        // The constructor always receives applicationContext, so process lifetime is intentional.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: SeenMessageStore? = null
        fun getInstance(appContext: Context): SeenMessageStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SeenMessageStore(appContext.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private val secure = SecureIdentityStateManager(context)

    private val delivered = LinkedHashSet<String>(MAX_IDS)
    private val locallyRead = LinkedHashSet<String>(MAX_IDS)
    private val readReceiptsSent = LinkedHashSet<String>(MAX_IDS)

    init { load() }

    @Synchronized fun hasDelivered(id: String) = delivered.contains(id)
    @Synchronized fun hasBeenReadLocally(id: String) = locallyRead.contains(id)
    @Synchronized fun hasReadReceiptBeenSent(id: String) = readReceiptsSent.contains(id)

    @Synchronized fun markDelivered(id: String) {
        if (delivered.remove(id)) delivered.add(id) else {
            delivered.add(id)
            trim(delivered)
        }
        persist()
    }

    @Synchronized fun markReadLocally(id: String) {
        if (locallyRead.remove(id)) locallyRead.add(id) else {
            locallyRead.add(id)
            trim(locallyRead)
        }
        AppStateStore.markPrivateMessageRead(id)
        persist()
    }

    @Synchronized fun markReadReceiptSent(id: String) {
        if (readReceiptsSent.remove(id)) readReceiptsSent.add(id) else {
            readReceiptsSent.add(id)
            trim(readReceiptsSent)
        }
        persist()
    }

    @Synchronized fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        delivered.removeAll(ids)
        locallyRead.removeAll(ids)
        readReceiptsSent.removeAll(ids)
        persist()
    }

    @Synchronized fun clear() {
        delivered.clear()
        locallyRead.clear()
        readReceiptsSent.clear()
        persist()
    }

    private fun trim(set: LinkedHashSet<String>) {
        if (set.size <= MAX_IDS) return
        val it = set.iterator()
        while (set.size > MAX_IDS && it.hasNext()) {
            it.next(); it.remove()
        }
    }

    @Synchronized private fun load() {
        try {
            val json = secure.getSecureValue(STORAGE_KEY) ?: return
            val data = gson.fromJson(json, StorePayload::class.java) ?: return
            delivered.clear(); locallyRead.clear(); readReceiptsSent.clear()
            data.delivered.takeLast(MAX_IDS).forEach { delivered.add(it) }
            data.locallyRead.takeLast(MAX_IDS).forEach { locallyRead.add(it) }
            // Older payloads used the local-read set to suppress receipt sends. Seed the new
            // explicit set once during migration to avoid replaying an entire chat history.
            (data.readReceiptsSent ?: data.locallyRead)
                .takeLast(MAX_IDS)
                .forEach { readReceiptsSent.add(it) }
            Log.d(
                TAG,
                "Loaded delivered=${delivered.size}, locallyRead=${locallyRead.size}, " +
                    "readReceiptsSent=${readReceiptsSent.size}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SeenMessageStore: ${e.message}")
        }
    }

    @Synchronized private fun persist() {
        try {
            val payload = StorePayload(
                delivered = delivered.toList(),
                locallyRead = locallyRead.toList(),
                readReceiptsSent = readReceiptsSent.toList()
            )
            val json = gson.toJson(payload)
            secure.storeSecureValue(STORAGE_KEY, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
        }
    }

    private data class StorePayload(
        val delivered: List<String> = emptyList(),
        // Keep the existing JSON field name for backward-compatible secure-store migration.
        @SerializedName("read")
        val locallyRead: List<String> = emptyList(),
        @SerializedName("read_receipts_sent")
        val readReceiptsSent: List<String>? = null
    )
}
