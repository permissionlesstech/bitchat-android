package com.bitchat.domain.repository

import com.bitchat.domain.model.*
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun sendMessage(message: Message): Result<Unit>
    fun observeChannelMessages(channel: String): Flow<List<Message>>
    suspend fun getMessageById(id: String): Message?
    suspend fun markAsRead(messageId: String, readerPeerId: String? = null): Result<Unit>
}

interface IdentityRepository {
    suspend fun getCurrentIdentityAnnouncement(): IdentityAnnouncement?
    suspend fun saveIdentityAnnouncement(announcement: IdentityAnnouncement)
    fun fingerprint(publicKey: ByteArray): String
}

interface FavoritesRepository {
    suspend fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship?
    suspend fun updateFavoriteStatus(noisePublicKey: ByteArray, nickname: String, isFavorite: Boolean)
    suspend fun updatePeerFavoritedUs(noisePublicKey: ByteArray, theyFavoritedUs: Boolean)
    suspend fun updateNostrPublicKey(noisePublicKey: ByteArray, nostrPubkey: String)
    suspend fun getMutualFavorites(): List<FavoriteRelationship>
    suspend fun getOurFavorites(): List<FavoriteRelationship>
}

data class FavoriteRelationship(
    val peerNoisePublicKey: ByteArray,
    val peerNostrPublicKey: String?,
    val peerNickname: String,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean
)

interface MessageRetentionRepository {
    fun getFavoriteChannels(): Set<String>
    fun toggleFavoriteChannel(channel: String): Boolean
    fun isChannelBookmarked(channel: String): Boolean
    suspend fun saveMessage(message: Message, channel: String)
    suspend fun loadMessagesForChannel(channel: String): List<Message>
    suspend fun deleteMessagesForChannel(channel: String)
    suspend fun deleteAllStoredMessages()
    fun getBookmarkedChannelsCount(): Int
}

