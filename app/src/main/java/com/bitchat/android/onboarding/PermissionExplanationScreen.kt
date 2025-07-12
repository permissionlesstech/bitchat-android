package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R

/**
 * Permission explanation screen shown before requesting permissions
 * Explains why bitchat needs each permission and reassures users about privacy
 */
@Composable
fun PermissionExplanationScreen(
    permissionCategories: List<PermissionCategory>,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        // Header
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.permission_explanation_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                ),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.permission_explanation_subtitle),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                ),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy assurance section
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🔒",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.permission_explanation_box1_title),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )
                    )
                }
                
                Text(
                    text = stringResource(R.string.permission_explanation_box1_text),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.permission_explanation_box2_title),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
        )

        // Permission categories
        permissionCategories.forEach { category ->
            PermissionCategoryCard(
                category = category,
                colorScheme = colorScheme
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.permission_explanation_button_grant),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            ) {
                Text(
                    text = stringResource(R.string.permission_explanation_button_exit),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionCategoryCard(
    category: PermissionCategory,
    colorScheme: ColorScheme
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = category.emoji,
                    style = MaterialTheme.typography.titleLarge,
                    color = category.type.getPermissionIconColor(),
                    modifier = Modifier.size(24.dp)
                )
                
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                )
            }
            
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.8f),
                    lineHeight = 18.sp
                )
            )

            if (category.name == stringResource(R.string.permission_precise_location_title)) {
                // Extra emphasis for location permission
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⚠️",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.permission_precise_location_system_description_2),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFF9800)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCategoryType.getPermissionIconColor(): Color {
    return when (this) {
        PermissionCategoryType.NEARBY_DEVICES -> Color(0xFF2196F3) // Blue
        PermissionCategoryType.PRECISE_LOCATION -> Color(0xFFFF9800) // Orange
        PermissionCategoryType.NOTIFICATIONS -> Color(0xFF4CAF50) // Green
    }
}
