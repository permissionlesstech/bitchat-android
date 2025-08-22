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
    forceScrollToBottom: Boolean = false
) {
    val listState = rememberLazyListState()
    
    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    
    // Smart scroll: auto-scroll to bottom for initial load, then only when user is at or near the bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = layoutInfo.totalItemsCount
            
            // Always scroll to bottom on first load, or when user is near the bottom
            val isFirstLoad = !hasScrolledToInitialPosition
            val isNearBottom = lastVisibleIndex >= totalItems - 3
            
            if (isFirstLoad || isNearBottom) {
                listState.animateScrollToItem(messages.size - 1)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }
    
    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (forceScrollToBottom && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    SelectionContainer(modifier = modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(messages) { message ->
                MessageItem(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService
                )
            }
        }
    }
}

@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Single text view for natural wrapping (like iOS)
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
        
        // Delivery status for private messages
        if (message.isPrivate && message.sender == currentUserNickname) {
            message.deliveryStatus?.let { status ->
                DeliveryStatusIcon(status = status)
            }
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
