package com.bitchat.android.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R

/**
 * Screen shown when checking Bluetooth status or requesting Bluetooth enable
 */
@Composable
fun BluetoothCheckScreen(
    modifier: Modifier,
    status: BluetoothStatus,
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    isLoading: Boolean = false
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            BluetoothStatus.DISABLED -> {
                BluetoothDisabledContent(
                    onEnableBluetooth = onEnableBluetooth,
                    onRetry = onRetry,
                    colorScheme = colorScheme,
                    isLoading = isLoading
                )
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                BluetoothNotSupportedContent(
                    colorScheme = colorScheme
                )
            }
            BluetoothStatus.ENABLED -> {
                BluetoothCheckingContent(
                    colorScheme = colorScheme
                )
            }
        }
    }
}

@Composable
private fun BluetoothDisabledContent(
    onEnableBluetooth: () -> Unit,
    onRetry: () -> Unit,
    colorScheme: ColorScheme,
    isLoading: Boolean
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bluetooth icon - using Bluetooth outlined icon in app's green color
        Icon(
            imageVector = Icons.Outlined.Bluetooth,
            contentDescription = stringResource(R.string.cd_bluetooth),
            modifier = Modifier.size(64.dp),
            tint = Color(0xFF00C851) // App's main green color
        )

        Text(
            text = stringResource(R.string.bluetooth_required),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            ),
            textAlign = TextAlign.Center
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                    Text(
                        text = stringResource(R.string.bluetooth_needs_for),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                
                    Text(
                        text = stringResource(R.string.bluetooth_needs_bullets),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                )
            }
        }

        if (isLoading) {
            BluetoothLoadingIndicator()
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onEnableBluetooth,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C851) // App's main green color
                    )
                ) {
                        Text(
                            text = stringResource(R.string.enable_bluetooth),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                //Since we are automatically checking bluetooth state -- commented

//                OutlinedButton(
//                    onClick = onRetry,
//                    modifier = Modifier.fillMaxWidth()
//                ) {
//                    Text(
//                        text = "Check Again",
//                        style = MaterialTheme.typography.bodyMedium.copy(
//                            fontFamily = FontFamily.Monospace
//                        ),
//                        modifier = Modifier.padding(vertical = 4.dp)
//                    )
//                }
            }
        }
    }
}

@Composable
private fun BluetoothNotSupportedContent(
    colorScheme: ColorScheme
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Error icon
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFFEBEE)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.warning_emoji),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(16.dp)
            )
        }

        Text(
            text = stringResource(R.string.bluetooth_not_supported),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colorScheme.error
            ),
            textAlign = TextAlign.Center
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorScheme.errorContainer.copy(alpha = 0.1f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Text(
                text = stringResource(R.string.bluetooth_unsupported_explanation),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface
                ),
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BluetoothCheckingContent(
    colorScheme: ColorScheme
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary
            ),
            textAlign = TextAlign.Center
        )

        BluetoothLoadingIndicator()

        Text(
            text = stringResource(R.string.checking_bluetooth_status),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
        )
    }
}

@Composable
private fun BluetoothLoadingIndicator() {
    // Animated rotation for the loading indicator
    val infiniteTransition = rememberInfiniteTransition(label = "bluetooth_loading")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .fillMaxSize()
                .rotate(rotationAngle),
            color = Color(0xFF2196F3), // Bluetooth blue
            strokeWidth = 3.dp
        )
    }
}
