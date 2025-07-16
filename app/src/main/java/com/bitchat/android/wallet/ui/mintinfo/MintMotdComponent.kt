package com.bitchat.android.wallet.ui.mintinfo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Component for displaying mint MOTD (Message of the Day) with dismiss functionality
 */
@Composable
fun MintMotdComponent(
    message: String,
    isDismissed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.isEmpty()) return
    
    if (!isDismissed) {
        // Active MOTD card
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clickable(enabled = false) { }, // Prevent accidental clicks
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            ),
            border = BorderStroke(1.dp, Color(0xFFF18408)) // Orange border like in Vue version
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Info icon
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "MOTD",
                    tint = Color(0xFFF18408),
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // Content
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Header with title and close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Message of the Day",
                            color = Color(0xFFF18408),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                        
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                tint = Color(0xFFF18408),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    // MOTD message
                    Text(
                        text = message,
                        color = Color(0xFFF18408),
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    } else {
        // Dismissed MOTD - minimal display
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clickable { /* Could expand back if needed */ },
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A1A)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "MOTD",
                    tint = Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = "Message of the Day",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Text(
                        text = message,
                        color = Color.Gray,
                        fontSize = 14.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2 // Truncate in dismissed state
                    )
                }
            }
        }
    }
} 