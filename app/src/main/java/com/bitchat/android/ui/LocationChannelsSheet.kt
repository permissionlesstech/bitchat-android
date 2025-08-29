package com.bitchat.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import kotlinx.coroutines.launch
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import java.util.*

/**
 * Location Channels Sheet for selecting geohash-based location channels
 * Direct port from iOS LocationChannelsSheet for 100% compatibility
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationChannelsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationManager = LocationChannelManager.getInstance(context)
    
    // Observe location manager state
    val permissionState by locationManager.permissionState.observeAsState()
    val availableChannels by locationManager.availableChannels.observeAsState(emptyList())
    val selectedChannel by locationManager.selectedChannel.observeAsState()
    val teleported by locationManager.teleported.observeAsState(false)
    val locationNames by locationManager.locationNames.observeAsState(emptyMap())
    val locationServicesEnabled by locationManager.locationServicesEnabled.observeAsState(false)
    
    // CRITICAL FIX: Observe reactive participant counts for real-time updates
    val geohashParticipantCounts by viewModel.geohashParticipantCounts.observeAsState(emptyMap())
    
    // UI state
    var customGeohash by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    var isInputFocused by remember { mutableStateOf(false) }
    
    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = isInputFocused
    )
    val coroutineScope = rememberCoroutineScope()
    
    // Scroll state for LazyColumn
    val listState = rememberLazyListState()
    
    // iOS system colors (matches iOS exactly)
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val standardGreen = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D) // iOS green
    val standardBlue = Color(0xFF007AFF) // iOS blue
    
    if (isPresented) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isInputFocused) {
                            Modifier.fillMaxHeight().padding(horizontal = 16.dp, vertical = 24.dp)
                        } else {
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Text(
                    text = "#location channels",
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = "chat with people near you using geohash channels. only a coarse geohash is shared, never exact gps. do not screenshot or share this screen to protect your privacy.",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                // Location Services Control - Show permission handling if enabled
                if (locationServicesEnabled) {
                    when (permissionState) {
                        LocationChannelManager.PermissionState.NOT_DETERMINED -> {
                            Button(
                                onClick = { locationManager.enableLocationChannels() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = standardGreen.copy(alpha = 0.12f),
                                    contentColor = standardGreen
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "grant location permission",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        
                        LocationChannelManager.PermissionState.DENIED,
                        LocationChannelManager.PermissionState.RESTRICTED -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "location permission denied. enable in settings to use location channels.",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Red.copy(alpha = 0.8f)
                                )
                                
                                TextButton(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    }
                                ) {
                                    Text(
                                        text = "open settings",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        
                        LocationChannelManager.PermissionState.AUTHORIZED -> {
                            Text(
                                text = "✓ location permission granted",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = standardGreen
                            )
                        }
                        
                        null -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp))
                                Text(
                                    text = "checking permissions...",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                
                // Channel list (iOS-style plain list)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f)
                ) {
                    // Mesh option first
                    item {
                        ChannelRow(
                            title = meshTitleWithCount(viewModel),
                            subtitle = "#bluetooth • ${bluetoothRangeString()}",
                            isSelected = selectedChannel is ChannelID.Mesh,
                            titleColor = standardBlue,
                            titleBold = meshCount(viewModel) > 0,
                            onClick = {
                                locationManager.select(ChannelID.Mesh)
                                onDismiss()
                            }
                        )
                    }
                    
                    // Nearby options (only show if location services are enabled)
                    if (availableChannels.isNotEmpty() && locationServicesEnabled) {
                        items(availableChannels) { channel ->
                            val coverage = coverageString(channel.geohash.length)
                            val nameBase = locationNames[channel.level]
                            val namePart = nameBase?.let { formattedNamePrefix(channel.level) + it }
                            val subtitlePrefix = "#${channel.geohash} • $coverage"
                            // CRITICAL FIX: Use reactive participant count from LiveData
                            val participantCount = geohashParticipantCounts[channel.geohash] ?: 0
                            val highlight = participantCount > 0
                            
                            ChannelRow(
                                title = geohashTitleWithCount(channel, participantCount),
                                subtitle = subtitlePrefix + (namePart?.let { " • $it" } ?: ""),
                                isSelected = isChannelSelected(channel, selectedChannel),
                                titleColor = standardGreen,
                                titleBold = highlight,
                                onClick = {
                                    // Selecting a suggested nearby channel is not a teleport
                                    locationManager.setTeleported(false)
                                    locationManager.select(ChannelID.Location(channel))
                                    onDismiss()
                                }
                            )
                        }
                    } else if (permissionState == LocationChannelManager.PermissionState.AUTHORIZED && locationServicesEnabled) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text(
                                    text = "finding nearby channels…",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    
                    // Custom geohash teleport (iOS-style inline form)
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            color = Color.Transparent
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#",
                                        fontSize = BASE_FONT_SIZE.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    
                                    BasicTextField(
                                        value = customGeohash,
                                        onValueChange = { newValue ->
                                            // iOS-style geohash validation (base32 characters only)
                                            val allowed = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()
                                            val filtered = newValue
                                                .lowercase()
                                                .replace("#", "")
                                                .filter { it in allowed }
                                                .take(12)
                                            
                                            customGeohash = filtered
                                            customError = null
                                        },
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = BASE_FONT_SIZE.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .onFocusChanged { focusState ->
                                                isInputFocused = focusState.isFocused
                                                if (focusState.isFocused) {
                                                    coroutineScope.launch {
                                                        sheetState.expand()
                                                        // Scroll to bottom to show input and remove button
                                                        listState.animateScrollToItem(
                                                            index = listState.layoutInfo.totalItemsCount - 1
                                                        )
                                                    }
                                                }
                                            },
                                        singleLine = true,
                                        decorationBox = { innerTextField ->
                                            if (customGeohash.isEmpty()) {
                                                Text(
                                                    text = "geohash",
                                                    fontSize = BASE_FONT_SIZE.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                    
                                    val normalized = customGeohash.trim().lowercase().replace("#", "")
                                    val isValid = validateGeohash(normalized)
                                    
                                    // iOS-style teleport button
                                    Button(
                                        onClick = {
                                            if (isValid) {
                                                val level = levelForLength(normalized.length)
                                                val channel = GeohashChannel(level = level, geohash = normalized)
                                                // Mark this selection as a manual teleport
                                                locationManager.setTeleported(true)
                                                locationManager.select(ChannelID.Location(channel))
                                                onDismiss()
                                            } else {
                                                customError = "invalid geohash"
                                            }
                                        },
                                        enabled = isValid,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                            contentColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "teleport",
                                                fontSize = BASE_FONT_SIZE.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            // iOS has a face.dashed icon, use closest Material equivalent
                                            Icon(
                                                imageVector = Icons.Filled.PinDrop,
                                                contentDescription = "Teleport",
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                                
                                customError?.let { error ->
                                    Text(
                                        text = error,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Red
                                    )
                                }
                            }
                        }
                    }
                    
                    // Location services toggle button
                    item {
                        Button(
                            onClick = {
                                if (locationServicesEnabled) {
                                    locationManager.disableLocationServices()
                                } else {
                                    locationManager.enableLocationServices()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (locationServicesEnabled) {
                                    Color.Red.copy(alpha = 0.08f)
                                } else {
                                    standardGreen.copy(alpha = 0.12f)
                                },
                                contentColor = if (locationServicesEnabled) {
                                    Color(0xFFBF1A1A)
                                } else {
                                    standardGreen
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (locationServicesEnabled) {
                                    "disable location services"
                                } else {
                                    "enable location services"
                                },
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Lifecycle management
    LaunchedEffect(isPresented) {
        if (isPresented) {
            // Refresh channels when opening (only if location services are enabled)
            if (permissionState == LocationChannelManager.PermissionState.AUTHORIZED && locationServicesEnabled) {
                locationManager.refreshChannels()
            }
            // Begin periodic refresh while sheet is open (only if location services are enabled)
            if (locationServicesEnabled) {
                locationManager.beginLiveRefresh()
            }
            
            // Begin multi-channel sampling for counts
            val geohashes = availableChannels.map { it.geohash }
            viewModel.beginGeohashSampling(geohashes)
        } else {
            locationManager.endLiveRefresh()
            viewModel.endGeohashSampling()
        }
    }
    
    // React to permission changes
    LaunchedEffect(permissionState) {
        if (permissionState == LocationChannelManager.PermissionState.AUTHORIZED && locationServicesEnabled) {
            locationManager.refreshChannels()
        }
    }
    
    // React to location services enable/disable
    LaunchedEffect(locationServicesEnabled) {
        if (locationServicesEnabled && permissionState == LocationChannelManager.PermissionState.AUTHORIZED) {
            locationManager.refreshChannels()
        }
    }
    
    // React to available channels changes
    LaunchedEffect(availableChannels) {
        val geohashes = availableChannels.map { it.geohash }
        viewModel.beginGeohashSampling(geohashes)
    }
}

@Composable
private fun ChannelRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    titleColor: Color? = null,
    titleBold: Boolean = false,
    onClick: () -> Unit
) {
    // iOS-style list row (plain button, no card background)
    Surface(
        onClick = onClick,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Split title to handle count part with smaller font (iOS style)
                val (baseTitle, countSuffix) = splitTitleAndCount(title)
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = baseTitle,
                        fontSize = BASE_FONT_SIZE.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (titleBold) FontWeight.Bold else FontWeight.Normal,
                        color = titleColor ?: MaterialTheme.colorScheme.onSurface
                    )
                    
                    countSuffix?.let { count ->
                        Text(
                            text = count,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            if (isSelected) {
                Text(
                    text = "✔︎",
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF32D74B) // iOS green for checkmark
                )
            }
        }
    }
}

// MARK: - Helper Functions (matching iOS implementation)

private fun splitTitleAndCount(title: String): Pair<String, String?> {
    val lastBracketIndex = title.lastIndexOf('[')
    return if (lastBracketIndex != -1) {
        val prefix = title.substring(0, lastBracketIndex).trim()
        val suffix = title.substring(lastBracketIndex)
        Pair(prefix, suffix)
    } else {
        Pair(title, null)
    }
}

private fun meshTitleWithCount(viewModel: ChatViewModel): String {
    val meshCount = meshCount(viewModel)
    val noun = if (meshCount == 1) "person" else "people"
    return "mesh [$meshCount $noun]"
}

private fun meshCount(viewModel: ChatViewModel): Int {
    val myID = viewModel.meshService.myPeerID
    return viewModel.connectedPeers.value?.count { peerID ->
        peerID != myID
    } ?: 0
}

private fun geohashTitleWithCount(channel: GeohashChannel, participantCount: Int): String {
    val noun = if (participantCount == 1) "person" else "people"
    return "${channel.level.displayName.lowercase()} [$participantCount $noun]"
}

private fun isChannelSelected(channel: GeohashChannel, selectedChannel: ChannelID?): Boolean {
    return when (selectedChannel) {
        is ChannelID.Location -> selectedChannel.channel == channel
        else -> false
    }
}

private fun validateGeohash(geohash: String): Boolean {
    if (geohash.isEmpty() || geohash.length > 12) return false
    val allowed = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()
    return geohash.all { it in allowed }
}

private fun levelForLength(length: Int): GeohashChannelLevel {
    return when (length) {
        in 0..2 -> GeohashChannelLevel.REGION
        in 3..4 -> GeohashChannelLevel.PROVINCE
        5 -> GeohashChannelLevel.CITY
        6 -> GeohashChannelLevel.NEIGHBORHOOD
        7 -> GeohashChannelLevel.BLOCK
        else -> GeohashChannelLevel.BLOCK
    }
}

private fun coverageString(precision: Int): String {
    // Approximate max cell dimension at equator for a given geohash length
    val maxMeters = when (precision) {
        2 -> 1_250_000.0
        3 -> 156_000.0
        4 -> 39_100.0
        5 -> 4_890.0
        6 -> 1_220.0
        7 -> 153.0
        8 -> 38.2
        9 -> 4.77
        10 -> 1.19
        else -> if (precision <= 1) 5_000_000.0 else 1.19 * Math.pow(0.25, (precision - 10).toDouble())
    }
    
    // Use metric system for simplicity (could be made locale-aware)
    val km = maxMeters / 1000.0
    return "~${formatDistance(km)} km"
}

private fun formatDistance(value: Double): String {
    return when {
        value >= 100 -> String.format("%.0f", value)
        value >= 10 -> String.format("%.1f", value)
        else -> String.format("%.1f", value)
    }
}

private fun bluetoothRangeString(): String {
    // Approximate Bluetooth LE range for typical mobile devices
    return "~10–50 m"
}

private fun formattedNamePrefix(level: GeohashChannelLevel): String {
//    return when (level) {
//        GeohashChannelLevel.REGION -> ""
//        else -> "~"
//    }
    return "~"
}
