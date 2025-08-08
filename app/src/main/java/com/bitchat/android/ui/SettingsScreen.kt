package com.bitchat.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Full-screen settings screen for app preferences
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: SettingsManager.ThemePreference,
    onThemeChange: (SettingsManager.ThemePreference) -> Unit,
    onBackClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Header with back button
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colorScheme.background,
            shadowElevation = 4.dp
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background
                )
            )
        }
        
        // Settings content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Theme Section
            SettingsSection(
                title = "Appearance",
                icon = Icons.Default.Palette
            ) {
                ThemeSettings(
                    currentTheme = currentTheme,
                    onThemeChange = onThemeChange
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Future sections can be added here
            // Example:
            // SettingsSection(
            //     title = "Privacy",
            //     icon = Icons.Default.Security
            // ) {
            //     // Privacy settings content
            // }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ThemeSettings(
    currentTheme: SettingsManager.ThemePreference,
    onThemeChange: (SettingsManager.ThemePreference) -> Unit
) {
    val themeOptions = listOf(
        Triple(SettingsManager.ThemePreference.SYSTEM, Icons.Default.Smartphone, "System" to "Follow system theme"),
        Triple(SettingsManager.ThemePreference.LIGHT, Icons.Default.LightMode, "Light" to "Always use light theme"),
        Triple(SettingsManager.ThemePreference.DARK, Icons.Default.DarkMode, "Dark" to "Always use dark theme"),
        Triple(SettingsManager.ThemePreference.DYNAMIC, Icons.Default.Palette, "Dynamic" to "Material You colors (Android 12+)")
    )
    
    Column(
        modifier = Modifier.selectableGroup()
    ) {
        themeOptions.forEachIndexed { index, (theme, icon, labelDescription) ->
            val (label, description) = labelDescription
            
            // Staggered entrance animation for each option
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = tween(
                        durationMillis = 300,
                        delayMillis = index * 50,
                        easing = EaseOutCubic
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 300,
                        delayMillis = index * 50
                    )
                )
            ) {
                ThemeOption(
                    theme = theme,
                    icon = icon,
                    label = label,
                    description = description,
                    selected = currentTheme == theme,
                    onSelect = { onThemeChange(theme) }
                )
            }
            
            if (index < themeOptions.size - 1) {
                Spacer(modifier = Modifier.height(12.dp))
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
    
    // Animated background color
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) {
            colorScheme.primary.copy(alpha = 0.1f) // Use primary color with low alpha for consistency
        } else {
            Color.Transparent
        },
        animationSpec = tween(
            durationMillis = 200,
            easing = EaseInOutCubic
        ),
        label = "backgroundColorAnimation"
    )
    
    // Animated border color
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            colorScheme.primary.copy(alpha = 0.5f)
        } else {
            colorScheme.outline.copy(alpha = 0.2f)
        },
        animationSpec = tween(
            durationMillis = 200,
            easing = EaseInOutCubic
        ),
        label = "borderColorAnimation"
    )
    
    // Animated icon and text colors
    val contentColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurface,
        animationSpec = tween(
            durationMillis = 200,
            easing = EaseInOutCubic
        ),
        label = "contentColorAnimation"
    )
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() },
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = borderColor
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated icon with scale effect
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.1f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "iconScaleAnimation"
            )
            
            Icon(
                imageVector = icon,
                contentDescription = "$label theme option",
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                tint = contentColor
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            // Animated checkmark for selected state
            AnimatedVisibility(
                visible = selected,
                enter = scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    modifier = Modifier.size(20.dp),
                    tint = colorScheme.primary
                )
            }
        }
    }
}