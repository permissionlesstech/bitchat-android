package com.bitchat.android.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.wallet.data.WalletTransaction
import com.bitchat.android.wallet.data.TransactionType
import com.bitchat.android.wallet.data.TransactionStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog component that shows a generated Cashu token as a QR code
 * with transaction metadata and copy functionality
 */
@Composable
fun SendTokenDialog(
    transaction: WalletTransaction,
    token: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showCopyConfirmation by remember { mutableStateOf(false) }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cashu Token Created",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.Gray
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Transaction Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Success",
                        tint = Color(0xFF00C851),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Token successfully created and ready to share",
                        color = Color(0xFF00C851),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // QR Code
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        QRCodeCanvas(
                            text = token,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Transaction Details
                TransactionDetailsCard(transaction = transaction)
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Token String (collapsible)
                TokenStringCard(token = token)
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Copy Button
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(token))
                        showCopyConfirmation = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C851)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (showCopyConfirmation) "Copied!" else "Copy Token",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                }
                
                // Reset copy confirmation after delay
                LaunchedEffect(showCopyConfirmation) {
                    if (showCopyConfirmation) {
                        kotlinx.coroutines.delay(2000)
                        showCopyConfirmation = false
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "Info",
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "How to use this token:",
                                color = Color(0xFF2196F3),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "• Share the QR code or copy the token string\n" +
                                    "• Recipient can scan or paste into any Cashu wallet\n" +
                                    "• Tokens are bearer instruments - keep them secure\n" +
                                    "• Once redeemed, the token becomes invalid",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionDetailsCard(transaction: WalletTransaction) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Transaction Details",
                color = Color(0xFF00C851),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            DetailRow(
                label = "Amount",
                value = formatAmount(transaction.amount.toLong(), transaction.unit)
            )
            
            DetailRow(
                label = "Type",
                value = formatTransactionType(transaction.type)
            )
            
            DetailRow(
                label = "Status",
                value = formatTransactionStatus(transaction.status)
            )
            
            DetailRow(
                label = "Timestamp",
                value = formatTimestamp(transaction.timestamp)
            )
            
            transaction.description?.let { description ->
                DetailRow(
                    label = "Description",
                    value = description
                )
            }
            
            transaction.mint?.let { mint ->
                DetailRow(
                    label = "Mint",
                    value = extractMintName(mint)
                )
            }
            
            DetailRow(
                label = "Transaction ID",
                value = transaction.id.take(16) + "..."
            )
        }
    }
}

@Composable
private fun TokenStringCard(token: String) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Token String",
                    color = Color(0xFF00C851),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                
                IconButton(
                    onClick = { isExpanded = !isExpanded }
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = Color.Gray
                    )
                }
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                
                SelectionContainer {
                    Text(
                        text = token,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 12.sp
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "${token.take(50)}...",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            color = Color.Gray,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
        
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

// Utility functions
private fun formatAmount(amount: Long, unit: String): String {
    return when (unit.lowercase()) {
        "sat", "sats" -> {
            when {
                amount >= 100_000_000 -> String.format("%.8f BTC", amount / 100_000_000.0)
                amount >= 1000 -> String.format("%,d ₿", amount)
                else -> "$amount ₿"
            }
        }
        else -> "$amount $unit"
    }
}

private fun formatTransactionType(type: TransactionType): String {
    return when (type) {
        TransactionType.CASHU_SEND -> "Cashu Send"
        TransactionType.CASHU_RECEIVE -> "Cashu Receive"
        TransactionType.LIGHTNING_SEND -> "Lightning Send"
        TransactionType.LIGHTNING_RECEIVE -> "Lightning Receive"
        TransactionType.MINT -> "Mint"
        TransactionType.MELT -> "Melt"
    }
}

private fun formatTransactionStatus(status: TransactionStatus): String {
    return when (status) {
        TransactionStatus.PENDING -> "Pending"
        TransactionStatus.CONFIRMED -> "Confirmed"
        TransactionStatus.FAILED -> "Failed"
        TransactionStatus.EXPIRED -> "Expired"
    }
}

private fun formatTimestamp(timestamp: Date): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    return dateFormat.format(timestamp)
}

private fun extractMintName(url: String): String {
    return try {
        val host = url.substringAfter("://").substringBefore("/")
        host.substringBefore(".").replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        "Unknown Mint"
    }
} 