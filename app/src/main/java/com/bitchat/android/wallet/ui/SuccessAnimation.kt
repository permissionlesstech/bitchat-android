package com.bitchat.android.wallet.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.wallet.viewmodel.WalletViewModel
import kotlinx.coroutines.delay

/**
 * Fullscreen success animation component for wallet operations
 */
@Composable
fun SuccessAnimation(
    animationData: WalletViewModel.SuccessAnimationData,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(true) }
    
    // Auto-hide after animation duration
    LaunchedEffect(animationData) {
        delay(2500) // Show for 2.5 seconds
        isVisible = false
        delay(300) // Allow fade out animation to complete
        onAnimationComplete()
    }
    
    // Scale animation for the icon
    val infiniteTransition = rememberInfiniteTransition(label = "success_animation")
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_scale"
    )
    
    // Pulsing background effect
    val backgroundAlpha by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "background_alpha"
    )
    
    // Entry animation
    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.8f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "entry_scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "entry_alpha"
    )
    
    if (alpha > 0f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha * alpha)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.scale(scale)
            ) {
                // Success icon with pulsing circle background
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    // Pulsing background circle
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                Color(0xFF00C851).copy(alpha = 0.2f),
                                CircleShape
                            )
                    )
                    
                    // Inner circle
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                Color(0xFF00C851),
                                CircleShape
                            )
                            .scale(iconScale),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getIconForAnimationType(animationData.type),
                            contentDescription = "Success",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                
                // Success message
                Text(
                    text = "SUCCESS!",
                    color = Color(0xFF00C851),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                
                // Amount
                Text(
                    text = formatAmount(animationData.amount, animationData.unit),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                
                // Description
                Text(
                    text = animationData.description,
                    color = Color.Gray,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                
                // Animated checkmark or icon indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0..2) {
                        val dotAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 600),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = StartOffset(i * 200)
                            ),
                            label = "dot_$i"
                        )
                        
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    Color(0xFF00C851).copy(alpha = dotAlpha),
                                    CircleShape
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Get appropriate icon for animation type
 */
private fun getIconForAnimationType(type: WalletViewModel.SuccessAnimationType): ImageVector {
    return when (type) {
        WalletViewModel.SuccessAnimationType.CASHU_RECEIVED -> Icons.Filled.Download
        WalletViewModel.SuccessAnimationType.CASHU_SENT -> Icons.Filled.Upload
        WalletViewModel.SuccessAnimationType.LIGHTNING_RECEIVED -> Icons.Filled.FlashOn
        WalletViewModel.SuccessAnimationType.LIGHTNING_SENT -> Icons.AutoMirrored.Filled.Send
    }
}

/**
 * Format amount for display
 */
private fun formatAmount(amount: Long, unit: String): String {
    return when (unit.lowercase()) {
        "sat", "sats" -> {
            when {
                amount >= 100_000_000 -> String.format("%.2f BTC", amount / 100_000_000.0)
                amount >= 1000 -> String.format("%,d sats", amount)
                else -> "$amount sats"
            }
        }
        else -> "$amount $unit"
    }
} 