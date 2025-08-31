package com.bitchat.android.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.nostr.NostrProofOfWork
import com.bitchat.android.nostr.PoWPreferenceManager

/**
 * Shows the current Proof of Work status and settings
 */
@Composable
fun PoWStatusIndicator(
    modifier: Modifier = Modifier,
    style: PoWIndicatorStyle = PoWIndicatorStyle.COMPACT
) {
    val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
    val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()
    val isMining by PoWPreferenceManager.isMining.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    
    if (!powEnabled) return
    
    when (style) {
        PoWIndicatorStyle.COMPACT -> {
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // PoW icon with animation if mining
                if (isMining) {
                    val rotation by rememberInfiniteTransition(label = "pow-rotation").animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "pow-icon-rotation"
                    )
                    
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = "Mining PoW",
                        tint = Color(0xFFFF9500), // Orange for mining
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer { rotationZ = rotation }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = "PoW Enabled",
                        tint = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D), // Green when ready
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        
        PoWIndicatorStyle.DETAILED -> {
            Surface(
                modifier = modifier,
                color = colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // PoW icon
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = "Proof of Work",
                        tint = if (isMining) Color(0xFFFF9500) else {
                            if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                        },
                        modifier = Modifier.size(14.dp)
                    )
                    
                    // Status text
                    Text(
                        text = if (isMining) {
                            "mining..."
                        } else {
                            "pow: ${powDifficulty}bit"
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isMining) Color(0xFFFF9500) else {
                            colorScheme.onSurface.copy(alpha = 0.7f)
                        }
                    )
                    
                    // Time estimate
                    if (!isMining && powDifficulty > 0) {
                        Text(
                            text = "(~${NostrProofOfWork.estimateMiningTime(powDifficulty)})",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Style options for the PoW status indicator
 */
enum class PoWIndicatorStyle {
    COMPACT,    // Small icon + difficulty number
    DETAILED    // Icon + status text + time estimate
}

/**
 * Shows mining progress with animated indicator
 */
@Composable
fun PoWMiningIndicator(
    modifier: Modifier = Modifier,
    difficulty: Int,
    iterations: Int? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Surface(
        modifier = modifier,
        color = Color(0xFFFF9500).copy(alpha = 0.1f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated security icon
            val rotation by rememberInfiniteTransition(label = "mining-rotation").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "mining-icon-rotation"
            )
            
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = "Mining Proof of Work",
                tint = Color(0xFFFF9500),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
            
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "mining proof of work...",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFF9500)
                )
                
                Text(
                    text = "difficulty: ${difficulty}bit (~${NostrProofOfWork.estimateMiningTime(difficulty)})" +
                            if (iterations != null) " • ${iterations} attempts" else "",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
