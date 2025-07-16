package com.bitchat.android.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.wallet.data.Mint
import com.bitchat.android.wallet.viewmodel.WalletViewModel
import com.bitchat.android.wallet.ui.mintinfo.MintDetailsScreen
import java.text.SimpleDateFormat
import java.util.*

/**
 * Mints management screen
 */
@Composable
fun MintsScreen(
    viewModel: WalletViewModel,
    modifier: Modifier = Modifier
) {
    val mints by viewModel.mints.observeAsState(emptyList())
    val activeMint by viewModel.activeMint.observeAsState()
    val showAddMintDialog by viewModel.showAddMintDialog.observeAsState(false)
    val isLoading by viewModel.isLoading.observeAsState(false)
    val errorMessage by viewModel.errorMessage.observeAsState()
    val showMintDetails by viewModel.showMintDetails.observeAsState(false)
    val selectedMintUrl by viewModel.selectedMintUrl.observeAsState()
    
    // Show mint details screen if selected
    if (showMintDetails && selectedMintUrl != null) {
        MintDetailsScreen(
            mintUrl = selectedMintUrl!!,
            walletViewModel = viewModel,
            onNavigateBack = { viewModel.hideMintDetails() },
            modifier = modifier
        )
    } else {
        // Show normal mints list
        MintsListContent(
            viewModel = viewModel,
            mints = mints,
            activeMint = activeMint,
            showAddMintDialog = showAddMintDialog,
            isLoading = isLoading,
            errorMessage = errorMessage,
            modifier = modifier
        )
    }
}

@Composable
private fun MintsListContent(
    viewModel: WalletViewModel,
    mints: List<Mint>,
    activeMint: String?,
    showAddMintDialog: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        // Header with Add button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Mints",
                color = Color(0xFF00C851),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            
            Button(
                onClick = { viewModel.showAddMintDialog() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C851)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add Mint",
                    tint = Color.Black
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Add",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (mints.isEmpty()) {
            EmptyMintsCard(onAddClick = { viewModel.showAddMintDialog() })
        } else {
                    MintsList(
            mints = mints,
            activeMint = activeMint,
            onMintSelect = { viewModel.setActiveMint(it) },
            onMintInfo = { mintUrl -> 
                viewModel.showMintDetails(mintUrl)
            }
        )
        }
        
        // Error message
        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            ErrorCard(
                message = message,
                onDismiss = { viewModel.clearError() }
            )
        }
    }
    
    // Add mint dialog
    if (showAddMintDialog) {
        AddMintDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.hideAddMintDialog() }
        )
    }
}

@Composable
private fun MintsList(
    mints: List<Mint>,
    activeMint: String?,
    onMintSelect: (String) -> Unit,
    onMintInfo: (String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(mints) { mint ->
            MintItem(
                mint = mint,
                isActive = mint.url == activeMint,
                onSelect = { onMintSelect(mint.url) },
                onInfo = { onMintInfo(mint.url) }
            )
        }
    }
}

@Composable
private fun MintItem(
    mint: Mint,
    isActive: Boolean,
    onSelect: () -> Unit,
    onInfo: () -> Unit
) {
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF00C851).copy(alpha = 0.1f) else Color(0xFF1A1A1A)
        ),
        border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00C851)) else null
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mint icon
            Icon(
                imageVector = Icons.Filled.AccountBalance,
                contentDescription = "Mint",
                tint = if (isActive) Color(0xFF00C851) else Color.Gray,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Mint info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mint.nickname,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                
                Text(
                    text = mint.url,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                mint.info?.let { info ->
                    Text(
                        text = info.description ?: info.name,
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                Text(
                    text = "Added ${formatDate(mint.dateAdded)}",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            // Info button
            IconButton(onClick = onInfo) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Mint Details",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            // Active indicator
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Active",
                    tint = Color(0xFF00C851),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
    

}

@Composable
private fun EmptyMintsCard(onAddClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountBalance,
                    contentDescription = "No mints",
                    tint = Color.Gray,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No mints added yet",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Add a mint to start using ecash",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onAddClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C851)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Add first mint",
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
private fun AddMintDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    var mintUrl by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
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
                modifier = Modifier.padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add Mint",
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
                
                // URL input
                OutlinedTextField(
                    value = mintUrl,
                    onValueChange = { mintUrl = it },
                    label = {
                        Text(
                            text = "Mint URL",
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    placeholder = {
                        Text(
                            text = "https://mint.example.com",
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
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Nickname input
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = {
                        Text(
                            text = "Nickname (optional)",
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
                
                // Add button
                Button(
                    onClick = {
                        if (mintUrl.isNotEmpty()) {
                            viewModel.addMint(mintUrl, nickname)
                        }
                    },
                    enabled = !isLoading && mintUrl.isNotEmpty(),
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
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add",
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add Mint",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}



@Composable
private fun ErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x30FF0000))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Error",
                tint = Color.Red,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = message,
                color = Color.Red,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = Color.Red,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Utility function
private fun formatDate(date: Date): String {
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(date)
}
