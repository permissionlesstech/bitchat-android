package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Manager for publishing and subscribing to the user's profile notes (#me) via Nostr
 * - Publishes kind=1 text note with tag ["bitchat","1"] for compatibility and filtering
 * - Subscribes to author's kind=1 notes filtered by the tag above
 * - Uses NostrRelayManager when online, falls back to mesh via NostrMeshGateway when offline
 */
object NostrProfileManager {
    private const val TAG = "NostrProfileManager"
    
    // Minimum PoW difficulty required for mesh relay (must match NostrMeshGateway)
    private const val MESH_REQUIRED_POW_DIFFICULTY = 8

    // Popular relays known for good profile/user content coverage
    private val PROFILE_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://relay.nostr.band",
        "wss://nos.lol",
        "wss://relay.snort.social",
        "wss://purplepag.es",
        "wss://nostr.wine"
    )

    private var context: Context? = null
    private var meshService: com.bitchat.android.mesh.BluetoothMeshService? = null
    private var subscribeFunc: ((NostrFilter, String, (NostrEvent) -> Unit) -> String)? = null
    private var unsubscribeFunc: ((String) -> Unit)? = null
    private var sendEventFunc: ((NostrEvent, List<String>?) -> Unit)? = null
    private var currentSubId: String? = null
    
    /**
     * Result of publishing a profile message
     */
    sealed class PublishResult {
        object Success : PublishResult()
        object NotInitialized : PublishResult()
        object NoIdentity : PublishResult()
        object PowRequired : PublishResult()  // PoW is required but not enabled
        object PowMiningFailed : PublishResult()
        data class Error(val message: String) : PublishResult()
    }

    fun initialize(
        context: Context,
        meshService: com.bitchat.android.mesh.BluetoothMeshService,
        subscribe: (NostrFilter, String, (NostrEvent) -> Unit) -> String,
        unsubscribe: (String) -> Unit,
        sendEvent: (NostrEvent, List<String>?) -> Unit
    ) {
        this.context = context.applicationContext
        this.meshService = meshService
        this.subscribeFunc = subscribe
        this.unsubscribeFunc = unsubscribe
        this.sendEventFunc = sendEvent
        Log.d(TAG, "Initialized NostrProfileManager")
    }

    /**
     * Publish a profile message (kind=1) signed with current identity.
     * Publishes to Nostr relays (no bitchat tag for normal posts).
     * 
     * @return PublishResult indicating success or type of failure
     */
    suspend fun publishProfileMessage(content: String, nickname: String?): PublishResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "publishProfileMessage called with content: ${content.take(50)}...")
        val ctx = context ?: run { 
            Log.w(TAG, "Not initialized - context is null")
            return@withContext PublishResult.NotInitialized
        }
        val mesh = meshService
        val sendEvent = sendEventFunc

        val identity = try { NostrIdentityBridge.getCurrentNostrIdentity(ctx) } catch (e: Exception) { null }
        if (identity == null) {
            Log.e(TAG, "No Nostr identity available for profile publish")
            return@withContext PublishResult.NoIdentity
        }

        // Check if we're offline - if so, we need PoW for mesh relay
        val isOffline = !NostrMeshGateway.hasInternetConnection(ctx)
        
        val event: NostrEvent
        if (isOffline) {
            Log.d(TAG, "Device is offline - checking PoW requirements for mesh relay")
            
            if (!PoWPreferenceManager.isPowEnabled()) {
                Log.w(TAG, "PoW is required for offline #me messages but is not enabled")
                return@withContext PublishResult.PowRequired
            }
            
            // Create unsigned event first (we'll sign after mining)
            val unsignedEvent = NostrEvent(
                pubkey = identity.publicKeyHex,
                createdAt = (System.currentTimeMillis() / 1000).toInt(),
                kind = NostrKind.TEXT_NOTE,
                tags = emptyList(),
                content = content
            )
            
            // Mine the event with required difficulty
            val difficulty = maxOf(PoWPreferenceManager.getPowDifficulty(), MESH_REQUIRED_POW_DIFFICULTY)
            Log.d(TAG, "Mining PoW for offline message with difficulty $difficulty...")
            
            PoWPreferenceManager.startMining()
            try {
                val minedEvent = NostrProofOfWork.mineEvent(unsignedEvent, difficulty)
                if (minedEvent == null) {
                    Log.e(TAG, "PoW mining failed")
                    return@withContext PublishResult.PowMiningFailed
                }
                // Sign the mined event (with nonce tag already added)
                event = minedEvent.sign(identity.privateKeyHex)
                Log.d(TAG, "PoW mining successful, signed event id=${event.id.take(16)}...")
            } finally {
                PoWPreferenceManager.stopMining()
            }
        } else {
            // Online: create and sign normally (no PoW needed)
            event = NostrEvent.createTextNote(content, identity.publicKeyHex, identity.privateKeyHex, tags = emptyList())
        }

        try {
            // Ensure we're connected to profile-focused relays for better delivery
            NostrRelayManager.shared.connectToAdditionalRelays(PROFILE_RELAYS)

            // If we have a direct sendEvent function (relays), prefer that via NostrRelayManager; else fallback to mesh
            val meshSender: (ByteArray) -> Unit = { bytes ->
                try {
                    val svc = mesh
                    if (svc != null) {
                        val pkt = BitchatPacket(
                            type = MessageType.MESSAGE.value,
                            ttl = AppConstants.SYNC_TTL_HOPS,
                            senderID = svc.myPeerID,
                            payload = bytes
                        )
                        svc.broadcastPacket(pkt)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "meshSender failed: ${e.message}")
                }
            }

            NostrMeshGateway.publishToMeshOrRelay(ctx, event, meshSender)

            Log.d(TAG, "Published profile message id=${event.id.take(16)}...")
            return@withContext PublishResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish profile message: ${e.message}")
            return@withContext PublishResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Subscribe to our profile notes and deliver incoming events to handler
     */
    fun subscribeMyProfile(handler: (NostrEvent) -> Unit) {
        val ctx = context ?: run { Log.w(TAG, "Not initialized"); return }
        val sub = subscribeFunc ?: run { Log.w(TAG, "subscribeFunc not set"); return }

        val identity = try { NostrIdentityBridge.getCurrentNostrIdentity(ctx) } catch (e: Exception) { null }
        if (identity == null) {
            Log.w(TAG, "No identity to subscribe for")
            return
        }

        // Ensure we're connected to profile-focused relays for better coverage
        NostrRelayManager.shared.connectToAdditionalRelays(PROFILE_RELAYS)

        // Build filter: author=self, kind=1 (all text notes, not just bitchat-tagged)
        val filter = NostrFilter.Builder()
            .authors(identity.publicKeyHex)
            .kinds(NostrKind.TEXT_NOTE)
            .limit(100)
            .build()

        val id = "profile-sub-${System.currentTimeMillis()}"
        try {
            currentSubId = sub(filter, id) { event -> handler(event) }
            Log.d(TAG, "Subscribed to my profile notes with subId=$currentSubId, pubkey=${identity.publicKeyHex.take(16)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to profile: ${e.message}")
        }
    }

    fun unsubscribeProfile() {
        val unsub = unsubscribeFunc ?: return
        val id = currentSubId ?: return
        try {
            unsub(id)
            currentSubId = null
            Log.d(TAG, "Unsubscribed profile sub $id")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unsubscribe profile: ${e.message}")
        }
    }
}
