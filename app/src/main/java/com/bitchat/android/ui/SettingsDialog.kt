package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Settings dialog for app preferences
 */
@Composable
fun SettingsDialog(
    show: Boolean,
    currentTheme: SettingsManager.ThemePreference,
    onThemeChange: (SettingsManager.ThemePreference) -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        Dialog(onDismissRequest = onDismiss) {
            SettingsDialogContent(
                currentTheme = currentTheme,
                onThemeChange = onThemeChange,
                onDismiss = onDismiss
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialogContent(
    currentTheme: SettingsManager.ThemePreference,
    onThemeChange: (SettingsManager.ThemePreference) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colorScheme.primary
                )
                
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close settings",
                        tint = colorScheme.onSurface
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Theme Section
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Column(
                modifier = Modifier.selectableGroup()
            ) {
                ThemeOption(
                    theme = SettingsManager.ThemePreference.SYSTEM,
                    icon = Icons.Default.Smartphone,
                    label = "System",
                    description = "Follow system theme",
                    selected = currentTheme == SettingsManager.ThemePreference.SYSTEM,
                    onSelect = { onThemeChange(SettingsManager.ThemePreference.SYSTEM) }
                )
                
                ThemeOption(
                    theme = SettingsManager.ThemePreference.LIGHT,
                    icon = Icons.Default.LightMode,
                    label = "Light",
                    description = "Always use light theme",
                    selected = currentTheme == SettingsManager.ThemePreference.LIGHT,
                    onSelect = { onThemeChange(SettingsManager.ThemePreference.LIGHT) }
                )
                
                ThemeOption(
                    theme = SettingsManager.ThemePreference.DARK,
                    icon = Icons.Default.DarkMode,
                    label = "Dark",
                    description = "Always use dark theme",
                    selected = currentTheme == SettingsManager.ThemePreference.DARK,
                    onSelect = { onThemeChange(SettingsManager.ThemePreference.DARK) }
                )
                
                ThemeOption(
                    theme = SettingsManager.ThemePreference.DYNAMIC,
                    icon = Icons.Default.Palette,
                    label = "Dynamic",
                    description = "Material You colors (Android 12+)",
                    selected = currentTheme == SettingsManager.ThemePreference.DYNAMIC,
                    onSelect = { onThemeChange(SettingsManager.ThemePreference.DYNAMIC) }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss
                ) {
                    Text(
                        text = "Done",
                        color = colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOption(
    theme: SettingsManager.ThemePreference,
    icon: ImageVector,
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null, // handled by selectable modifier
            colors = RadioButtonDefaults.colors(
                selectedColor = colorScheme.primary
            )
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Icon(
            imageVector = icon,
            contentDescription = "$label theme option",
            modifier = Modifier.size(20.dp),
            tint = if (selected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) colorScheme.primary else colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}