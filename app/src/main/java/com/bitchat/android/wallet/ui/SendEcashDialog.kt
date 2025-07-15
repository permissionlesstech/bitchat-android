package com.bitchat.android.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.parsing.CashuTokenParser
import com.bitchat.android.wallet.ui.WalletUtils
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Ecash (Cashu) token send dialog content
 */
@Composable
fun SendEcashDialog(
    viewModel: WalletViewModel,
    generatedToken: String?,
    isLoading: Boolean,
    maxAmount: Long
) {
    var amount by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    
    if (generatedToken != null) {
        // Parse token to extract information
        val parser = remember { CashuTokenParser() }
        val parsedToken = remember(generatedToken) { parser.parseToken(generatedToken) }
        
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
                        text = "CASHU TOKEN",
                        color = Color(0xFF00C851),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Token amount and mint info
                    parsedToken?.let { token ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = WalletUtils.formatSats(token.amount),
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = "From: ${token.mintUrl}",
                                color = Color(0xFF888888),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Token display (truncated)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                    ) {
                        SelectionContainer {
                            Text(
                                text = if (generatedToken.length > 50) {
                                    "${generatedToken.take(50)}..."
                                } else {
                                    generatedToken
                                },
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
            // Request focus on the amount input when dialog opens
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
            
            // Amount input
            OutlinedTextField(
                value = amount,
                onValueChange = { if (!isLoading) amount = it },
                enabled = !isLoading,
                label = {
                    Text(
                        text = "Amount (₿)",
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
                    focusedTextColor = Color.White,
                    disabledBorderColor = Color.Gray,
                    disabledLabelColor = Color.Gray,
                    disabledTextColor = Color.Gray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .focusRequester(focusRequester),
                shape = RoundedCornerShape(16.dp)
            )
            
            // Memo input
            OutlinedTextField(
                value = memo,
                onValueChange = { if (!isLoading) memo = it },
                enabled = !isLoading,
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
                    focusedTextColor = Color.White,
                    disabledBorderColor = Color.Gray,
                    disabledLabelColor = Color.Gray,
                    disabledTextColor = Color.Gray
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
                            text = "CREATING TOKEN...",
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