package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    myPeerID: String,
    peerRSSI: Map<String, Int>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    SelectionContainer(modifier = modifier) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(
                items = messages,
                key = { message -> message.id } // Use a unique and stable key
            ) { message ->

                val signalColor = when {
                    message.senderPeerID == myPeerID -> Color(0xFF0080FF) // Blue-Green
                    else -> {
                        getRSSIColor(peerRSSI[message.senderPeerID])
                    }
                }

                key(message.content, currentUserNickname, signalColor) {
                    MessageItem(
                        message = message,
                        currentUserNickname = currentUserNickname,
                        signalColor = signalColor,
                    )
                }
            }
        }
    }
}

@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    signalColor: Color,
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        val colorScheme = colorScheme
        val formattedText = remember(message.content, signalColor) {
            formatMessageAsAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                signalColor = signalColor,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter
            )
        }

        Text(
            text = formattedText,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
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

val mockBitchatMessage = BitchatMessage(
    sender = "User1",
    content = "Hello there! This is a test message. How are you doing today? @User2 #general",
    timestamp = Date(),
    mentions = listOf("User2"),
    channel = "general"
)

@Preview(showBackground = true)
@Composable
fun MessagesListPreview() {
    MaterialTheme {
        MessagesList(
            messages = listOf(
                mockBitchatMessage,
                mockBitchatMessage
            ),
            currentUserNickname = "Alice",
            myPeerID = "alice_peer_id",
            peerRSSI = mapOf("bob_peer_id" to -50)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MessageItemPreview() {
    MaterialTheme {
        MessageItem(
            message = mockBitchatMessage,
            currentUserNickname = "Alice",
            signalColor = Color.Blue
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DeliveryStatusIconSendingPreview() {
    MaterialTheme {
        DeliveryStatusIcon(status = DeliveryStatus.Sending)
    }
}

@Preview(showBackground = true)
@Composable
fun DeliveryStatusIconSentPreview() {
    MaterialTheme {
        DeliveryStatusIcon(status = DeliveryStatus.Sent)
    }
}

@Preview(showBackground = true)
@Composable
fun DeliveryStatusIconDeliveredPreview() {
    MaterialTheme {
        DeliveryStatusIcon(status = DeliveryStatus.Delivered("peer1", Date()))
    }
}

@Preview(showBackground = true)
@Composable
fun DeliveryStatusIconReadPreview() {
    MaterialTheme {
        DeliveryStatusIcon(status = DeliveryStatus.Read("peer1", Date()))
    }
}

@Preview(showBackground = true)
@Composable
fun DeliveryStatusIconFailedPreview() {
    MaterialTheme {
        DeliveryStatusIcon(status = DeliveryStatus.Failed("Error"))
    }
}
