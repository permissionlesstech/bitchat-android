package com.bitchat.android.wallet.ui

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.wallet.data.MintQuote
import com.bitchat.android.wallet.data.CashuToken
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Receive dialog with options for Cashu tokens or Lightning invoices
 */
@Composable
fun ReceiveDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    val receiveType by viewModel.receiveType.observeAsState(WalletViewModel.ReceiveType.CASHU)
    val decodedToken by viewModel.decodedToken.observeAsState()
    val currentMintQuote by viewModel.currentMintQuote.observeAsState()
    val isLoading by viewModel.isLoading.observeAsState(false)
    
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
                        text = "Receive",
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
                
                // Receive type selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        onClick = { viewModel.setReceiveType(WalletViewModel.ReceiveType.CASHU) },
                        label = {
                            Text(
                                text = "Cashu",
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        selected = receiveType == WalletViewModel.ReceiveType.CASHU,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00C851),
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    
                    FilterChip(
                        onClick = { viewModel.setReceiveType(WalletViewModel.ReceiveType.LIGHTNING) },
                        label = {
                            Text(
                                text = "Lightning",
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        selected = receiveType == WalletViewModel.ReceiveType.LIGHTNING,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00C851),
                            selectedLabelColor = Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Content based on receive type
                when (receiveType) {
                    WalletViewModel.ReceiveType.CASHU -> {
                        CashuReceiveContent(
                            viewModel = viewModel,
                            decodedToken = decodedToken,
                            isLoading = isLoading
                        )
                    }
                    WalletViewModel.ReceiveType.LIGHTNING -> {
                        LightningReceiveContent(
                            viewModel = viewModel,
                            currentMintQuote = currentMintQuote,
                            isLoading = isLoading
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CashuReceiveContent(
    viewModel: WalletViewModel,
    decodedToken: CashuToken?,
    isLoading: Boolean
) {
    var token by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    
    if (decodedToken != null) {
        // Show decoded token info and receive button
        Column {
            Text(
                text = "Cashu Token Details",
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
                            text = formatSats(decodedToken.amount.toLong()),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Mint:",
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = decodedToken.mint.take(20) + if (decodedToken.mint.length > 20) "..." else "",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    
                    if (!decodedToken.memo.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Memo:",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = decodedToken.memo,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = {
                    viewModel.receiveCashuToken(decodedToken.token)
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
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Receive",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Receive Token",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    } else {
        // Show token input
        Column {
            OutlinedTextField(
                value = token,
                onValueChange = { 
                    token = it
                    if (it.isNotEmpty() && it.startsWith("cashu")) {
                        viewModel.decodeCashuToken(it)
                    }
                },
                label = {
                    Text(
                        text = "Cashu Token",
                        fontFamily = FontFamily.Monospace
                    )
                },
                placeholder = {
                    Text(
                        text = "cashu...",
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
                maxLines = 3,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            // TODO: Implement QR code scanning
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Scan QR",
                            tint = Color(0xFF00C851)
                        )
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    clipboardManager.getText()?.text?.let { clipText ->
                        if (clipText.startsWith("cashu")) {
                            token = clipText
                            viewModel.decodeCashuToken(clipText)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
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
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = "Paste",
                    tint = Color(0xFF00C851)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Paste from clipboard",
                    color = Color(0xFF00C851),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LightningReceiveContent(
    viewModel: WalletViewModel,
    currentMintQuote: MintQuote?,
    isLoading: Boolean
) {
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    
    if (currentMintQuote != null) {
        // Show Lightning invoice QR code and details
        Column {
            Text(
                text = "Lightning Invoice",
                color = Color(0xFF00C851),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // QR Code 
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    QRCodeCanvas(
                        text = currentMintQuote.request,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Invoice details
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
                            text = formatSats(currentMintQuote.amount.toLong()),
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Status:",
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (currentMintQuote.paid) "Paid" else "Waiting...",
                            color = if (currentMintQuote.paid) Color(0xFF00C851) else Color.Yellow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Copy invoice button
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(currentMintQuote.request))
                },
                modifier = Modifier.fillMaxWidth(),
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
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    tint = Color(0xFF00C851)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Copy Invoice",
                    color = Color(0xFF00C851),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    } else {
        // Show amount input
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
                value = description,
                onValueChange = { description = it },
                label = {
                    Text(
                        text = "Description (optional)",
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
                        viewModel.createMintQuote(amountSats, description.ifEmpty { null })
                    }
                },
                enabled = !isLoading && amount.toLongOrNull()?.let { it > 0 } == true,
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
                        contentDescription = "Create Invoice",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Create Invoice",
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
