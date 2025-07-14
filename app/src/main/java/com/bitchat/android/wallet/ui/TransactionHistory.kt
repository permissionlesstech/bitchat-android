package com.bitchat.android.wallet.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.data.WalletTransaction
import com.bitchat.android.wallet.data.TransactionType
import com.bitchat.android.wallet.data.TransactionStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistory(
    transactions: List<WalletTransaction>,
    modifier: Modifier = Modifier,
    onTransactionClick: (WalletTransaction) -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.surface)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = "Transaction History",
                tint = Color(0xFF00C851),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Transaction History",
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )
        }
        
        if (transactions.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Receipt,
                        contentDescription = null,
                        tint = colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No transactions yet",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your transaction history will appear here",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Transaction list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transactions) { transaction ->
                    TransactionItem(
                        transaction = transaction,
                        onClick = { onTransactionClick(transaction) }
                    )
                }
                
                // Bottom padding
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(
    transaction: WalletTransaction,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val clipboardManager = LocalClipboardManager.current
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface,
            contentColor = colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Transaction type and icon
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (transaction.type) {
                            TransactionType.CASHU_SEND -> Icons.Filled.Send
                            TransactionType.CASHU_RECEIVE -> Icons.Filled.CallReceived
                            TransactionType.LIGHTNING_SEND, TransactionType.MELT -> Icons.Filled.Bolt
                            TransactionType.LIGHTNING_RECEIVE, TransactionType.MINT -> Icons.Filled.QrCode
                        },
                        contentDescription = null,
                        tint = when (transaction.type) {
                            TransactionType.CASHU_SEND, TransactionType.LIGHTNING_SEND, TransactionType.MELT -> Color(0xFFFF5722)
                            TransactionType.CASHU_RECEIVE, TransactionType.LIGHTNING_RECEIVE, TransactionType.MINT -> Color(0xFF4CAF50)
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (transaction.type) {
                            TransactionType.CASHU_SEND -> "Send eCash"
                            TransactionType.CASHU_RECEIVE -> "Receive eCash"  
                            TransactionType.LIGHTNING_SEND, TransactionType.MELT -> "Pay Invoice"
                            TransactionType.LIGHTNING_RECEIVE, TransactionType.MINT -> "Create Invoice"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    )
                }
                
                // Status indicator
                TransactionStatusChip(transaction.status)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Amount row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDateTime(transaction.timestamp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                Text(
                    text = formatAmount(transaction.amount.toLong(), transaction.type),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (transaction.type) {
                        TransactionType.CASHU_SEND, TransactionType.LIGHTNING_SEND, TransactionType.MELT -> 
                            Color(0xFFFF5722)
                        TransactionType.CASHU_RECEIVE, TransactionType.LIGHTNING_RECEIVE, TransactionType.MINT -> 
                            Color(0xFF4CAF50)
                    }
                )
            }
            
            // Memo if present
            transaction.description?.let { description ->
                if (description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "\"$description\"",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.8f),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Additional details row
            transaction.token?.let { details ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (transaction.type) {
                            TransactionType.CASHU_SEND, TransactionType.CASHU_RECEIVE -> "Token ID"
                            TransactionType.LIGHTNING_SEND, TransactionType.LIGHTNING_RECEIVE, 
                            TransactionType.MINT, TransactionType.MELT -> "Quote ID"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = details.take(8) + "...${details.takeLast(4)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(details))
                            },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy Details",
                                tint = colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionStatusChip(status: TransactionStatus) {
    val colorScheme = MaterialTheme.colorScheme
    val (backgroundColor, textColor, text) = when (status) {
        TransactionStatus.PENDING -> Triple(
            Color(0xFFFFC107).copy(alpha = 0.2f),
            Color(0xFFFFC107),
            "PENDING"
        )
        TransactionStatus.CONFIRMED -> Triple(
            Color(0xFF4CAF50).copy(alpha = 0.2f),
            Color(0xFF4CAF50),
            "SUCCESS"
        )
        TransactionStatus.FAILED -> Triple(
            Color(0xFFFF5722).copy(alpha = 0.2f),
            Color(0xFFFF5722),
            "FAILED"
        )
        TransactionStatus.EXPIRED -> Triple(
            colorScheme.onSurface.copy(alpha = 0.2f),
            colorScheme.onSurface.copy(alpha = 0.6f),
            "EXPIRED"
        )
    }
    
    Box(
        modifier = Modifier
            .background(
                color = backgroundColor,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

private fun formatDateTime(date: Date): String {
    val now = Date()
    val diffMillis = now.time - date.time
    val diffMinutes = diffMillis / (1000 * 60)
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24
    
    return when {
        diffMinutes < 1 -> "Just now"
        diffMinutes < 60 -> "${diffMinutes}m ago"
        diffHours < 24 -> "${diffHours}h ago"
        diffDays < 7 -> "${diffDays}d ago"
        else -> SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(date)
    }
}

private fun formatAmount(amount: Long, type: TransactionType): String {
    val prefix = when (type) {
        TransactionType.CASHU_SEND, TransactionType.LIGHTNING_SEND, TransactionType.MELT -> "-"
        TransactionType.CASHU_RECEIVE, TransactionType.LIGHTNING_RECEIVE, TransactionType.MINT -> "+"
    }
    
    return "$prefix$amount sat"
}
