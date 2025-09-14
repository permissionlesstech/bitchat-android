package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MessageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * GeohashMessageHandler
 * - Processes kind=20000 Nostr events for geohash channels
 * - Updates repository for participants + nicknames
 * - Emits messages to MessageManager
 */
class GeohashMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val repo: GeohashRepository,
    private val scope: CoroutineScope,
    private val dataManager: com.bitchat.android.ui.DataManager
) {
    companion object { private const val TAG = "GeohashMessageHandler" }

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    fun onEvent(event: NostrEvent, subscribedGeohash: String) {
        scope.launch(Dispatchers.Default) {
            try {
                if (event.kind != 20000) return@launch
                val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
                if (tagGeo == null || !tagGeo.equals(subscribedGeohash, true)) return@launch
                if (dedupe(event.id)) return@launch

                // PoW validation (if enabled)
                val pow = PoWPreferenceManager.getCurrentSettings()
                if (pow.enabled && pow.difficulty > 0) {
                    if (!NostrProofOfWork.validateDifficulty(event, pow.difficulty)) return@launch
                }

                // Blocked users check (use injected DataManager which has loaded state)
                if (dataManager.isGeohashUserBlocked(event.pubkey)) return@launch

                // Update repository (participants, nickname, teleport)
                // Update repository on a background-safe path; repository will post updates to LiveData
                repo.updateParticipant(subscribedGeohash, event.pubkey, Date(event.createdAt * 1000L))
                event.tags.find { it.size >= 2 && it[0] == "n" }?.let { repo.cacheNickname(event.pubkey, it[1]) }
                event.tags.find { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }?.let { repo.markTeleported(event.pubkey) }
                // Register a geohash DM alias for this participant so MessageRouter can route DMs via Nostr
                try {
                    com.bitchat.android.nostr.GeohashAliasRegistry.put("nostr_${event.pubkey.take(16)}", event.pubkey)
                } catch (_: Exception) { }

                // Skip our own events for message emission
                val my = NostrIdentityBridge.deriveIdentity(subscribedGeohash, application)
                if (my.publicKeyHex.equals(event.pubkey, true)) return@launch

                val isTeleportPresence = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" } &&
                                         event.content.trim().isEmpty()
                if (isTeleportPresence) return@launch

                val senderName = repo.displayNameForNostrPubkeyUI(event.pubkey)
                val hasNonce = try { NostrProofOfWork.hasNonce(event) } catch (_: Exception) { false }
                val msg = BitchatMessage(
                    id = event.id,
                    sender = senderName,
                    content = event.content,
                    timestamp = Date(event.createdAt * 1000L),
                    isRelay = false,
                    originalSender = repo.displayNameForNostrPubkey(event.pubkey),
                    senderPeerID = "nostr:${event.pubkey.take(8)}",
                    mentions = null,
                    channel = "#$subscribedGeohash",
                    powDifficulty = try {
                        if (hasNonce) NostrProofOfWork.calculateDifficulty(event.id).takeIf { it > 0 } else null
                    } catch (_: Exception) { null }
                )
                withContext(Dispatchers.Main) { messageManager.addChannelMessage("geo:$subscribedGeohash", msg) }
            } catch (e: Exception) {
                Log.e(TAG, "onEvent error: ${e.message}")
            }
        }
    }
}
