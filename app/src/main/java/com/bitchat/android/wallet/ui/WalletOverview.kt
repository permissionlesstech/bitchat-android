package com.bitchat.android.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.data.WalletTransaction
import com.bitchat.android.wallet.data.TransactionType
import com.bitchat.android.wallet.data.TransactionStatus
import com.bitchat.android.wallet.service.CashuService
import com.bitchat.android.wallet.viewmodel.WalletViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main wallet overview screen showing balance and recent transactions
 */
@Composable
fun WalletOverview(
    viewModel: WalletViewModel,
    onBackToChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val balance by viewModel.balance.observeAsState(0L)
    val transactions by viewModel.transactions.observeAsState(emptyList())
    val isLoading by viewModel.isLoading.observeAsState(false)
    val errorMessage by viewModel.errorMessage.observeAsState()
    val activeMint by viewModel.activeMint.observeAsState()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Back to Chat button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(
                onClick = onBackToChat,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF00C851)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back to Chat",
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF00C851)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Chat",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = Color(0xFF00C851)
                )
            }
        }
        
        // Balance Card
        BalanceCard(
            balance = balance,
            isLoading = isLoading,
            onSendClick = { viewModel.showSendDialog() },
            onReceiveClick = { viewModel.showReceiveDialog() }
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Recent Transactions
        Text(
            text = "Recent Transactions",
            color = Color(0xFF00C851),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (transactions.isEmpty()) {
            EmptyTransactionsCard()
        } else {
            TransactionsList(transactions = transactions)
        }
        
        // Error message
        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            ErrorCard(
                message = message,
                onDismiss = { viewModel.clearError() }
            )
        }
    }
}

private fun extractMintName(url: String): String {
    return try {
        val host = url.substringAfter("://").substringBefore("/")
        host.substringBefore(".").replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        "Cashu Mint"
    }
}

@Composable
private fun BalanceCard(
    balance: Long,
    isLoading: Boolean,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Balance",
                color = Color.Gray,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (isLoading) {
                CircularProgressIndicator(
                    color = Color(0xFF00C851),
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Text(
                    text = formatSats(balance),
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Send button
                Button(
                    onClick = onSendClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C851)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Send",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                // Receive button
                Button(
                    onClick = onReceiveClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, 
                        Color(0xFF00C851)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Receive",
                        tint = Color(0xFF00C851),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Receive",
                        color = Color(0xFF00C851),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionsList(transactions: List<WalletTransaction>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(transactions) { transaction ->
            TransactionItem(transaction = transaction)
        }
    }
}

@Composable
private fun TransactionItem(transaction: WalletTransaction) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Transaction type icon
            Icon(
                imageVector = when (transaction.type) {
                    TransactionType.CASHU_SEND -> Icons.Filled.Send
                    TransactionType.CASHU_RECEIVE -> Icons.Filled.KeyboardArrowDown
                    TransactionType.LIGHTNING_SEND -> Icons.Filled.FlashOn
                    TransactionType.LIGHTNING_RECEIVE -> Icons.Filled.FlashOn
                    TransactionType.MINT -> Icons.Filled.Add
                    TransactionType.MELT -> Icons.Filled.Remove
                },
                contentDescription = transaction.type.name,
                tint = when (transaction.type) {
                    TransactionType.CASHU_SEND, TransactionType.LIGHTNING_SEND, TransactionType.MELT -> Color.Red
                    TransactionType.CASHU_RECEIVE, TransactionType.LIGHTNING_RECEIVE, TransactionType.MINT -> Color(0xFF00C851)
                },
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Transaction details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.description ?: getDefaultDescription(transaction.type),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = formatTimestamp(transaction.timestamp),
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            // Amount with fee if applicable
            Column(
                horizontalAlignment = Alignment.End
            ) {
                val isIncoming = transaction.type in listOf(
                    TransactionType.CASHU_RECEIVE,
                    TransactionType.LIGHTNING_RECEIVE,
                    TransactionType.MINT
                )
                
                Text(
                    text = "${if (isIncoming) "+" else "-"}${formatSats(transaction.amount.toLong())}",
                    color = if (isIncoming) Color(0xFF00C851) else Color.Red,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                
                transaction.fee?.let { fee ->
                    if (fee.toLong() > 0) {
                        Text(
                            text = "fee: ${formatSats(fee.toLong())}",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Status indicator
            when (transaction.status) {
                TransactionStatus.PENDING -> {
                    CircularProgressIndicator(
                        color = Color.Yellow,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                TransactionStatus.CONFIRMED -> {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Confirmed",
                        tint = Color(0xFF00C851),
                        modifier = Modifier.size(16.dp)
                    )
                }
                TransactionStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Failed",
                        tint = Color.Red,
                        modifier = Modifier.size(16.dp)
                    )
                }
                TransactionStatus.EXPIRED -> {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = "Expired",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTransactionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = "No transactions",
                    tint = Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No transactions yet",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Start by sending or receiving some sats!",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x30FF0000))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Error",
                tint = Color.Red,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = message,
                color = Color.Red,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = Color.Red,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Utility functions

private fun formatSats(sats: Long): String {
    return when {
        sats >= 100_000_000 -> String.format("%.8f BTC", sats / 100_000_000.0)
        sats >= 1000 -> String.format("%,d ₿", sats)
        else -> "$sats ₿"
    }
}

private fun formatTimestamp(timestamp: Date): String {
    val now = Date()
    val diff = now.time - timestamp.time
    
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(timestamp)
    }
}

private fun getDefaultDescription(type: TransactionType): String {
    return when (type) {
        TransactionType.CASHU_SEND -> "Cashu token sent"
        TransactionType.CASHU_RECEIVE -> "Cashu token received"
        TransactionType.LIGHTNING_SEND -> "Lightning payment sent"
        TransactionType.LIGHTNING_RECEIVE -> "Lightning payment received"
        TransactionType.MINT -> "Ecash minted"
        TransactionType.MELT -> "Ecash melted"
    }
}
