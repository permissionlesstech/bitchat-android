package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager

/**
 * Presenter component for LocationNotesSheet
 * Handles sheet presentation logic with proper error states
 * Extracts this logic from ChatScreen for better separation of concerns
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationNotesSheetPresenter(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val availableChannels by locationManager.availableChannels.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    
    // iOS pattern: notesGeohash ?? LocationChannelManager.shared.availableChannels.first(where: { $0.level == .building })?.geohash
    val buildingGeohash = availableChannels.firstOrNull { it.level == GeohashChannelLevel.BUILDING }?.geohash
    
    if (buildingGeohash != null) {
        // Get location name from locationManager
        val locationNames by locationManager.locationNames.observeAsState(emptyMap())
        val locationName = locationNames[GeohashChannelLevel.BUILDING]
            ?: locationNames[GeohashChannelLevel.BLOCK]
        
        LocationNotesSheet(
            geohash = buildingGeohash,
            locationName = locationName,
            nickname = nickname,
            onDismiss = onDismiss
        )
    } else {
        // No building geohash available - show error state (matches iOS)
        LocationNotesErrorSheet(
            onDismiss = onDismiss,
            locationManager = locationManager
        )
    }
}

/**
 * Error sheet when location is unavailable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationNotesErrorSheet(
    onDismiss: () -> Unit,
    locationManager: LocationChannelManager
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Location Unavailable",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Location permission is required for notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                // UNIFIED FIX: Enable location services first (user toggle)
                locationManager.enableLocationServices()
                // Then request location channels (which will also request permission if needed)
                locationManager.enableLocationChannels()
                locationManager.refreshChannels()
            }) {
                Text("Enable Location")
            }
        }
    }
}
