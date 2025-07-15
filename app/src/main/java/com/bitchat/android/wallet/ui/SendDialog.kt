package com.bitchat.android.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                        text = WalletUtils.formatSats(balance),
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
                    SendEcashDialog(
                        viewModel = viewModel,
                        generatedToken = generatedToken,
                        isLoading = isLoading,
                        maxAmount = balance
                    )
                }
                WalletViewModel.SendType.LIGHTNING -> {
                    SendLightningDialog(
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


