package com.bitchat.android.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.wallet.data.MeltQuote
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Send dialog with options for Cashu tokens or Lightning payments
 */
@Composable
fun SendDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    val sendType by viewModel.sendType.observeAsState(WalletViewModel.SendType.CASHU)
    val generatedToken by viewModel.generatedToken.observeAsState()
    val currentMeltQuote by viewModel.currentMeltQuote.observeAsState()
    val isLoading by viewModel.isLoading.observeAsState(false)
    val balance by viewModel.balance.observeAsState(0L)
    
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
                        text = "Send",
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
                
                // Balance display
                Text(
                    text = "Available: ${formatSats(balance)}",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Send type selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        onClick = { viewModel.setSendType(WalletViewModel.SendType.CASHU) },
                        label = {
                            Text(
                                text = "Cashu",
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        selected = sendType == WalletViewModel.SendType.CASHU,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00C851),
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    
                    FilterChip(
                        onClick = { viewModel.setSendType(WalletViewModel.SendType.LIGHTNING) },
                        label = {
                            Text(
                                text = "Lightning",
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        selected = sendType == WalletViewModel.SendType.LIGHTNING,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00C851),
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Content based on send type
                when (sendType) {
                    WalletViewModel.SendType.CASHU -> {
                        CashuSendContent(
                            viewModel = viewModel,
                            generatedToken = generatedToken,
                            isLoading = isLoading,
                            maxAmount = balance
                        )
                    }
                    WalletViewModel.SendType.LIGHTNING -> {
                        LightningSendContent(
                            viewModel = viewModel,
                            currentMeltQuote = currentMeltQuote,
                            isLoading = isLoading,
                            maxAmount = balance
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CashuSendContent(
    viewModel: WalletViewModel,
    generatedToken: String?,
    isLoading: Boolean,
    maxAmount: Long
) {
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    
    if (generatedToken != null) {
        // Show generated token
        Column {
            Text(
                text = "Cashu Token Generated",
                color = Color(0xFF00C851),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
            ) {
                SelectionContainer {
                    Text(
                        text = generatedToken,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(generatedToken))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Copy Token",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    } else {
        // Show input form
        Column {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = {
                    Text(
                        text = "Amount (sats)",
                        fontFamily = FontFamily.Monospace
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00C851),
                    focusedLabelColor = Color(0xFF00C851),
                    unfocusedTextColor = Color.White,
                    focusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = {
                    Text(
                        text = "Memo (optional)",
                        fontFamily = FontFamily.Monospace
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00C851),
                    focusedLabelColor = Color(0xFF00C851),
                    unfocusedTextColor = Color.White,
                    focusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    amount.toLongOrNull()?.let { amountSats ->
                        viewModel.createCashuToken(amountSats, memo.ifEmpty { null })
                    }
                },
                enabled = !isLoading && amount.toLongOrNull()?.let { it > 0 && it <= maxAmount } == true,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Create Token",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create Token",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun LightningSendContent(
    viewModel: WalletViewModel,
    currentMeltQuote: MeltQuote?,
    isLoading: Boolean,
    maxAmount: Long
) {
    var invoice by remember { mutableStateOf("") }
    
    if (currentMeltQuote != null) {
        // Show quote and pay button
        Column {
            Text(
                text = "Lightning Invoice Quote",
                color = Color(0xFF00C851),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Amount:",
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = formatSats(currentMeltQuote.amount.toLong()),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    
                    if (currentMeltQuote.feeReserve.toLong() > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Fee:",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = formatSats(currentMeltQuote.feeReserve.toLong()),
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Total:",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatSats(currentMeltQuote.amount.toLong() + currentMeltQuote.feeReserve.toLong()),
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    viewModel.payLightningInvoice(currentMeltQuote.id)
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.FlashOn,
                        contentDescription = "Pay Invoice",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Pay Invoice",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    } else {
        // Show invoice input
        Column {
            OutlinedTextField(
                value = invoice,
                onValueChange = { invoice = it },
                label = {
                    Text(
                        text = "Lightning Invoice",
                        fontFamily = FontFamily.Monospace
                    )
                },
                placeholder = {
                    Text(
                        text = "lnbc...",
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00C851),
                    focusedLabelColor = Color(0xFF00C851),
                    unfocusedTextColor = Color.White,
                    focusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    if (invoice.isNotEmpty()) {
                        viewModel.createMeltQuote(invoice)
                    }
                },
                enabled = !isLoading && invoice.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Assessment,
                        contentDescription = "Get Quote",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Get Quote",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// Utility function to format sats
private fun formatSats(sats: Long): String {
    return when {
        sats >= 100_000_000 -> String.format("%.2f BTC", sats / 100_000_000.0)
        sats >= 1000 -> String.format("%,d sats", sats)
        else -> "$sats sats"
    }
}
