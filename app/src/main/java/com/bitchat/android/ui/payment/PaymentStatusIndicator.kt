package com.bitchat.android.ui.payment

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Payment status for Cashu token creation
 */
sealed class PaymentStatus {
    data class Creating(val amount: Long) : PaymentStatus()
    data class Success(val amount: Long, val token: String) : PaymentStatus()
    data class Error(val amount: Long, val message: String) : PaymentStatus()
}

/**
 * Sleek payment status indicator that slides up above the text input
 * Shows creation progress, success, and error states
 */
@Composable
fun PaymentStatusIndicator(
    status: PaymentStatus?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = status != null
    
    // Auto-dismiss success/error states after delay
    LaunchedEffect(status) {
        if (status is PaymentStatus.Success || status is PaymentStatus.Error) {
            delay(3000) // Show for 3 seconds
            onDismiss()
        }
    }
    
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = 0.8f,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(
            animationSpec = tween(300, easing = EaseOutCubic)
        ),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut(
            animationSpec = tween(200, easing = EaseInCubic)
        ),
        modifier = modifier
    ) {
        if (status != null) {
            PaymentStatusCard(status = status)
        }
    }
}

@Composable
private fun PaymentStatusCard(status: PaymentStatus) {
    val colors = when (status) {
        is PaymentStatus.Creating -> PaymentColors(
            background = Color(0xFF1A1A1A),
            border = Color(0xFF00C851),
            icon = Color(0xFF00C851),
            text = Color.White
        )
        is PaymentStatus.Success -> PaymentColors(
            background = Color(0xFF00C851).copy(alpha = 0.15f),
            border = Color(0xFF00C851),
            icon = Color(0xFF00C851),
            text = Color.White
        )
        is PaymentStatus.Error -> PaymentColors(
            background = Color(0xFFFF4444).copy(alpha = 0.15f),
            border = Color(0xFFFF4444),
            icon = Color(0xFFFF4444),
            text = Color.White
        )
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.background
        ),
        border = androidx.compose.foundation.BorderStroke(2.dp, colors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status icon with animation
            PaymentStatusIcon(
                status = status,
                color = colors.icon
            )
            
            // Status content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                PaymentStatusText(
                    status = status,
                    textColor = colors.text
                )
            }
            
            // Amount display
            PaymentAmountDisplay(
                amount = when (status) {
                    is PaymentStatus.Creating -> status.amount
                    is PaymentStatus.Success -> status.amount
                    is PaymentStatus.Error -> status.amount
                },
                color = colors.text
            )
        }
    }
}

@Composable
private fun PaymentStatusIcon(
    status: PaymentStatus,
    color: Color
) {
    when (status) {
        is PaymentStatus.Creating -> {
            // Animated spinner
            val infiniteTransition = rememberInfiniteTransition(label = "payment_creating")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "spinner_rotation"
            )
            
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Creating payment",
                tint = color,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
        is PaymentStatus.Success -> {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Payment sent",
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        is PaymentStatus.Error -> {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "Payment failed",
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PaymentStatusText(
    status: PaymentStatus,
    textColor: Color
) {
    when (status) {
        is PaymentStatus.Creating -> {
            Text(
                text = "Creating payment...",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Generating Cashu token",
                color = textColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        is PaymentStatus.Success -> {
            Text(
                text = "Payment sent!",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Cashu token delivered to chat",
                color = textColor.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        is PaymentStatus.Error -> {
            Text(
                text = "Payment failed",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = status.message,
                color = textColor.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PaymentAmountDisplay(
    amount: Long,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.End
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "⚡",
                fontSize = 16.sp,
                color = color
            )
            Text(
                text = formatSats(amount),
                color = color,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Colors for different payment states
 */
private data class PaymentColors(
    val background: Color,
    val border: Color,
    val icon: Color,
    val text: Color
)

/**
 * Format sats amount for display
 */
private fun formatSats(sats: Long): String {
    return when {
        sats >= 100_000_000 -> String.format("%.8f BTC", sats / 100_000_000.0)
        sats >= 1000 -> String.format("%,d ₿", sats)
        else -> "$sats ₿"
    }
} 