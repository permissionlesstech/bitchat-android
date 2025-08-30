package com.bitchat.android.services

import android.util.Log
import com.bitchat.android.model.BitchatMessage

/**
 * Service for filtering spam messages in geohash channels
 * Filters out messages from known spam bots and messages with suspicious patterns
 */
class SpamFilterService {
    
    companion object {
        private const val TAG = "SpamFilterService"
        
                       // Known spam bot nicknames
               private val SPAM_BOT_NICKNAMES = mutableSetOf(
                   "spam-tester",
                   "semicolon"
               )
        
        // Pattern for detecting multiple semicolons in a row
        private val MULTIPLE_SEMICOLONS_PATTERN = Regex(";{2,}")
        
        @Volatile
        private var INSTANCE: SpamFilterService? = null
        
        fun getInstance(): SpamFilterService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpamFilterService().also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Check if a message should be filtered as spam
     * @param message The message to check
     * @return true if the message should be filtered, false otherwise
     */
    fun isSpamMessage(message: BitchatMessage): Boolean {
        // Check if sender is a known spam bot
        if (isSpamBotNickname(message.sender)) {
            Log.d(TAG, "Filtering message from spam bot: ${message.sender}")
            return true
        }
        
        // Check if message content contains suspicious patterns
        if (hasSuspiciousContent(message.content)) {
            Log.d(TAG, "Filtering message with suspicious content from: ${message.sender}")
            return true
        }
        
        return false
    }
    
    /**
     * Check if a nickname belongs to a known spam bot
     */
    private fun isSpamBotNickname(nickname: String): Boolean {
        return SPAM_BOT_NICKNAMES.any { spamBot -> 
            nickname.contains(spamBot, ignoreCase = true) 
        }
    }
    
    /**
     * Check if message content contains suspicious patterns
     */
    private fun hasSuspiciousContent(content: String): Boolean {
        // Check for multiple semicolons in a row
        if (MULTIPLE_SEMICOLONS_PATTERN.containsMatchIn(content)) {
            Log.d(TAG, "Detected multiple semicolons in message: ${content.take(50)}...")
            return true
        }
        
        return false
    }
    
    /**
     * Add a new spam bot nickname to the filter
     * @param nickname The nickname to add
     */
    fun addSpamBotNickname(nickname: String) {
        SPAM_BOT_NICKNAMES.add(nickname.lowercase())
        Log.i(TAG, "Added spam bot nickname: $nickname")
    }
    
    /**
     * Remove a nickname from the spam bot filter
     * @param nickname The nickname to remove
     */
    fun removeSpamBotNickname(nickname: String) {
        SPAM_BOT_NICKNAMES.remove(nickname.lowercase())
        Log.i(TAG, "Removed spam bot nickname: $nickname")
    }
    
    /**
     * Get all currently blocked spam bot nicknames
     */
    fun getSpamBotNicknames(): Set<String> {
        return SPAM_BOT_NICKNAMES.toSet()
    }
} 