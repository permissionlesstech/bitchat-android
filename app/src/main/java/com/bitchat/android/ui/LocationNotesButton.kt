package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Location Notes button component for MainHeader
 * Shows in mesh mode when location permission granted AND services enabled.
 * Base tint is primary when notes exist, secondary otherwise; Tor health may
 * override that color (orange connecting, red failed) via [torConnectionTint].
 */
@Composable
fun LocationNotesButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val context = LocalContext.current

    // Get channel and permission state
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val permissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val locationServicesEnabled by locationManager.effectiveLocationEnabled.collectAsStateWithLifecycle(false)

    // Check both permission AND location services enabled
    val locationPermissionGranted = permissionState == LocationChannelManager.PermissionState.AUTHORIZED
    val locationEnabled = locationPermissionGranted && locationServicesEnabled

    // Get notes count from LocationNotesManager
    val notesManager = remember { LocationNotesManager.getInstance() }
    val notes by notesManager.notes.collectAsStateWithLifecycle()
    val notesCount = notes.size

    // Only show in mesh mode when location is authorized (iOS pattern)
    if (selectedLocationChannel is ChannelID.Mesh && locationEnabled) {
        val hasNotes = notesCount > 0
        val contentDescription = stringResource(R.string.cd_location_notes)
        val normalTint = if (hasNotes) colorScheme.primary else palette.textSecondary
        val tint = torConnectionTint(normal = normalTint)
        // Match other header icon buttons: 44.dp target, no Material IconButton min-size padding
        // that pushed the notes glyph farther from the mesh badge than sibling gaps.
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = contentDescription) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = contentDescription,
                modifier = Modifier.size(HeaderIconSize),
                tint = tint
            )
        }
    }
}
