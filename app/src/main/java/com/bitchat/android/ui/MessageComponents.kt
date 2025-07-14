package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.parsing.MessageElement
import com.bitchat.android.parsing.ParsedMessageContent
import com.bitchat.android.parsing.ParsedCashuToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    modifier: Modifier = Modifier,
    onCashuPaymentClick: ((ParsedCashuToken) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(messages) { message ->
                MessageItem(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onCashuPaymentClick = onCashuPaymentClick
                )
            }
        }
    }
}

@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    onCashuPaymentClick: ((ParsedCashuToken) -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Check if message contains special content (like Cashu tokens)
        val parsedElements = parseMessageContent(message.content)
        val hasSpecialContent = parsedElements.any { it !is MessageElement.Text }
        
        if (hasSpecialContent) {
            // Use new parsed message layout for special content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Timestamp and sender header
                MessageHeader(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    colorScheme = colorScheme,
                    timeFormatter = timeFormatter
                )
                
                // Parsed content with inline special elements
                ParsedMessageContent(
                    elements = parsedElements,
                    onCashuPaymentClick = onCashuPaymentClick,
                    modifier = Modifier.padding(start = 16.dp) // Indent content slightly
                )
            }
        } else {
            // Use existing text-only layout
            Text(
                text = formatMessageAsAnnotatedString(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    colorScheme = colorScheme,
                    timeFormatter = timeFormatter
                ),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                softWrap = true,
                overflow = TextOverflow.Visible
            )
        }
        
        // Delivery status for private messages
        if (message.isPrivate && message.sender == currentUserNickname) {
            message.deliveryStatus?.let { status ->
                DeliveryStatusIcon(status = status)
            }
        }
    }
}

@Composable
fun MessageHeader(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timestamp
        val timestampColor = if (message.sender == "system") Color.Gray else colorScheme.primary.copy(alpha = 0.7f)
        Text(
            text = "[${timeFormatter.format(message.timestamp)}] ",
            fontSize = 12.sp,
            color = timestampColor,
            fontFamily = FontFamily.Monospace
        )
        
        if (message.sender != "system") {
            // Sender
            val senderColor = when {
                message.sender == currentUserNickname -> colorScheme.primary
                else -> {
                    val peerID = message.senderPeerID
                    val rssi = peerID?.let { meshService.getPeerRSSI()[it] } ?: -60
                    getRSSIColor(rssi)
                }
            }
            
            Text(
                text = "<@${message.sender}> ",
                fontSize = 14.sp,
                color = senderColor,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        } else {
            // System message prefix
            Text(
                text = "* ",
                fontSize = 12.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = "○",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            Text(
                text = "✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            Text(
                text = "✓✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = "✓✓",
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = "⚠",
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            Text(
                text = "✓${status.reached}/${status.total}",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}
