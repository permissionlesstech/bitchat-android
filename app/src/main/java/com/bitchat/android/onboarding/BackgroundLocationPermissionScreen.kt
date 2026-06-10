package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R

/**
 * Explanation screen shown before requesting background location permission.
 */
@Composable
fun BackgroundLocationPermissionScreen(
    modifier: Modifier,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 88.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            HeaderSection(colorScheme)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = stringResource(R.string.cd_location_services),
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.background_location_required_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.background_location_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.background_location_settings_tip),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = stringResource(R.string.cd_privacy_protected),
                            tint = colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.background_location_needs_for),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.background_location_needs_bullets),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.background_location_privacy_note),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = colorScheme.onBackground
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.grant_background_location),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.check_again),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }

                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.battery_optimization_skip),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(colorScheme: ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            ),
            color = colorScheme.onBackground
        )

        Text(
            text = stringResource(R.string.background_location_required_subtitle),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}
