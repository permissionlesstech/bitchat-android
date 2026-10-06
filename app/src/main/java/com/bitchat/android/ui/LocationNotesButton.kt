package com.bitchat.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager

/**
 * Location Notes button for MainHeader - the entry point for reading and writing notes here.
 * Mesh-only with location authorized. Tor health tints the glyph with muted colours
 * and a slow glow while connecting (via [rememberTorConnectionVisual]).
 */
@Composable
fun LocationNotesButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val permissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val locationServicesEnabled by locationManager.effectiveLocationEnabled.collectAsStateWithLifecycle(false)

    val locationPermissionGranted = permissionState == LocationChannelManager.PermissionState.AUTHORIZED
    val locationEnabled = locationPermissionGranted && locationServicesEnabled

    // Shown whenever notes are usable here, not only once notes exist. Gating on a non-empty list
    // kept the header tidy but left writing the first note with no affordance at all - the only
    // way in was a row below the fold of the channels sheet.
    if (selectedLocationChannel is ChannelID.Mesh && locationEnabled) {
        val contentDescription = stringResource(R.string.cd_location_notes)
        val torVisual = rememberTorConnectionVisual(normal = colorScheme.primary)

        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .pressScaleClickable(onClick = onClick, onClickLabel = contentDescription),
            contentAlignment = Alignment.Center
        ) {
            TorAwareHeaderIcon(
                painter = painterResource(R.drawable.ic_spec_chat_bubbles),
                tint = torVisual.tint,
                isProgress = torVisual.isProgress,
                contentDescription = contentDescription
            )
        }
    }
}
