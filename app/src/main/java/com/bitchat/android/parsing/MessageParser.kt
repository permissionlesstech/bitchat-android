package com.bitchat.android.parsing

import android.util.Log

/**
 * Main message parser that orchestrates parsing messages for special content.
 * Supports DMs, public messages, and channel messages.
 * 
 * Currently supports:
 * - Cashu token parsing (cashuB...)
 */
class MessageParser {
    private val cashuParser = CashuTokenParser()
    
    companion object {
        private const val TAG = "MessageParser"
        
        // Singleton instance
        val instance = MessageParser()
        
        // Safe logging that works in unit tests
        private fun logDebug(message: String) {
            try {
                Log.d(TAG, message)
            } catch (e: RuntimeException) {
                // Ignore - likely running in unit test
                println("[$TAG] $message")
            }
        }
        
        private fun logWarning(message: String, throwable: Throwable? = null) {
            try {
                if (throwable != null) {
                    Log.w(TAG, message, throwable)
                } else {
                    Log.w(TAG, message)
                }
            } catch (e: RuntimeException) {
                // Ignore - likely running in unit test  
                println("[$TAG] WARNING: $message")
                throwable?.printStackTrace()
            }
        }
        
        private fun logError(message: String, throwable: Throwable? = null) {
            try {
                if (throwable != null) {
                    Log.e(TAG, message, throwable)
                } else {
                    Log.e(TAG, message)
                }
            } catch (e: RuntimeException) {
                // Ignore - likely running in unit test
                println("[$TAG] ERROR: $message") 
                throwable?.printStackTrace()
            }
        }
    }
    
    /**
     * Parse message content and return list of message elements.
     * Each element can be text or a special parsed element (like Cashu payment).
     */
    fun parseMessage(content: String): List<MessageElement> {
        val elements = mutableListOf<MessageElement>()
        
        try {
            // Start with the full content as a single text element
            var remainingContent = content
            var currentIndex = 0
            
            // Look for Cashu tokens (cashuB...)
            val cashuPattern = """cashuB[A-Za-z0-9+/=_-]+""".toRegex()
            val matches = cashuPattern.findAll(content).toList()
            
            for (match in matches) {
                // Add text before the match
                if (match.range.first > currentIndex) {
                    val beforeText = content.substring(currentIndex, match.range.first)
                    if (beforeText.isNotEmpty()) {
                        elements.add(MessageElement.Text(beforeText))
                    }
                }
                
                // Parse the Cashu token
                val tokenString = match.value
                val cashuToken = cashuParser.parseToken(tokenString)
                
                if (cashuToken != null) {
                    elements.add(MessageElement.CashuPayment(cashuToken))
                    logDebug("Parsed Cashu token: ${cashuToken.amount} ${cashuToken.unit}")
                } else {
                    // If parsing failed, treat it as regular text
                    elements.add(MessageElement.Text(tokenString))
                    logWarning("Failed to parse Cashu token: $tokenString")
                }
                
                currentIndex = match.range.last + 1
            }
            
            // Add remaining text after last match
            if (currentIndex < content.length) {
                val remainingText = content.substring(currentIndex)
                if (remainingText.isNotEmpty()) {
                    elements.add(MessageElement.Text(remainingText))
                }
            }
            
            // If no matches found, just return the content as text
            if (elements.isEmpty()) {
                elements.add(MessageElement.Text(content))
            }
            
        } catch (e: Exception) {
            logError("Error parsing message", e)
            // Fallback to plain text on any error
            elements.clear()
            elements.add(MessageElement.Text(content))
        }
        
        return elements
    }
}

/**
 * Sealed class representing different types of message elements
 */
sealed class MessageElement {
    /**
     * Plain text content
     */
    data class Text(val content: String) : MessageElement()
    
    /**
     * Cashu payment token
     */
    data class CashuPayment(val token: ParsedCashuToken) : MessageElement()
}

/**
 * Represents a parsed Cashu token with extracted information
 */
data class ParsedCashuToken(
    val originalString: String,
    val amount: Long,
    val unit: String,
    val mintUrl: String,
    val memo: String?,
    val proofCount: Int
)
