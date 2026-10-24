package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.feature.location.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The location notes destination. Collects [LocationNotesViewModel] and holds the effects
 * tied to the notes content being composed: one location refresh per entry (N10) and the
 * nearby-notes hold (N11), keyed as before on the geohash, the location toggle and the
 * permission.
 */
@Composable
fun LocationNotesRoot(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationNotesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            LocationNotesEvent.Close -> onBack()
        }
    }

    val notes = uiState.mode as? NotesMode.Notes
    if (notes != null) {
        // Collected here, with false until the first value arrives, as the notes content
        // did: the hold's first pass sees location off and the next one the real value.
        val locationEnabled by remember(viewModel) {
            viewModel.uiState.map { it.locationEnabled }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = false)

        LaunchedEffect(Unit) {
            viewModel.onAction(LocationNotesAction.NotesEntered)
        }

        // Opening the notes screen is an explicit reveal. The balanced hold lets
        // the mesh timeline keep the shared subscription alive after dismissal.
        DisposableEffect(
            notes.geohash,
            locationEnabled,
            uiState.locationAuthorized,
            viewModel,
        ) {
            viewModel.onAction(
                LocationNotesAction.ScreenShown(
                    geohash = notes.geohash,
                    locationEnabled = locationEnabled,
                    locationAuthorized = uiState.locationAuthorized,
                )
            )
            onDispose {
                viewModel.onAction(LocationNotesAction.ScreenHidden)
            }
        }
    }

    LocationNotesScreen(uiState = uiState, onAction = viewModel::onAction, modifier = modifier)
}

/**
 * Shown while location is being acquired
 */
@Composable
internal fun LocationNotesAcquiringContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.locationNotesScreenRoot()) {
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
internal fun LocationNotesErrorContent(
    onBack: () -> Unit,
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.locationNotesScreenRoot()) {
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
            Button(onClick = onEnableLocation) {
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
