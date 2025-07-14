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
import com.bitchat.android.wallet.data.MintQuote
import com.bitchat.android.wallet.data.CashuToken
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Full-screen receive view with options for Cashu tokens or Lightning invoices
 */
@Composable
fun ReceiveView(
    viewModel: WalletViewModel,
    onNavigateBack: () -> Unit
) {
    val receiveType by viewModel.receiveType.observeAsState(WalletViewModel.ReceiveType.CASHU)
    val decodedToken by viewModel.decodedToken.observeAsState()
    val currentMintQuote by viewModel.currentMintQuote.observeAsState()
    val isLoading by viewModel.isLoading.observeAsState(false)
    
    // Determine if we should show type selector
    val showTypeSelector = decodedToken == null && currentMintQuote == null
    
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
                    text = "RECEIVE",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                
                // Spacer to center the title
                Spacer(modifier = Modifier.width(48.dp))
            }
            
            // Receive type selector (only show when not displaying QR code or token info)
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
                            onClick = { viewModel.setReceiveType(WalletViewModel.ReceiveType.CASHU) },
                            label = {
                                Text(
                                    text = "Cashu Token",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            selected = receiveType == WalletViewModel.ReceiveType.CASHU,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00C851),
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF2A2A2A),
                                labelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        
                        FilterChip(
                            onClick = { viewModel.setReceiveType(WalletViewModel.ReceiveType.LIGHTNING) },
                            label = {
                                Text(
                                    text = "Lightning Invoice",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            selected = receiveType == WalletViewModel.ReceiveType.LIGHTNING,
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
                        text = "CASHU TOKEN",
                        color = Color(0xFF00C851),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Amount
                    Text(
                        text = formatSats(decodedToken.amount.toLong()),
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Mint info
                    Text(
                        text = "From: ${decodedToken.mint.take(30)}${if (decodedToken.mint.length > 30) "..." else ""}",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                    
                    if (!decodedToken.memo.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Memo: ${decodedToken.memo}",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            // Receive button
            Button(
                onClick = {
                    viewModel.receiveCashuToken(decodedToken.token)
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
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Receive",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "RECEIVE TOKEN",
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
        // Show token input
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Token input field
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
                        if (clipText.startsWith("cashu")) {
                            token = clipText
                            viewModel.decodeCashuToken(clipText)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Invoice header
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
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
                        text = "LIGHTNING INVOICE",
                        color = Color(0xFFFFB000),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Amount
                    Text(
                        text = formatSats(currentMintQuote.amount.toLong()),
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Status
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (currentMintQuote.paid) Color(0xFF00C851) else Color(0xFFFFB000))
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Text(
                            text = if (currentMintQuote.paid) "PAID" else "WAITING FOR PAYMENT",
                            color = if (currentMintQuote.paid) Color(0xFF00C851) else Color(0xFFFFB000),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
            
            // QR Code 
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(bottom = 24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    QRCodeCanvas(
                        text = currentMintQuote.request,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            // Copy invoice button
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(currentMintQuote.request))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                border = androidx.compose.foundation.BorderStroke(
                    2.dp, 
                    Color(0xFFFFB000)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color(0xFFFFB000),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "COPY INVOICE",
                        color = Color(0xFFFFB000),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    } else {
        // Show amount input
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
            
            // Description input
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
            
            // Create invoice button
            Button(
                onClick = {
                    amount.toLongOrNull()?.let { amountSats ->
                        viewModel.createMintQuote(amountSats, description.ifEmpty { null })
                    }
                },
                enabled = !isLoading && amount.toLongOrNull()?.let { it > 0 } == true,
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
                            contentDescription = "Create Invoice",
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "CREATE INVOICE",
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
