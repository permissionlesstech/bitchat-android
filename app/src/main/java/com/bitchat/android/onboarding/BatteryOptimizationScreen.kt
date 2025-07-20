package com.bitchat.android.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/**
 * Screen shown when checking battery optimization status or requesting battery optimization disable
 */



@Composable
fun BatteryOptimizationScreen(
    status: BatteryOptimizationStatus,
    onDisableBatteryOptimization: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    isLoading: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            BatteryOptimizationStatus.ENABLED -> {
                BatteryOptimizationEnabledContent(
                    onDisableBatteryOptimization = onDisableBatteryOptimization,
                    onRetry = onRetry,
                    onSkip = onSkip,
                    colorScheme = colorScheme,
                    isLoading = isLoading
                )
            }
            
            BatteryOptimizationStatus.DISABLED -> {
                BatteryOptimizationCheckingContent(
                    colorScheme = colorScheme
                )
            }
            
            BatteryOptimizationStatus.NOT_SUPPORTED -> {
                BatteryOptimizationNotSupportedContent(
                    onRetry = onRetry,
                    colorScheme = colorScheme
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationEnabledContent(
    onDisableBatteryOptimization: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    colorScheme: ColorScheme,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Scrollable content area
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "bitchat*",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Icon(
                imageVector = Icons.Outlined.BatteryAlert,
                contentDescription = "Battery Optimization",
                modifier = Modifier.size(64.dp),
                tint = colorScheme.error
            )
            
            Text(
                text = stringResource(R.string.battery_optimization_detected),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                ),
                textAlign = TextAlign.Center
            )
            
            Text(
                text = stringResource(R.string.battery_optimization_explanation),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.battery_optimization_why_disable),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface
                        )
                    )
                    
                    Text(
                        text = stringResource(R.string.battery_optimization_benefits),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            
            Text(
                text = stringResource(R.string.battery_optimization_note),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
        }
        
        // Fixed buttons at the bottom
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onDisableBatteryOptimization,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.battery_optimization_disable_button))
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(stringResource(R.string.battery_optimization_check_again))
                }
                
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(stringResource(R.string.battery_optimization_skip))
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationCheckingContent(
    colorScheme: ColorScheme
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "bitchat*",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )
        )
        
        val infiniteTransition = rememberInfiniteTransition(label = "rotation")
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
        
        Icon(
            imageVector = Icons.Filled.BatteryStd,
            contentDescription = "Checking Battery Optimization",
            modifier = Modifier
                .size(64.dp)
                .rotate(rotation),
            tint = colorScheme.primary
        )
        
        Text(
            text = stringResource(R.string.battery_optimization_disabled),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            ),
            textAlign = TextAlign.Center
        )
        
        Text(
            text = stringResource(R.string.battery_optimization_success_message),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = colorScheme.onSurfaceVariant
            ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BatteryOptimizationNotSupportedContent(
    onRetry: () -> Unit,
    colorScheme: ColorScheme
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "bitchat*",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            )
        )
        
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Battery Optimization Not Supported",
            modifier = Modifier.size(64.dp),
            tint = colorScheme.primary
        )
        
        Text(
            text = stringResource(R.string.battery_optimization_not_required),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            ),
            textAlign = TextAlign.Center
        )
        
        Text(
            text = stringResource(R.string.battery_optimization_not_supported_message),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = colorScheme.onSurfaceVariant
            ),
            textAlign = TextAlign.Center
        )
        
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.battery_optimization_continue))
        }
    }
}