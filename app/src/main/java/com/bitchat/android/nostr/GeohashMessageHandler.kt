package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MessageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    internal fun onEvent(
        event: NostrEvent,
        subscribedGeohash: String,
        accountEpoch: NostrAccountEpoch
    ) {
        val accountContext =
            NostrInboundAccountLifecycle.contextFor(accountEpoch)
                ?: return
        scope.launch(Dispatchers.Default + accountContext.receiveJob) {
            try {
                if (!NostrInboundAccountLifecycle.isCurrent(accountEpoch)) {
                    return@launch
                }
                if (event.kind != NostrKind.EPHEMERAL_EVENT && event.kind != NostrKind.GEOHASH_PRESENCE) return@launch
                val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
                if (tagGeo == null || !tagGeo.equals(subscribedGeohash, true)) return@launch

                // PoW validation (if enabled) - apply to chat messages primarily
                if (event.kind == NostrKind.EPHEMERAL_EVENT) {
                    val pow = PoWPreferenceManager.getCurrentSettings()
                    if (pow.enabled && pow.difficulty > 0) {
                        if (!NostrProofOfWork.validateDifficulty(event, pow.difficulty)) return@launch
                    }
                }

                // Normalize pubkey to lowercase for consistent blocking and storage
                val pubkey = event.pubkey.lowercase()

                // Blocked users check (use injected DataManager which has loaded state)
                if (dataManager.isGeohashUserBlocked(pubkey)) return@launch

                val isTeleportPresence = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" } &&
                                         event.content.trim().isEmpty()
                val emitMessage =
                    event.kind != NostrKind.GEOHASH_PRESENCE &&
                        !isTeleportPresence &&
                        !NostrIdentityBridge.deriveIdentity(
                            subscribedGeohash,
                            application
                        ).publicKeyHex.equals(pubkey, true)

                NostrInboundAccountLifecycle.runIfCurrent(accountEpoch) {
                    if (dedupe(event.id)) return@runIfCurrent
                    repo.updateParticipant(
                        subscribedGeohash,
                        pubkey,
                        Date(event.createdAt * 1000L)
                    )
                    event.tags.find {
                        it.size >= 2 && it[0] == "n"
                    }?.let {
                        repo.cacheNickname(pubkey, it[1])
                    }
                    event.tags.find {
                        it.size >= 2 && it[0] == "t" && it[1] == "teleport"
                    }?.let {
                        repo.markTeleported(pubkey)
                    }
                    GeohashAliasRegistry.put(
                        "nostr_${pubkey.take(16)}",
                        pubkey
                    )
                    if (emitMessage) {
                        val hasNonce = try {
                            NostrProofOfWork.hasNonce(event)
                        } catch (_: Exception) {
                            false
                        }
                        val msg = BitchatMessage(
                            id = event.id,
                            sender = repo.displayNameForNostrPubkeyUI(pubkey),
                            content = event.content,
                            timestamp = Date(event.createdAt * 1000L),
                            isRelay = false,
                            originalSender = repo.displayNameForNostrPubkey(pubkey),
                            senderPeerID = "nostr:${pubkey.take(8)}",
                            senderNostrPubkey = pubkey,
                            mentions = null,
                            channel = "#$subscribedGeohash",
                            powDifficulty = try {
                                if (hasNonce) {
                                    NostrProofOfWork.calculateDifficulty(event.id)
                                        .takeIf { it > 0 }
                                } else {
                                    null
                                }
                            } catch (_: Exception) {
                                null
                            }
                        )
                        messageManager.addChannelMessage(
                            "geo:$subscribedGeohash",
                            msg
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onEvent error: ${e.message}")
            }
        }
    }
}
