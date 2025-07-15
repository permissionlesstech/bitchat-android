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
                    ReceiveEcashDialog(
                        viewModel = viewModel,
                        decodedToken = decodedToken,
                        isLoading = isLoading
                    )
                }
                WalletViewModel.ReceiveType.LIGHTNING -> {
                    ReceiveLightningDialog(
                        viewModel = viewModel,
                        currentMintQuote = currentMintQuote,
                        isLoading = isLoading
                    )
                }
            }
        }
    }
}


