package com.bitchat.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
 * Main app screen with wallet access via header button only
 */
@Composable
fun MainAppScreen(
    chatViewModel: ChatViewModel,
    onBackPress: (backHandler: () -> Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showWallet by remember { mutableStateOf(false) }
    
    // Get wallet ViewModel scoped to this composable
    val walletViewModel: WalletViewModel = viewModel()
    
    // Handle back navigation
    fun handleBackPress(): Boolean {
        return when {
            showWallet -> {
                // Wallet is showing - let WalletViewModel handle it first
                walletViewModel.handleBackPress().takeIf { it } ?: run {
                    // If wallet doesn't handle it, close wallet
                    showWallet = false
                    true
                }
            }
            else -> {
                // Chat is showing - let ChatViewModel handle it
                chatViewModel.handleBackPressed()
            }
        }
    }
    
    // Expose back handler to MainActivity
    LaunchedEffect(Unit) {
        onBackPress { handleBackPress() }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // Chat is always the base view
        ChatScreen(
            viewModel = chatViewModel,
            walletViewModel = walletViewModel,
            onWalletClick = { 
                // Close the keyboard if it's open
                chatViewModel.hideKeyboard()
                showWallet = true 
            },
            onWalletClickWithToken = { parsedToken ->
                // Close the keyboard if it's open
                chatViewModel.hideKeyboard()
                // Open wallet and show receive dialog with the parsed token immediately
                showWallet = true
                walletViewModel.openReceiveDialogWithParsedToken(parsedToken)
            }
        )
        
        // Wallet slides in from right when needed
        AnimatedVisibility(
            visible = showWallet,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing
                )
            ) + fadeIn(
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing
                )
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing
                )
            ) + fadeOut(
                animationSpec = tween(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing
                )
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            WalletScreen(
                walletViewModel = walletViewModel,
                onBackToChat = { showWallet = false }
            )
        }
    }
}
