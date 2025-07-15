package com.bitchat.android.parsing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log

/**
 * Composable components for rendering parsed message elements
 */

/**
 * Render a list of message elements with proper inline layout
 */
@Composable
fun ParsedMessageContent(
    elements: List<MessageElement>,
    modifier: Modifier = Modifier,
    onCashuPaymentClick: ((ParsedCashuToken) -> Unit)? = null
) {
    // Use a Column with proper text and special element rendering
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var currentTextRow = mutableListOf<MessageElement>()
        
        for (element in elements) {
            when (element) {
                is MessageElement.Text -> {
                    // Add text to current row
                    currentTextRow.add(element)
                }
                is MessageElement.CashuPayment -> {
                    // Flush any accumulated text first
                    if (currentTextRow.isNotEmpty()) {
                        TextRow(elements = currentTextRow.toList())
                        currentTextRow.clear()
                    }
                    
                    // Show the payment chip on its own row
                    CashuPaymentChip(
                        token = element.token,
                        onPaymentClick = onCashuPaymentClick,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        
        // Flush any remaining text
        if (currentTextRow.isNotEmpty()) {
            TextRow(elements = currentTextRow.toList())
        }
    }
}

/**
 * Render a row of text elements
 */
@Composable
fun TextRow(elements: List<MessageElement>) {
    Row(
        modifier = Modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        elements.forEach { element ->
            when (element) {
                is MessageElement.Text -> {
                    Text(
                        text = element.content,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                else -> { /* Skip non-text elements */ }
            }
        }
    }
}

/**
 * Chip component for displaying Cashu payments inline pill
 */
@Composable
fun CashuPaymentChip(
    token: ParsedCashuToken,
    modifier: Modifier = Modifier,
    onPaymentClick: ((ParsedCashuToken) -> Unit)? = null
) {
    Card(
        modifier = modifier
            .clickable { 
                onPaymentClick?.invoke(token) ?: handleCashuPayment(token)
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0E0E0E) // Dark background
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            0.25.dp, 
            Color(0xFF2EC954) // Green outline
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side - Payment info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Top row: Bitcoin icon and "bitcoin" text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Orange Bitcoin circle with symbol inside
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                Color(0xFFFF9F0A), 
                                shape = androidx.compose.foundation.shape.CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "₿",
                            fontSize = 10.sp,
                            color = Color(0xFF140C01),
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            style = androidx.compose.ui.text.TextStyle(
                                lineHeight = 10.sp,
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                    includeFontPadding = false
                                )
                            )
                        )
                    }
                    
                    // "bitcoin" text
                    Text(
                        text = "bitcoin",
                        fontSize = 16.sp,
                        color = Color(0xFF2EC954),
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                // Middle row: Amount and unit
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${token.amount}",
                        fontSize = 24.sp,
                        color = Color(0xFF2EC954),
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "₿",
                        fontSize = 16.sp,
                        color = Color(0xFF2EC954),
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                // Bottom row: Memo (if present)
                if (token.memo?.isNotBlank() == true) {
                    Text(
                        text = "\"${token.memo}\"",
                        fontSize = 10.sp,
                        color = Color(0xFF26A746),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            // Right side - Receive button
            Button(
                onClick = { 
                    onPaymentClick?.invoke(token) ?: handleCashuPayment(token)
                },
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2EC954),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Download/Receive icon
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Receive",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    
                    Text(
                        text = "Receive",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Handle Cashu payment interaction
 */
private fun handleCashuPayment(token: ParsedCashuToken) {
    Log.d("CashuPayment", "User clicked Cashu payment: ${token.originalString}")
    Log.d("CashuPayment", "Amount: ${token.amount} ${token.unit}")
    Log.d("CashuPayment", "Mint: ${token.mintUrl}")
    if (token.memo != null) {
        Log.d("CashuPayment", "Memo: ${token.memo}")
    }
    Log.d("CashuPayment", "Proofs: ${token.proofCount}")
    
    // TODO: Implement wallet integration
}
