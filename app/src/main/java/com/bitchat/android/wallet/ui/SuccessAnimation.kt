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
import androidx.compose.ui.graphics.graphicsLayer
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
    var isVisible by remember { mutableStateOf(false) }
    var startExit by remember { mutableStateOf(false) }
    
    // Control animation timing
    LaunchedEffect(animationData) {
        // Start with fade in
        isVisible = true
        delay(2000) // Show for 2 seconds
        // Start exit animation
        startExit = true
        delay(500) // Allow fade out animation to complete
        onAnimationComplete()
    }
    
    // Wobble/tada animation for the icon
    val infiniteTransition = rememberInfiniteTransition(label = "success_animation")
    val iconRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isVisible && !startExit) 6f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_wobble"
    )
    
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isVisible && !startExit) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_scale"
    )
    
    // Smooth fade in/out animations
    val contentScale by animateFloatAsState(
        targetValue = if (isVisible && !startExit) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = 0.7f, 
            stiffness = Spring.StiffnessMedium
        ),
        label = "content_scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isVisible && !startExit) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (startExit) 400 else 600,
            easing = if (startExit) FastOutLinearInEasing else LinearOutSlowInEasing
        ),
        label = "content_alpha"
    )
    
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (isVisible && !startExit) 0.95f else 0f,
        animationSpec = tween(
            durationMillis = if (startExit) 400 else 600
        ),
        label = "background_alpha"
    )
    
    if (alpha > 0f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.scale(contentScale)
            ) {
                // Success icon with wobble/tada effect
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    // Outer glow circle
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                Color(0xFF00C851).copy(alpha = 0.2f),
                                CircleShape
                            )
                    )
                    
                    // Main success circle with wobble
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                Color(0xFF00C851),
                                CircleShape
                            )
                            .scale(iconScale)
                            .graphicsLayer {
                                rotationZ = iconRotation
                            },
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
                
                // Success message with smooth fade
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                ) {
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
                }
            }
        }
    }
}

/**
 * Fullscreen failure animation component for wallet operations
 */
@Composable
fun FailureAnimation(
    errorMessage: String,
    operationType: String = "Operation",
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    var startExit by remember { mutableStateOf(false) }
    
    // Control animation timing
    LaunchedEffect(errorMessage) {
        // Start with fade in
        isVisible = true
        delay(3000) // Show for 3 seconds (longer for error message)
        // Start exit animation
        startExit = true
        delay(500) // Allow fade out animation to complete
        onAnimationComplete()
    }
    
    // Shake animation for the error icon
    val infiniteTransition = rememberInfiniteTransition(label = "failure_animation")
    val iconShake by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isVisible && !startExit) 3f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_shake"
    )
    
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isVisible && !startExit) 1.03f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_scale"
    )
    
    // Smooth fade in/out animations
    val contentScale by animateFloatAsState(
        targetValue = if (isVisible && !startExit) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = 0.7f, 
            stiffness = Spring.StiffnessMedium
        ),
        label = "content_scale"
    )
    
    val alpha by animateFloatAsState(
        targetValue = if (isVisible && !startExit) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (startExit) 400 else 600,
            easing = if (startExit) FastOutLinearInEasing else LinearOutSlowInEasing
        ),
        label = "content_alpha"
    )
    
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (isVisible && !startExit) 0.95f else 0f,
        animationSpec = tween(
            durationMillis = if (startExit) 400 else 600
        ),
        label = "background_alpha"
    )
    
    if (alpha > 0f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.scale(contentScale)
            ) {
                // Error icon with shake effect
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    // Outer glow circle (red)
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(
                                Color(0xFFFF4444).copy(alpha = 0.2f),
                                CircleShape
                            )
                    )
                    
                    // Main error circle with shake
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                Color(0xFFFF4444),
                                CircleShape
                            )
                            .scale(iconScale)
                            .graphicsLayer {
                                translationX = iconShake
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Error",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                
                // Error message with smooth fade
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.graphicsLayer { this.alpha = alpha }
                ) {
                    Text(
                        text = "FAILED",
                        color = Color(0xFFFF4444),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                    
                    // Operation type
                    Text(
                        text = "$operationType Failed",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    // Error message
                    Text(
                        text = errorMessage,
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
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
                amount >= 100_000_000 -> String.format("%.8f BTC", amount / 100_000_000.0)
                amount >= 1000 -> String.format("%,d ₿", amount)
                else -> "$amount ₿"
            }
        }
        else -> "$amount $unit"
    }
} 