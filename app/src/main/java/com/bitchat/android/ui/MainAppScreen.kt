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
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    // Get wallet ViewModel scoped to this composable
    val walletViewModel: WalletViewModel = viewModel()
    
    Column(modifier = modifier.fillMaxSize()) {
        // Content based on selected tab
        when (selectedTab) {
            0 -> ChatScreen(viewModel = chatViewModel) // Existing chat screen
            1 -> WalletScreen(walletViewModel = walletViewModel) // New wallet screen
        }
        
        // Bottom Navigation
        AppBottomNavigation(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )
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
