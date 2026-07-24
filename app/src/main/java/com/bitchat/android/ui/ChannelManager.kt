package com.bitchat.android.ui

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles channel management including creation, joining, leaving, and encryption.
 *
 * Password-protected channels use PBKDF2-HMAC-SHA256 (100k iterations, channel name as salt)
 * + AES-256-GCM, matching the historical bitchat channel crypto format.
 *
 * PBKDF2 runs on [Dispatchers.Default] via suspend APIs so UI threads are not blocked.
 */
class ChannelManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ChannelManager"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        const val ENCRYPTED_PLACEHOLDER = "[Encrypted message - password required]"
    }

    // Channel encryption and security
    private val channelKeys = mutableMapOf<String, SecretKeySpec>()
    private val channelPasswords = mutableMapOf<String, String>()
    private val channelKeyCommitments = mutableMapOf<String, String>()
    private val retentionEnabledChannels = mutableSetOf<String>()

    init {
        channelKeyCommitments.putAll(dataManager.loadChannelKeyCommitments())
    }

    // MARK: - Channel Lifecycle

    suspend fun joinChannel(channel: String, password: String? = null, myPeerID: String): Boolean {
        val channelTag = if (channel.startsWith("#")) channel else "#$channel"

        // Check if already joined
        if (state.getJoinedChannelsValue().contains(channelTag)) {
            if (state.getPasswordProtectedChannelsValue().contains(channelTag) && !channelKeys.containsKey(channelTag)) {
                if (password != null) {
                    val ok = verifyChannelPassword(channelTag, password)
                    if (ok) {
                        hidePasswordPrompt()
                        switchToChannel(channelTag)
                    }
                    return ok
                } else {
                    state.setPasswordPromptChannel(channelTag)
                    state.setShowPasswordPrompt(true)
                    return false
                }
            }
            switchToChannel(channelTag)
            return true
        }

        // Password protected and no key yet — always require password (including creator after restart)
        if (state.getPasswordProtectedChannelsValue().contains(channelTag) && !channelKeys.containsKey(channelTag)) {
            if (password != null) {
                if (!verifyChannelPassword(channelTag, password)) {
                    return false
                }
                hidePasswordPrompt()
            } else {
                state.setPasswordPromptChannel(channelTag)
                state.setShowPasswordPrompt(true)
                return false
            }
        }

        // Join the channel
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.add(channelTag)
        state.setJoinedChannels(updatedChannels)

        // Set as creator if new unprotected channel
        if (!dataManager.channelCreators.containsKey(channelTag) && !state.getPasswordProtectedChannelsValue().contains(channelTag)) {
            dataManager.addChannelCreator(channelTag, myPeerID)
        }

        // Add ourselves as member
        dataManager.addChannelMember(channelTag, myPeerID)

        // Initialize channel messages if needed
        if (!state.getChannelMessagesValue().containsKey(channelTag)) {
            val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
            updatedChannelMessages[channelTag] = emptyList()
            state.setChannelMessages(updatedChannelMessages)
        }

        switchToChannel(channelTag)
        saveChannelData()
        return true
    }

    fun leaveChannel(channel: String) {
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.remove(channel)
        state.setJoinedChannels(updatedChannels)

        if (state.getCurrentChannelValue() == channel) {
            state.setCurrentChannel(null)
        }

        messageManager.removeChannelMessages(channel)
        dataManager.removeChannelMembers(channel)
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        channelKeyCommitments.remove(channel)
        dataManager.saveChannelKeyCommitments(channelKeyCommitments)
        dataManager.removeChannelCreator(channel)

        saveChannelData()
    }

    fun switchToChannel(channel: String?) {
        state.setCurrentChannel(channel)
        state.setSelectedPrivateChatPeer(null)

        channel?.let { ch ->
            messageManager.clearChannelUnreadCount(ch)
        }
    }

    // MARK: - Channel Password and Encryption

    /**
     * Derive and validate a channel password on a background dispatcher.
     * On success, stores the key and re-decrypts any placeholder history.
     */
    suspend fun verifyChannelPassword(channel: String, password: String): Boolean {
        if (password.isEmpty()) return false

        val key = try {
            withContext(Dispatchers.Default) {
                deriveChannelKey(password, channel)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to derive channel key for $channel: ${e.message}")
            return false
        }

        val expectedCommitment = channelKeyCommitments[channel]
            ?: dataManager.loadChannelKeyCommitments()[channel]
        if (expectedCommitment != null) {
            val actual = calculateKeyCommitment(key)
            if (!actual.equals(expectedCommitment, ignoreCase = true)) {
                Log.w(TAG, "Channel password rejected for $channel (commitment mismatch)")
                return false
            }
        } else {
            val existingMessages = state.getChannelMessagesValue()[channel]?.filter { it.isEncrypted }
            if (!existingMessages.isNullOrEmpty()) {
                val testMessage = existingMessages.firstOrNull { it.encryptedContent?.isNotEmpty() == true }
                if (testMessage != null) {
                    val decrypted = decryptChannelMessage(
                        testMessage.encryptedContent ?: byteArrayOf(),
                        channel,
                        key
                    )
                    if (decrypted == null) {
                        Log.w(TAG, "Channel password rejected for $channel (decrypt failed)")
                        return false
                    }
                }
            }
        }

        channelKeys[channel] = key
        channelPasswords[channel] = password
        val commitment = calculateKeyCommitment(key)
        channelKeyCommitments[channel] = commitment
        dataManager.saveChannelKeyCommitments(channelKeyCommitments)

        redecryptChannelHistory(channel, key)
        return true
    }

    private fun deriveChannelKey(password: String, channelName: String): SecretKeySpec {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            channelName.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            KEY_BITS
        )
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    private fun calculateKeyCommitment(key: SecretKeySpec): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.encoded).joinToString("") { "%02x".format(it) }
    }

    /**
     * Apply the stored (or provided) key to all stored encrypted messages for [channel],
     * replacing placeholder content with decrypted plaintext.
     */
    fun redecryptChannelHistory(channel: String, key: SecretKeySpec? = null) {
        val resolvedKey = key ?: channelKeys[channel] ?: return
        val messages = state.getChannelMessagesValue()[channel] ?: return
        var changed = false
        val updated = messages.map { msg ->
            if (msg.isEncrypted && msg.encryptedContent?.isNotEmpty() == true) {
                val plain = decryptChannelMessage(msg.encryptedContent, channel, resolvedKey)
                if (plain != null && plain != msg.content) {
                    changed = true
                    msg.copy(content = plain)
                } else {
                    msg
                }
            } else {
                msg
            }
        }
        if (changed) {
            messageManager.replaceChannelMessages(channel, updated)
        }
    }

    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return decryptChannelMessage(encryptedContent, channel, null)
    }

    private fun decryptChannelMessage(
        encryptedContent: ByteArray,
        channel: String,
        testKey: SecretKeySpec?
    ): String? {
        val key = testKey ?: channelKeys[channel] ?: return null

        return try {
            if (encryptedContent.size < GCM_IV_BYTES + 1) return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encryptedContent.sliceArray(0 until GCM_IV_BYTES)
            val ciphertext = encryptedContent.sliceArray(GCM_IV_BYTES until encryptedContent.size)

            val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val decryptedData = cipher.doFinal(ciphertext)
            String(decryptedData, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encrypt a channel message on [Dispatchers.Default] and deliver the binary payload.
     * On failure, calls [onFallback] — callers must NOT send plaintext when a channel key exists.
     */
    fun sendEncryptedChannelMessage(
        content: String,
        mentions: List<String>,
        channel: String,
        senderNickname: String?,
        myPeerID: String,
        onEncryptedPayload: (ByteArray) -> Unit,
        onFallback: () -> Unit
    ) {
        val key = channelKeys[channel]
        if (key == null) {
            Log.w(TAG, "No channel key for $channel; cannot encrypt")
            onFallback()
            return
        }

        coroutineScope.launch(Dispatchers.Default) {
            try {
                val contentBytes = content.toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)

                val iv = cipher.iv
                require(iv.size == GCM_IV_BYTES) {
                    "Unexpected GCM IV size: ${iv.size}"
                }
                val encryptedData = cipher.doFinal(contentBytes)

                val combined = ByteArray(iv.size + encryptedData.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(encryptedData, 0, combined, iv.size, encryptedData.size)

                val encryptedMessage = BitchatMessage(
                    sender = senderNickname ?: myPeerID,
                    content = "",
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = channel,
                    encryptedContent = combined,
                    isEncrypted = true
                )

                val messageData = encryptedMessage.toBinaryPayload()
                if (messageData != null) {
                    onEncryptedPayload(messageData)
                } else {
                    Log.e(TAG, "Failed to encode encrypted channel message for $channel")
                    onFallback()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt channel message for $channel: ${e.message}")
                onFallback()
            }
        }
    }

    // MARK: - Channel Management

    fun addChannelMessage(channel: String, message: BitchatMessage, senderPeerID: String?) {
        messageManager.addChannelMessage(channel, message)

        senderPeerID?.let { peerID ->
            dataManager.addChannelMember(channel, peerID)
        }
    }

    fun markChannelPasswordProtected(channel: String) {
        if (!state.getPasswordProtectedChannelsValue().contains(channel)) {
            state.setPasswordProtectedChannels(
                state.getPasswordProtectedChannelsValue().toMutableSet().apply { add(channel) }
            )
            saveChannelData()
        }
    }

    /**
     * Prompt for password when the channel is protected but we have no key yet.
     */
    fun requestPasswordForChannel(channel: String) {
        state.setPasswordPromptChannel(channel)
        state.setShowPasswordPrompt(true)
    }

    fun removeChannelMember(channel: String, peerID: String) {
        dataManager.removeChannelMember(channel, peerID)
    }

    fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        dataManager.cleanupAllDisconnectedMembers(connectedPeers, myPeerID)
    }

    // MARK: - Channel Information

    fun isChannelPasswordProtected(channel: String): Boolean {
        return state.getPasswordProtectedChannelsValue().contains(channel)
    }

    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }

    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }

    fun getChannelKeyCommitment(channel: String): String? {
        return channelKeyCommitments[channel]
    }

    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return dataManager.isChannelCreator(channel, peerID)
    }

    fun getJoinedChannelsList(): List<String> {
        return state.getJoinedChannelsValue().toList().sorted()
    }

    // MARK: - Data Persistence

    private fun saveChannelData() {
        dataManager.saveChannelData(state.getJoinedChannelsValue(), state.getPasswordProtectedChannelsValue())
    }

    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        channelKeyCommitments.clear()
        channelKeyCommitments.putAll(dataManager.loadChannelKeyCommitments())
        return dataManager.loadChannelData()
    }

    // MARK: - Password Management

    fun hidePasswordPrompt() {
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
    }

    suspend fun setChannelPassword(channel: String, password: String) {
        if (password.isEmpty()) {
            Log.w(TAG, "Ignoring empty password for $channel")
            return
        }

        val key = withContext(Dispatchers.Default) {
            deriveChannelKey(password, channel)
        }
        channelPasswords[channel] = password
        channelKeys[channel] = key

        val commitment = calculateKeyCommitment(key)
        channelKeyCommitments[channel] = commitment
        dataManager.saveChannelKeyCommitments(channelKeyCommitments)

        state.setPasswordProtectedChannels(
            state.getPasswordProtectedChannelsValue().toMutableSet().apply { add(channel) }
        )

        dataManager.saveChannelData(
            state.getJoinedChannelsValue(),
            state.getPasswordProtectedChannelsValue()
        )

        redecryptChannelHistory(channel, key)
    }

    // MARK: - Emergency Clear

    fun clearAllChannels() {
        state.setJoinedChannels(emptySet())
        state.setCurrentChannel(null)
        state.setPasswordProtectedChannels(emptySet())
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)

        channelKeys.clear()
        channelPasswords.clear()
        channelKeyCommitments.clear()
        retentionEnabledChannels.clear()
        dataManager.saveChannelKeyCommitments(emptyMap())
    }
}
