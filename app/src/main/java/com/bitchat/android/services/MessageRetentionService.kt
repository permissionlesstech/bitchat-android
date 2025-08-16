//package com.bitchat.android.services
//
//import android.content.Context
//import android.content.SharedPreferences
//import android.util.Log
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//import java.io.File
//import java.io.FileInputStream
//import java.io.FileOutputStream
//import java.io.ObjectInputStream
//import java.io.ObjectOutputStream
//import java.util.*
//
///**
// * Message retention service for saving channel messages locally
// * Matches iOS MessageRetentionService functionality
// */
//class MessageRetentionService private constructor(private val context: Context) {
//
//    companion object {
//        private const val TAG = "MessageRetentionService"
//        private const val PREF_NAME = "message_retention"
//        private const val KEY_FAVORITE_CHANNELS = "favorite_channels"
//
//        @Volatile
//        private var INSTANCE: MessageRetentionService? = null
//
//        fun getInstance(context: Context): MessageRetentionService {
//            return INSTANCE ?: synchronized(this) {
//                INSTANCE ?: MessageRetentionService(context.applicationContext).also { INSTANCE = it }
//            }
//        }
//    }
//
//    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
//    private val retentionDir = File(context.filesDir, "retained_messages")
//
//    init {
//        if (!retentionDir.exists()) {
//            retentionDir.mkdirs()
//        }
//    }
//
//    // MARK: - Channel Bookmarking (Favorites)
//
//    fun getFavoriteChannels(): Set<String> {
//        return prefs.getStringSet(KEY_FAVORITE_CHANNELS, emptySet()) ?: emptySet()
//    }
//
//    fun toggleFavoriteChannel(channel: String): Boolean {
//        val currentFavorites = getFavoriteChannels().toMutableSet()
//        val wasAdded = if (currentFavorites.contains(channel)) {
//            currentFavorites.remove(channel)
//            false
//        } else {
//            currentFavorites.add(channel)
//            true
//        }
//
//        prefs.edit().putStringSet(KEY_FAVORITE_CHANNELS, currentFavorites).apply()
//
//        if (!wasAdded) {
//            // Channel removed from favorites - delete saved messages in background
//            Thread {
//                try {
//                    val channelFile = getChannelFile(channel)
//                    if (channelFile.exists()) {
//                        channelFile.delete()
//                        Log.d(TAG, "Deleted saved messages for channel $channel")
//                    }
//                } catch (e: Exception) {
//                    Log.e(TAG, "Failed to delete messages for channel $channel", e)
//                }
//            }.start()
//        }
//
//        Log.d(TAG, "Channel $channel ${if (wasAdded) "bookmarked" else "unbookmarked"}")
//        return wasAdded
//    }
//
//    fun isChannelBookmarked(channel: String): Boolean {
//        return getFavoriteChannels().contains(channel)
//    }
//
//    // MARK: - Message Storage
//
//    suspend fun saveMessage(message: BitchatMessage, forChannel: String) = withContext(Dispatchers.IO) {
//        if (!isChannelBookmarked(forChannel)) {
//            Log.w(TAG, "Attempted to save message for non-bookmarked channel: $forChannel")
//            return@withContext
//        }
//
//        try {
//            val channelFile = getChannelFile(forChannel)
//            val existingMessages = loadMessagesFromFile(channelFile).toMutableList()
//
//            // Check if message already exists (by ID)
//            if (existingMessages.any { it.id == message.id }) {
//                Log.d(TAG, "Message ${message.id} already saved for channel $forChannel")
//                return@withContext
//            }
//
//            // Add new message
//            existingMessages.add(message)
//
//            // Sort by timestamp
//            existingMessages.sortBy { it.timestamp }
//
//            // Save back to file
//            saveMessagesToFile(channelFile, existingMessages)
//
//            Log.d(TAG, "Saved message ${message.id} for channel $forChannel")
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to save message for channel $forChannel", e)
//        }
//    }
//
//    suspend fun loadMessagesForChannel(channel: String): List<BitchatMessage> = withContext(Dispatchers.IO) {
//        if (!isChannelBookmarked(channel)) {
//            Log.d(TAG, "Channel $channel not bookmarked, returning empty list")
//            return@withContext emptyList()
//        }
//
//        try {
//            val channelFile = getChannelFile(channel)
//            val messages = loadMessagesFromFile(channelFile)
//            Log.d(TAG, "Loaded ${messages.size} messages for channel $channel")
//            return@withContext messages
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to load messages for channel $channel", e)
//            return@withContext emptyList()
//        }
//    }
//
//    suspend fun deleteMessagesForChannel(channel: String): Unit = withContext(Dispatchers.IO) {
//        try {
//            val channelFile = getChannelFile(channel)
//            if (channelFile.exists()) {
//                channelFile.delete()
//                Log.d(TAG, "Deleted saved messages for channel $channel")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to delete messages for channel $channel", e)
//        }
//    }
//
//    suspend fun deleteAllStoredMessages(): Unit = withContext(Dispatchers.IO) {
//        try {
//            if (retentionDir.exists()) {
//                retentionDir.listFiles()?.forEach { file ->
//                    file.delete()
//                }
//                Log.d(TAG, "Deleted all stored messages")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to delete all stored messages", e)
//        }
//    }
//
//    // MARK: - File Operations
//
//    private fun getChannelFile(channel: String): File {
//        // Sanitize channel name for filename
//        val sanitizedChannel = channel.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
//        return File(retentionDir, "channel_${sanitizedChannel}.dat")
//    }
//
//    private fun loadMessagesFromFile(file: File): List<BitchatMessage> {
//        if (!file.exists()) {
//            return emptyList()
//        }
//
//        return try {
//            FileInputStream(file).use { fis ->
//                ObjectInputStream(fis).use { ois ->
//                    @Suppress("UNCHECKED_CAST")
//                    ois.readObject() as List<BitchatMessage>
//                }
//            }
//        } catch (e: Exception) {
//            Log.w(TAG, "Failed to load messages from ${file.name}, returning empty list", e)
//            emptyList()
//        }
//    }
//
//    private fun saveMessagesToFile(file: File, messages: List<BitchatMessage>) {
//        FileOutputStream(file).use { fos ->
//            ObjectOutputStream(fos).use { oos ->
//                oos.writeObject(messages)
//            }
//        }
//    }
//
//    // MARK: - Statistics
//
//    fun getBookmarkedChannelsCount(): Int {
//        return getFavoriteChannels().size
//    }
//
//    suspend fun getTotalStoredMessagesCount(): Int = withContext(Dispatchers.IO) {
//        var totalCount = 0
//
//        try {
//            retentionDir.listFiles()?.forEach { file ->
//                if (file.name.startsWith("channel_") && file.name.endsWith(".dat")) {
//                    val messages = loadMessagesFromFile(file)
//                    totalCount += messages.size
//                }
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to count stored messages", e)
//        }
//
//        totalCount
//    }
//}
