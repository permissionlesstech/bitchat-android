package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.GeohashAliasRegistry

object ContactDirectory {
    data class ContactResolution(
        val conversationID: String,
        val meshPeerID: String?,
        val noisePublicKey: ByteArray?,
        val nostrPubkey: String?,
        val displayName: String?,
        val isMutualFavorite: Boolean
    ) {
        val noiseKeyHex: String? get() = noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var meshProvider: (() -> MeshService?)? = null

    fun initialize(context: Context, meshProvider: () -> MeshService?) {
        appContext = context.applicationContext
        this.meshProvider = meshProvider
    }

    fun isContactConversationID(value: String): Boolean =
        ContactIdentityResolver.isContactConversationId(value)

    fun canonicalConversationId(peerOrConversationID: String): String {
        val value = peerOrConversationID.trim()
        if (ContactIdentityResolver.isContactConversationId(value)) return value.lowercase()

        noiseKeyForAlias(value)?.let {
            return ContactIdentityResolver.contactConversationIdForNoiseKey(it)
        }

        if (ContactIdentityResolver.isMeshPeerId(value)) {
            favoriteForMeshPeerID(value)?.peerNoisePublicKey?.let {
                return ContactIdentityResolver.contactConversationIdForNoiseKey(it)
            }
        }

        if (ContactIdentityResolver.isNostrAlias(value)) {
            nostrPubkeyHexForAlias(value)?.let { pubHex ->
                findFavoriteByNostrHex(pubHex)?.peerNoisePublicKey?.let {
                    return ContactIdentityResolver.contactConversationIdForNoiseKey(it)
                }
            }
        }

        return value
    }

    fun resolve(peerOrConversationID: String): ContactResolution {
        val conversationID = canonicalConversationId(peerOrConversationID)
        val contactFingerprint = ContactIdentityResolver.fingerprintFromContactConversationId(conversationID)

        val favorite = contactFingerprint?.let { findFavoriteByFingerprint(it) }
        val noiseKey = when {
            favorite != null -> favorite.peerNoisePublicKey
            ContactIdentityResolver.isNoiseKeyHex(peerOrConversationID) ->
                ContactIdentityResolver.bytesFromHex(peerOrConversationID)
            else -> null
        }
        val liveMeshPeerID = contactFingerprint?.let { findLiveMeshPeerForFingerprint(it) }
            ?: peerOrConversationID.takeIf { ContactIdentityResolver.isMeshPeerId(it) && isMeshPeerConnected(it) }

        return ContactResolution(
            conversationID = conversationID,
            meshPeerID = liveMeshPeerID,
            noisePublicKey = noiseKey ?: liveMeshPeerID?.let { meshProvider?.invoke()?.getPeerInfo(it)?.noisePublicKey },
            nostrPubkey = favorite?.peerNostrPublicKey,
            displayName = favorite?.peerNickname?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
                ?: liveMeshPeerID?.let { meshProvider?.invoke()?.getPeerInfo(it)?.nickname },
            isMutualFavorite = favorite?.isMutual == true
        )
    }

    fun canonicalizePrivateChats(
        chats: Map<String, List<BitchatMessage>>
    ): Map<String, List<BitchatMessage>> {
        if (chats.isEmpty()) return chats

        val merged = linkedMapOf<String, MutableList<BitchatMessage>>()
        chats.forEach { (key, messages) ->
            val canonical = canonicalConversationId(key)
            val list = merged.getOrPut(canonical) { mutableListOf() }
            list.addAll(messages)
        }

        return merged.mapValues { (_, messages) ->
            messages
                .distinctBy { it.id }
                .sortedWith(compareBy<BitchatMessage> { it.timestamp.time }.thenBy { it.id })
        }
    }

    fun aliasesForConversation(peerOrConversationID: String): Set<String> {
        val resolution = resolve(peerOrConversationID)
        val aliases = mutableSetOf<String>()
        aliases.add(peerOrConversationID)
        aliases.add(resolution.conversationID)
        resolution.meshPeerID?.let { aliases.add(it) }
        resolution.noiseKeyHex?.let { aliases.add(it) }
        resolution.nostrPubkey
            ?.let { ContactIdentityResolver.nostrAliasForPubkey(it) }
            ?.let { aliases.add(it) }
        return aliases
    }

    private fun noiseKeyForAlias(value: String): ByteArray? {
        if (ContactIdentityResolver.isNoiseKeyHex(value)) {
            return ContactIdentityResolver.bytesFromHex(value)
        }

        if (ContactIdentityResolver.isMeshPeerId(value)) {
            meshProvider?.invoke()?.getPeerInfo(value)?.noisePublicKey?.let { return it }
            cachedNoiseKey(value)?.let { return it }
        }

        return null
    }

    private fun cachedNoiseKey(peerID: String): ByteArray? {
        val context = appContext ?: return null
        return try {
            SecureIdentityStateManager(context)
                .getCachedNoiseKey(peerID)
                ?.let { ContactIdentityResolver.bytesFromHex(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun favoriteForMeshPeerID(peerID: String): FavoriteRelationship? =
        try {
            FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
        } catch (_: Exception) {
            null
        }

    private fun findFavoriteByFingerprint(fingerprint: String): FavoriteRelationship? =
        try {
            FavoritesPersistenceService.shared.getAllRelationships().firstOrNull {
                ContactIdentityResolver.fingerprintHex(it.peerNoisePublicKey).equals(fingerprint, ignoreCase = true)
            }
        } catch (_: Exception) {
            null
        }

    private fun findFavoriteByNostrHex(pubHex: String): FavoriteRelationship? =
        try {
            FavoritesPersistenceService.shared.getAllRelationships().firstOrNull {
                it.peerNostrPublicKey
                    ?.let { npub -> ContactIdentityResolver.nostrPubkeyHex(npub) }
                    ?.equals(pubHex, ignoreCase = true) == true
            }
        } catch (_: Exception) {
            null
        }

    private fun nostrPubkeyHexForAlias(alias: String): String? {
        GeohashAliasRegistry.get(alias)?.let { return it.lowercase() }
        val prefix = alias.removePrefix("nostr_").removePrefix("nostr:")
        if (prefix.isBlank()) return null
        return try {
            FavoritesPersistenceService.shared.getAllRelationships()
                .asSequence()
                .mapNotNull { it.peerNostrPublicKey?.let { npub -> ContactIdentityResolver.nostrPubkeyHex(npub) } }
                .firstOrNull { it.startsWith(prefix, ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private fun findLiveMeshPeerForFingerprint(fingerprint: String): String? {
        val mesh = meshProvider?.invoke() ?: return null
        return mesh.getPeerNicknames().keys.firstOrNull { peerID ->
            val info = mesh.getPeerInfo(peerID)
            val noiseKey = info?.noisePublicKey ?: cachedNoiseKey(peerID)
            noiseKey != null &&
                ContactIdentityResolver.fingerprintHex(noiseKey).equals(fingerprint, ignoreCase = true) &&
                info?.isConnected == true
        }
    }

    private fun isMeshPeerConnected(peerID: String): Boolean =
        try {
            meshProvider?.invoke()?.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
}
