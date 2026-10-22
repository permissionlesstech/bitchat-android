package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.feature.location.R

/**
 * The location notes screen: the notes once the building is known, and
 * otherwise an acquiring or an unavailable state.
 *
 * The three states swap inside one destination rather than each being one of
 * their own, and each is a full screen with a back arrow and its own insets.
 */
@Composable
fun LocationNotesScreen(
    nickname: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val availableChannels by locationManager.availableChannels.collectAsStateWithLifecycle()
    val permissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val isLoadingLocation by locationManager.isLoadingLocation.collectAsStateWithLifecycle()

    // iOS pattern: notesGeohash ?? LocationChannelManager.shared.availableChannels.first(where: { $0.level == .building })?.geohash
    val buildingGeohash = availableChannels.firstOrNull { it.level == GeohashChannelLevel.BUILDING }?.geohash

    if (buildingGeohash != null) {
        // Get location name from locationManager
        val locationNames by locationManager.locationNames.collectAsStateWithLifecycle()
        val locationName = locationNames[GeohashChannelLevel.BUILDING]
            ?: locationNames[GeohashChannelLevel.BLOCK]

        LocationNotesContent(
            geohash = buildingGeohash,
            locationName = locationName,
            nickname = nickname,
            onBack = onBack
        )
    } else if (permissionState == LocationChannelManager.PermissionState.AUTHORIZED && isLoadingLocation) {
        LocationNotesAcquiringContent(onBack = onBack)
    } else {
        // No building geohash available - show error state (matches iOS)
        LocationNotesErrorContent(
            onBack = onBack,
            locationManager = locationManager
        )
    }
}

/**
 * Shown while location is being acquired
 */
@Composable
private fun LocationNotesAcquiringContent(onBack: () -> Unit) {
    Box(modifier = Modifier.locationNotesScreenRoot()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Acquiring Location",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Please wait while your location is being determined",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LocationNotesTopBar(onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/**
 * Shown when location is unavailable
 */
@Composable
private fun LocationNotesErrorContent(
    onBack: () -> Unit,
    locationManager: LocationChannelManager
) {
    Box(modifier = Modifier.locationNotesScreenRoot()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 80.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
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

        LocationNotesTopBar(onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
    }
}

/** Top bar of the unavailable and acquiring states, which have no count to title. */
@Composable
private fun LocationNotesTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BitchatScreenTopBar(
        onBack = onBack,
        modifier = modifier,
        title = {
            BitchatSheetTitle(text = stringResource(R.string.cd_location_notes).uppercase())
        }
    )
}

/**
 * Root of each notes state. A sheet applied the system bar insets to everything
 * in it; a screen has to, and consuming them here keeps the top bar from
 * padding a second time.
 */
@Composable
internal fun Modifier.locationNotesScreenRoot(): Modifier =
    this.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
