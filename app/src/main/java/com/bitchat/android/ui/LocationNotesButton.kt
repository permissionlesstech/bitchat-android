package com.bitchat.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager

/**
 * Location Notes button component for MainHeader
 * Shows in mesh mode when location permission granted AND services enabled
 * Icon turns primary color when notes exist, gray otherwise
 */
@Composable
fun LocationNotesButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    
    // Get channel and permission state
    val selectedLocationChannel by viewModel.selectedLocationChannel.observeAsState()
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val permissionState by locationManager.permissionState.observeAsState()
    val locationServicesEnabled by locationManager.locationServicesEnabled.observeAsState(false)

    // Check both permission AND location services enabled
    val locationPermissionGranted = permissionState == LocationChannelManager.PermissionState.AUTHORIZED
    val locationEnabled = locationPermissionGranted && locationServicesEnabled
    
    // Get notes count from LocationNotesManager
    val notesManager = remember { LocationNotesManager.getInstance() }
    val notes by notesManager.notes.observeAsState(emptyList())
    val notesCount = notes.size

    // Only show in mesh mode when location is authorized (iOS pattern)
    if (selectedLocationChannel is ChannelID.Mesh && locationEnabled) {
        val hasNotes = notesCount > 0
        IconButton(
            onClick = onClick,
            modifier = modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Description, // "long.text.page.and.pencil" equivalent
                contentDescription = stringResource(R.string.cd_location_notes),
                modifier = Modifier.size(16.dp),
                tint = if (hasNotes) colorScheme.primary else Color.Gray
            )
        }
    }
}
