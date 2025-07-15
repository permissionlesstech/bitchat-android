package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.wallet.ui.WalletScreen
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Main app screen with bottom navigation between Chat and Wallet
 */
@Composable
fun MainAppScreen(
    chatViewModel: ChatViewModel,
    onBackPress: (backHandler: () -> Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    // Get wallet ViewModel scoped to this composable
    val walletViewModel: WalletViewModel = viewModel()
    
    // Handle back navigation
    fun handleBackPress(): Boolean {
        return when (selectedTab) {
            0 -> {
                // Chat tab - let ChatViewModel handle it
                chatViewModel.handleBackPressed()
            }
            1 -> {
                // Wallet tab - let WalletViewModel handle it
                walletViewModel.handleBackPress()
            }
            else -> false
        }
    }
    
    // Expose back handler to MainActivity
    LaunchedEffect(Unit) {
        onBackPress { handleBackPress() }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        // Content based on selected tab
        when (selectedTab) {
            0 -> ChatScreen(
                viewModel = chatViewModel,
                onWalletClick = { selectedTab = 1 }, // Switch to wallet tab when header button is clicked
                onWalletClickWithToken = { token ->
                    // Switch to wallet and open receive dialog with the token
                    selectedTab = 1
                    walletViewModel.openReceiveDialogWithToken(token)
                }
            ) 
            1 -> WalletScreen(
                walletViewModel = walletViewModel,
                onBackToChat = { selectedTab = 0 }
            )
        }
        
        // Bottom Navigation
        AppBottomNavigation(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )
    }
    
    // Set up back handler for wallet
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            // When wallet is active, set up its back handler
            walletViewModel.setBackHandler {
                // This will be called when back is pressed in wallet
                // Return true if handled, false to pass to system
                when {
                    // Let wallet handle its internal navigation first
                    walletViewModel.showSendDialog.value == true -> {
                        walletViewModel.hideSendDialog()
                        true
                    }
                    walletViewModel.showReceiveDialog.value == true -> {
                        walletViewModel.hideReceiveDialog()
                        true
                    }
                    else -> {
                        // Go back to chat
                        selectedTab = 0
                        true
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF1A1A1A),
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            icon = {
                Icon(
                    imageVector = Icons.Filled.Chat,
                    contentDescription = "Chat",
                    modifier = Modifier.size(20.dp)
                )
            },
            label = {
                Text(
                    text = "Chat",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF00C851),
                selectedTextColor = Color(0xFF00C851),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color(0xFF00C851).copy(alpha = 0.2f)
            )
        )
        
        NavigationBarItem(
            icon = {
                Icon(
                    imageVector = Icons.Filled.AccountBalanceWallet,
                    contentDescription = "Wallet",
                    modifier = Modifier.size(20.dp)
                )
            },
            label = {
                Text(
                    text = "Wallet",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF00C851),
                selectedTextColor = Color(0xFF00C851),
                unselectedIconColor = Color.Gray,
                unselectedTextColor = Color.Gray,
                indicatorColor = Color(0xFF00C851).copy(alpha = 0.2f)
            )
        )
    }
}
