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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.data.MeltQuote
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Full-screen send view with options for Cashu tokens or Lightning payments
 */
@Composable
fun SendView(
    viewModel: WalletViewModel,
    onNavigateBack: () -> Unit
) {
    val sendType by viewModel.sendType.observeAsState(WalletViewModel.SendType.CASHU)
    val generatedToken by viewModel.generatedToken.observeAsState()
    val currentMeltQuote by viewModel.currentMeltQuote.observeAsState()
    val isLoading by viewModel.isLoading.observeAsState(false)
    val balance by viewModel.balance.observeAsState(0L)
    
    // Determine if we should show type selector
    val showTypeSelector = generatedToken == null && currentMeltQuote == null
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header with back button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A))
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                Text(
                    text = "SEND",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                
                // Spacer to center the title
                Spacer(modifier = Modifier.width(48.dp))
            }
            
            // Balance display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AVAILABLE BALANCE",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    
                    Text(
                        text = formatSats(balance),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            // Send type selector (only show when not displaying generated token or quote)
            if (showTypeSelector) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            onClick = { viewModel.setSendType(WalletViewModel.SendType.CASHU) },
                            label = {
                                Text(
                                    text = "Cashu Token",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            selected = sendType == WalletViewModel.SendType.CASHU,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00C851),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF2A2A2A),
                                labelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        
                        FilterChip(
                            onClick = { viewModel.setSendType(WalletViewModel.SendType.LIGHTNING) },
                            label = {
                                Text(
                                    text = "Lightning Payment",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            selected = sendType == WalletViewModel.SendType.LIGHTNING,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00C851),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF2A2A2A),
                                labelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Token info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Toll,
                        contentDescription = "Token",
                        tint = Color(0xFF00C851),
                        modifier = Modifier.size(48.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "CASHU TOKEN CREATED",
                        color = Color(0xFF00C851),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Token display
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                    ) {
                        SelectionContainer {
                            Text(
                                text = generatedToken,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            
            // Copy button
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(generatedToken))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "COPY TOKEN",
                        color = Color.Black,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    } else {
        // Show input form
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Amount input
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
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    unfocusedLabelColor = Color.Gray,
                    unfocusedTextColor = Color.White,
                    focusedTextColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp)
            )
            
            // Memo input
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
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    unfocusedLabelColor = Color.Gray,
                    unfocusedTextColor = Color.White,
                    focusedTextColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                shape = RoundedCornerShape(16.dp)
            )
            
            // Create token button
            Button(
                onClick = {
                    amount.toLongOrNull()?.let { amountSats ->
                        viewModel.createCashuToken(amountSats, memo.ifEmpty { null })
                    }
                },
                enabled = !isLoading && amount.toLongOrNull()?.let { it > 0 && it <= maxAmount } == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851),
                    disabledContainerColor = Color(0xFF2A2A2A)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Toll,
                            contentDescription = "Create Token",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "CREATE TOKEN",
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
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
    val clipboardManager = LocalClipboardManager.current
    
    if (currentMeltQuote != null) {
        // Show quote and pay button
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Quote info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.FlashOn,
                        contentDescription = "Lightning",
                        tint = Color(0xFFFFB000),
                        modifier = Modifier.size(48.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "LIGHTNING PAYMENT QUOTE",
                        color = Color(0xFFFFB000),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Quote details
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
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
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = formatSats(currentMeltQuote.amount.toLong()),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            if (currentMeltQuote.feeReserve.toLong() > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Fee:",
                                        color = Color.Gray,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = formatSats(currentMeltQuote.feeReserve.toLong()),
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = Color.Gray)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Total:",
                                        color = Color.Gray,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = formatSats(currentMeltQuote.amount.toLong() + currentMeltQuote.feeReserve.toLong()),
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Pay button
            Button(
                onClick = {
                    viewModel.payLightningInvoice(currentMeltQuote.id)
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851),
                    disabledContainerColor = Color(0xFF2A2A2A)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FlashOn,
                            contentDescription = "Pay Invoice",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "PAY INVOICE",
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    } else {
        // Show invoice input
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Invoice input field
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
                    unfocusedBorderColor = Color(0xFF2A2A2A),
                    unfocusedLabelColor = Color.Gray,
                    unfocusedTextColor = Color.White,
                    focusedTextColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                minLines = 3,
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            // TODO: Implement QR code scanning
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCode,
                            contentDescription = "Scan QR",
                            tint = Color(0xFF00C851)
                        )
                    }
                }
            )
            
            // Paste button
            Button(
                onClick = {
                    clipboardManager.getText()?.text?.let { clipText ->
                        if (clipText.startsWith("lnbc") || clipText.startsWith("lnbtb")) {
                            invoice = clipText
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                border = androidx.compose.foundation.BorderStroke(
                    2.dp, 
                    Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = "Paste",
                        tint = Color(0xFF00C851),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "PASTE FROM CLIPBOARD",
                        color = Color(0xFF00C851),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
            
            // Get quote button
            Button(
                onClick = {
                    if (invoice.isNotEmpty()) {
                        viewModel.createMeltQuote(invoice)
                    }
                },
                enabled = !isLoading && invoice.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851),
                    disabledContainerColor = Color(0xFF2A2A2A)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Assessment,
                            contentDescription = "Get Quote",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "GET QUOTE",
                            color = Color.Black,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
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
