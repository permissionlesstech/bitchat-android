package com.bitchat.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.mesh.BluetoothMeshService
import androidx.compose.material3.ColorScheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility functions for ChatScreen UI components
 * Extracted from ChatScreen.kt for better organization
 */

/**
 * Get RSSI-based color for signal strength visualization
 */
fun getRSSIColor(rssi: Int): Color {
    return when {
        rssi >= -50 -> Color(0xFF00FF00) // Bright green
        rssi >= -60 -> Color(0xFF80FF00) // Green-yellow
        rssi >= -70 -> Color(0xFFFFFF00) // Yellow
        rssi >= -80 -> Color(0xFFFF8000) // Orange
        else -> Color(0xFFFF4444) // Red
    }
}

/**
 * Format message as annotated string with proper styling
 */
fun formatMessageAsAnnotatedString(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    
    // Timestamp
    val timestampColor = if (message.sender == "system") Color.Gray else colorScheme.primary.copy(alpha = 0.7f)
    builder.pushStyle(SpanStyle(
        color = timestampColor,
        fontSize = 12.sp
    ))
    builder.append("[${timeFormatter.format(message.timestamp)}] ")
    builder.pop()
    
    if (message.sender != "system") {
        // Sender
        val senderColor = when {
            message.senderPeerID == meshService.myPeerID -> colorScheme.primary
            else -> {
                val peerID = message.senderPeerID
                val rssi = peerID?.let { meshService.getPeerRSSI()[it] } ?: -60
                getRSSIColor(rssi)
            }
        }
        
        builder.pushStyle(SpanStyle(
            color = senderColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        ))
        builder.append("<@${message.sender}> ")
        builder.pop()
        
        // Message content with mentions and hashtags highlighted
        appendFormattedContent(builder, message.content, message.mentions, currentUserNickname, colorScheme)
        
    } else {
        // System message
        builder.pushStyle(SpanStyle(
            color = Color.Gray,
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic
        ))
        builder.append("* ${message.content} *")
        builder.pop()
    }
    
    return builder.toAnnotatedString()
}

/**
 * Append formatted content with hashtag and mention highlighting
 */
private fun appendFormattedContent(
    builder: AnnotatedString.Builder,
    content: String,
    mentions: List<String>?,
    currentUserNickname: String,
    colorScheme: ColorScheme
) {
    val isMentioned = mentions?.contains(currentUserNickname) == true
    
    // Parse hashtags and mentions
    val hashtagPattern = "#([a-zA-Z0-9_]+)".toRegex()
    val mentionPattern = "@([a-zA-Z0-9_]+)".toRegex()
    
    val hashtagMatches = hashtagPattern.findAll(content).toList()
    val mentionMatches = mentionPattern.findAll(content).toList()
    
    // Combine and sort all matches
    val allMatches = (hashtagMatches.map { it.range to "hashtag" } + 
                     mentionMatches.map { it.range to "mention" })
        .sortedBy { it.first.first }
    
    var lastEnd = 0
    
    for ((range, type) in allMatches) {
        // Add text before the match
        if (lastEnd < range.first) {
            val beforeText = content.substring(lastEnd, range.first)
            builder.pushStyle(SpanStyle(
                color = colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
            ))
            builder.append(beforeText)
            builder.pop()
        }
        
        // Add the styled match
        val matchText = content.substring(range.first, range.last + 1)
        when (type) {
            "hashtag" -> {
                builder.pushStyle(SpanStyle(
                    color = Color(0xFF0080FF), // Blue
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                ))
            }
            "mention" -> {
                builder.pushStyle(SpanStyle(
                    color = Color(0xFFFF9500), // Orange
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ))
            }
        }
        builder.append(matchText)
        builder.pop()
        
        lastEnd = range.last + 1
    }
    
    // Add remaining text
    if (lastEnd < content.length) {
        val remainingText = content.substring(lastEnd)
        builder.pushStyle(SpanStyle(
            color = colorScheme.primary,
            fontSize = 14.sp,
            fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
        ))
        builder.append(remainingText)
        builder.pop()
    }
}
