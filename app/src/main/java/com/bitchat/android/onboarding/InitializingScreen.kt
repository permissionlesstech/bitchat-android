package com.bitchat.android.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.ui.theme.BitchatTheme

/**
 * Loading screen shown during app initialization after permissions are granted
 */
@Composable
fun InitializingScreen() {
    val colorScheme = MaterialTheme.colorScheme
    
    // Animated rotation for the loading indicator
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Animated dots for loading text
    val dotCount = 3
    val animationDelay = 300
    val dots = (0 until dotCount).map { index ->
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDelay * dotCount),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(animationDelay * index)
            ),
            label = "dot_$index"
        )
        alpha
    }

    Scaffold(
        content = { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp, alignment = Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App title
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    ),
                    textAlign = TextAlign.Center
                )

                // Loading indicator
                Box(
                    modifier = Modifier.size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(rotationAngle),
                        color = colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                }

                // Loading text with animated dots
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.initializing_mesh_network),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    )

                    // Animated dots
                    dots.forEach { alpha ->
                        Text(
                            text = ".",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurface.copy(alpha = alpha)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status message
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.setting_up_bluetooth_mesh),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurface.copy(alpha = 0.8f)
                            ),
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = stringResource(R.string.setup_duration),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurface.copy(alpha = 0.6f)
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    )
}

/**
 * Error screen shown if initialization fails
 */
@Composable
fun InitializationErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error indicator
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFEBEE)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Text(
                    text = "⚠️",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Text(
                text = stringResource(R.string.setup_not_complete),
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
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.try_again),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.open_settings),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}


@Preview
@Composable
private fun InitializingScreenWhitePrew() {
    BitchatTheme(false) {
        InitializingScreen()
    }
}

@Preview
@Composable
private fun InitializingScreenDarkPrew() {
    BitchatTheme(true) {
        InitializingScreen()
    }
}