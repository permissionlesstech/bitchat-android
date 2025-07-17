package com.bitchat.android.wallet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.data.CashuToken
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Ecash (Cashu) token receive dialog content
 */
@Composable
fun ReceiveEcashDialog(
    viewModel: WalletViewModel,
    decodedToken: CashuToken?,
    isLoading: Boolean,
    focusRequester: FocusRequester
) {
    val token by viewModel.tokenInput.observeAsState("")
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
                        text = WalletUtils.formatSats(decodedToken.amount.toLong()),
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
                    containerColor = if (isLoading) Color(0xFF2A2A2A) else Color(0xFF00C851),
                    disabledContainerColor = Color(0xFF2A2A2A)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "RECEIVING TOKEN...",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
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
                    if (!isLoading) {
                        viewModel.setTokenInput(it)
                    }
                },
                enabled = !isLoading,
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
                    focusedTextColor = Color.White,
                    disabledBorderColor = Color.Gray,
                    disabledLabelColor = Color.Gray,
                    disabledTextColor = Color.Gray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .focusRequester(focusRequester),
                minLines = 3,
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (!isLoading) {
                                // TODO: Implement QR code scanning
                            }
                        },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCode,
                            contentDescription = "Scan QR",
                            tint = if (isLoading) Color.Gray else Color(0xFF00C851)
                        )
                    }
                }
            )
            
            // Paste button
            Button(
                onClick = {
                    clipboardManager.getText()?.text?.let { clipText ->
                        if (clipText.startsWith("cashu")) {
                            viewModel.setTokenInput(clipText)
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent
                ),
                border = BorderStroke(
                    2.dp, 
                    if (isLoading) Color.Gray else Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = "Paste",
                        tint = if (isLoading) Color.Gray else Color(0xFF00C851),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "PASTE FROM CLIPBOARD",
                        color = if (isLoading) Color.Gray else Color(0xFF00C851),
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

 