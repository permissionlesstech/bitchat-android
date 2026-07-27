package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Persistent store for message IDs we've already acknowledged as delivered, read locally, or
 * admitted to a completed read-receipt send window, plus pairwise-ratchet events
 * durably committed before acknowledgement.
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
    private val ndrProcessed = LinkedHashSet<String>(MAX_IDS)

    init { load() }

    @Synchronized fun hasDelivered(id: String) = delivered.contains(id)
    @Synchronized fun hasBeenReadLocally(id: String) = locallyRead.contains(id)
    @Synchronized fun hasReadReceiptBeenSent(id: String) = readReceiptsSent.contains(id)
    @Synchronized fun hasProcessedNdr(id: String) = ndrProcessed.contains(id)

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
        persist()
    }

    @Synchronized fun markReadReceiptSent(id: String) {
        if (readReceiptsSent.remove(id)) readReceiptsSent.add(id) else {
            readReceiptsSent.add(id)
            trim(readReceiptsSent)
        }
        persist()
    }

    /**
     * Returns only after the processed marker is committed to encrypted
     * preferences. The pairwise action must not be acknowledged when this
     * returns false.
     */
    @Synchronized fun markProcessedNdr(id: String): Boolean {
        if (ndrProcessed.contains(id)) return true
        val previous = ndrProcessed.toList()
        ndrProcessed.add(id)
        trim(ndrProcessed)
        if (persistSynchronously()) return true
        ndrProcessed.clear()
        ndrProcessed.addAll(previous)
        return false
    }

    @Synchronized fun clear() {
        delivered.clear()
        locallyRead.clear()
        readReceiptsSent.clear()
        ndrProcessed.clear()
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
            delivered.clear()
            locallyRead.clear()
            readReceiptsSent.clear()
            ndrProcessed.clear()
            data.delivered.orEmpty().takeLast(MAX_IDS).forEach { delivered.add(it) }
            data.locallyRead.orEmpty().takeLast(MAX_IDS).forEach { locallyRead.add(it) }
            // Older payloads used the local-read set to suppress receipt sends. Seed the new
            // explicit set once during migration to avoid replaying an entire chat history.
            (data.readReceiptsSent ?: data.locallyRead.orEmpty())
                .takeLast(MAX_IDS)
                .forEach { readReceiptsSent.add(it) }
            data.ndrProcessed.orEmpty().takeLast(MAX_IDS).forEach { ndrProcessed.add(it) }
            Log.d(
                TAG,
                "Loaded delivered=${delivered.size}, locallyRead=${locallyRead.size}, " +
                    "readReceiptsSent=${readReceiptsSent.size}, ndr=${ndrProcessed.size}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SeenMessageStore: ${e.message}")
        }
    }

    @Synchronized private fun persist() {
        try {
            val payload = currentPayload()
            val json = gson.toJson(payload)
            secure.storeSecureValue(STORAGE_KEY, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist SeenMessageStore: ${e.message}")
        }
    }

    @Synchronized private fun persistSynchronously(): Boolean = try {
        secure.storeSecureValueSynchronously(STORAGE_KEY, gson.toJson(currentPayload()))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to durably persist SeenMessageStore: ${e.message}")
        false
    }

    private fun currentPayload() = StorePayload(
        delivered = delivered.toList(),
        locallyRead = locallyRead.toList(),
        readReceiptsSent = readReceiptsSent.toList(),
        ndrProcessed = ndrProcessed.toList()
    )

    private data class StorePayload(
        val delivered: List<String>? = emptyList(),
        // Keep the existing JSON field name for backward-compatible secure-store migration.
        @SerializedName("read")
        val locallyRead: List<String>? = emptyList(),
        @SerializedName("read_receipts_sent")
        val readReceiptsSent: List<String>? = null,
        val ndrProcessed: List<String>? = emptyList()
    )
}
