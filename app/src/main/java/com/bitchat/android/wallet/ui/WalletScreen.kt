package com.bitchat.android.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.wallet.viewmodel.WalletViewModel

/**
 * Main wallet screen with bottom navigation
 */
@Composable
fun WalletScreen(
    walletViewModel: WalletViewModel = viewModel(),
    onBackToChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    var showReceiveView by remember { mutableStateOf(false) }
    var showSendView by remember { mutableStateOf(false) }
    val showSendDialog by walletViewModel.showSendDialog.observeAsState(false)
    val showReceiveDialog by walletViewModel.showReceiveDialog.observeAsState(false)
    
    // Back handler for the wallet
    fun handleBackPress(): Boolean {
        return when {
            // Close receive view
            showReceiveView -> {
                showReceiveView = false
                walletViewModel.hideReceiveDialog()
                true
            }
            // Close send view
            showSendView -> {
                showSendView = false
                walletViewModel.hideSendDialog()
                true
            }
            // Close send dialog
            showSendDialog -> {
                walletViewModel.hideSendDialog()
                true
            }
            // If we're not in the wallet tab, go back to wallet tab
            selectedTab != 0 -> {
                selectedTab = 0
                true
            }
            // If we're in the wallet tab, go back to chat
            else -> {
                onBackToChat()
                true
            }
        }
    }
    
    if (showReceiveView) {
        // Full-screen ReceiveView
        ReceiveView(
            viewModel = walletViewModel,
            onNavigateBack = { 
                showReceiveView = false 
                walletViewModel.hideReceiveDialog()
            }
        )
    } else if (showSendView) {
        // Full-screen SendView
        SendView(
            viewModel = walletViewModel,
            onNavigateBack = { 
                showSendView = false 
                walletViewModel.hideSendDialog()
            }
        )
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            // Content
            when (selectedTab) {
                0 -> WalletOverview(
                    viewModel = walletViewModel,
                    onBackToChat = onBackToChat,
                    modifier = Modifier.weight(1f)
                )
                1 -> TransactionHistory(
                    transactions = walletViewModel.getAllTransactions().observeAsState(initial = emptyList()).value,
                    modifier = Modifier.weight(1f),
                    onTransactionClick = { transaction ->
                        // For lightning receive transactions, open the receive view with the quote
                        if (transaction.type == com.bitchat.android.wallet.data.TransactionType.LIGHTNING_RECEIVE && 
                            transaction.quote != null) {
                            walletViewModel.setCurrentMintQuote(transaction.quote!!)
                            showReceiveView = true
                        }
                    }
                )
                2 -> MintsScreen(viewModel = walletViewModel, modifier = Modifier.weight(1f))
                3 -> WalletSettings(
                    viewModel = walletViewModel,
                    onBackClick = { /* No back action needed in tab navigation */ }
                )
            }
            
            // Bottom Navigation
            WalletBottomNavigation(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    }
    
    // Handle dialog states
    if (showSendDialog) {
        LaunchedEffect(showSendDialog) {
            if (showSendDialog) {
                showSendView = true
            }
        }
    }
    
    if (showReceiveDialog) {
        LaunchedEffect(showReceiveDialog) {
            if (showReceiveDialog) {
                showReceiveView = true
            }
        }
    }
    
    // Expose the back handler to the parent
    // This will be called from MainAppScreen
    walletViewModel.setBackHandler { handleBackPress() }
}

@Composable
private fun WalletBottomNavigation(
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
                    imageVector = Icons.Filled.History,
                    contentDescription = "History",
                    modifier = Modifier.size(20.dp)
                )
            },
            label = {
                Text(
                    text = "History",
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
        
        NavigationBarItem(
            icon = {
                Icon(
                    imageVector = Icons.Filled.AccountBalance,
                    contentDescription = "Mints",
                    modifier = Modifier.size(20.dp)
                )
            },
            label = {
                Text(
                    text = "Mints",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
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
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(20.dp)
                )
            },
            label = {
                Text(
                    text = "Settings",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            },
            selected = selectedTab == 3,
            onClick = { onTabSelected(3) },
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
