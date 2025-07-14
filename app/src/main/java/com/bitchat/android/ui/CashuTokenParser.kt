package com.bitchat.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.regex.Pattern

/**
 * Cashu token parsing utilities for chat messages
 */
object CashuTokenParser {
    
    private const val CASHU_TOKEN_PATTERN = "cashu[A-Za-z0-9+/_-]{50,}"
    private val cashuTokenRegex = Pattern.compile(CASHU_TOKEN_PATTERN)
    
    /**
     * Check if a string contains a Cashu token
     */
    fun containsCashuToken(text: String): Boolean {
        return cashuTokenRegex.matcher(text).find()
    }
    
    /**
     * Extract Cashu token from text
     */
    fun extractCashuToken(text: String): String? {
        val matcher = cashuTokenRegex.matcher(text)
        return if (matcher.find()) {
            matcher.group()
        } else {
            null
        }
    }
    
    /**
     * Parse token to extract basic information without CDK
     * This is a simplified version that tries to decode the token format
     */
    fun parseTokenInfo(token: String): CashuTokenInfo? {
        return try {
            if (token.startsWith("cashu")) {
                // For now, return mock info since we don't have CDK integration yet
                // In a real implementation, this would use CDK to decode the token
                CashuTokenInfo(
                    token = token,
                    amount = estimateTokenAmount(token), // Simple heuristic
                    mint = "Unknown Mint",
                    unit = "sat"
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Simple heuristic to estimate token amount based on token length/structure
     * This is just for demo purposes - real implementation would decode the token
     */
    private fun estimateTokenAmount(token: String): Long {
        return when {
            token.length < 100 -> 1L
            token.length < 200 -> 21L
            token.length < 300 -> 100L
            token.length < 500 -> 1000L
            else -> 10000L
        }
    }
}

/**
 * Simplified Cashu token information
 */
data class CashuTokenInfo(
    val token: String,
    val amount: Long,
    val mint: String,
    val unit: String
)

/**
 * Colors for Cashu token highlighting
 */
@Composable
fun getCashuTokenColors(): Pair<Color, Color> {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    
    return if (isDarkTheme) {
        // Dark theme: bright orange background, dark text
        Pair(
            Color(0xFFFF9500).copy(alpha = 0.3f), // background
            Color(0xFFFF9500) // text
        )
    } else {
        // Light theme: light orange background, dark orange text
        Pair(
            Color(0xFFFF9500).copy(alpha = 0.15f), // background
            Color(0xFFE67E00) // text
        )
    }
}

/**
 * Extension function to get luminance from Color
 */
private fun Color.luminance(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}
