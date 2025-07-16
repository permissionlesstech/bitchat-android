package com.bitchat.android.wallet.ui.mintinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.data.Mint
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Detailed mint information screen with terminal-like design
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MintDetailsScreen(
    mintUrl: String,
    walletViewModel: WalletViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mints by walletViewModel.mints.observeAsState(emptyList())
    val isLoading by walletViewModel.isLoading.observeAsState(false)
    val errorMessage by walletViewModel.errorMessage.observeAsState()
    
    // Find the mint by URL
    val mint = mints.find { it.url == mintUrl }
    
    // Show error if mint not found
    if (mint == null) {
        MintNotFoundScreen(onNavigateBack = onNavigateBack)
        return
    }
    
    // State for MOTD dismissal
    var isMotdDismissed by remember { mutableStateOf(false) }
    
    // State for dialogs
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header with back button
        TopAppBar(
            title = {
                Text(
                    text = "Mint Details",
                    color = Color(0xFF00C851),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF00C851)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black
            )
        )
        
        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mint Header Section
            MintHeaderSection(mint = mint)
            
            // MOTD Section
            if (mint.info?.motd?.isNotEmpty() == true) {
                MintMotdComponent(
                    message = mint.info.motd,
                    isDismissed = isMotdDismissed,
                    onDismiss = { isMotdDismissed = true }
                )
            }
            
            // Description Section
            MintDescriptionSection(mint = mint)
            
            // Contact Section
            if (mint.info?.contact?.isNotEmpty() == true) {
                MintContactSection(contacts = mint.info.contact)
            }
            
            // Details Section
            MintDetailsSection(mint = mint)
            
            // Actions Section
            MintActionButtons(
                mint = mint,
                onEditNickname = { showEditDialog = true },
                onCopyUrl = { walletViewModel.copyToClipboard(mint.url) },
                onDeleteMint = { showDeleteDialog = true }
            )
            
            // Error display
            errorMessage?.let { error ->
                ErrorCard(
                    message = error,
                    onDismiss = { walletViewModel.clearError() }
                )
            }
            
            // Bottom spacing
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    // Edit nickname dialog
    if (showEditDialog) {
        EditMintNicknameDialog(
            currentNickname = mint.nickname,
            onSave = { newNickname ->
                walletViewModel.updateMintNickname(mint.url, newNickname)
                showEditDialog = false
            },
            onDismiss = { showEditDialog = false }
        )
    }
    
    // Delete mint dialog
    if (showDeleteDialog) {
        DeleteMintDialog(
            mint = mint,
            onConfirm = {
                walletViewModel.deleteMint(mint.url)
                showDeleteDialog = false
                onNavigateBack()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
    
    // Loading overlay
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = Color(0xFF00C851)
            )
        }
    }
}

@Composable
private fun MintNotFoundScreen(
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Error",
            tint = Color.Red,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Mint Not Found",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        
        Text(
            text = "The requested mint could not be found",
            color = Color.Gray,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onNavigateBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00C851)
            )
        ) {
            Text(
                text = "Go Back",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0x30FF0000)
        )
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