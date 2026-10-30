package com.bitchat.android.ui

import android.content.Intent
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.feature.location.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.net.TorMode
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.BitchatTheme

/**
 * Location channels: grouped card rows matching About → Settings.
 *
 * A full-screen destination rather than a sheet, because it launches the
 * geohash picker for a result. A saved route re-registers that launcher after
 * process death, so a pick made while the app was killed still lands.
 *
 * The Root owns the picker launcher, the settings intent, the typed geohash and its error (plain
 * `remember`, so rotation clears them, Q-L1), and the lifecycle-bound holds: live location
 * refresh while resumed and the nearby-notes hold while shown, with the keys and call order they
 * always had (inventory B5). The ViewModel makes the calls.
 */
@Composable
fun LocationChannelsRoot(
    onClose: () -> Unit,
    onShowLocationNotes: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationChannelsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var customGeohash by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    val invalidGeohash = stringResource(R.string.invalid_geohash)

    val mapPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val gh = result.data?.getStringExtra(GeohashPickerActivity.EXTRA_RESULT_GEOHASH)
            if (!gh.isNullOrBlank()) {
                customError = null
                viewModel.onAction(LocationChannelsAction.CustomGeohashSubmitted(gh))
            }
        }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            LocationChannelsEvent.Close -> onClose()
            LocationChannelsEvent.ShowNotes -> onShowLocationNotes()
            LocationChannelsEvent.OpenAppSettings -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
            is LocationChannelsEvent.OpenMapPicker -> {
                val intent = Intent(context, GeohashPickerActivity::class.java).apply {
                    putExtra(GeohashPickerActivity.EXTRA_INITIAL_GEOHASH, event.initial)
                }
                mapPickerLauncher.launch(intent)
            }
            is LocationChannelsEvent.CustomGeohashRejected -> {
                customGeohash = event.fieldText
                customError = invalidGeohash
            }
        }
    }

    LocationChannelsScreen(
        uiState = uiState,
        customGeohash = customGeohash,
        customError = customError,
        onCustomGeohashChange = { value ->
            customGeohash = normalizeGeohashInput(value)
            customError = null
        },
        onAction = { action ->
            // A teleport clears the error; a rejection sets it again before the next frame.
            if (action is LocationChannelsAction.TeleportClicked) customError = null
            viewModel.onAction(action)
        },
        modifier = modifier,
    )

    LifecycleResumeEffect(uiState.appLocationEnabled, uiState.systemLocationEnabled) {
        viewModel.onAction(LocationChannelsAction.ScreenResumed)
        onPauseOrDispose {
            viewModel.onAction(LocationChannelsAction.ScreenPaused)
        }
    }

    DisposableEffect(
        uiState.buildingGeohash,
        uiState.effectiveLocationEnabled,
        uiState.permissionState,
        viewModel,
    ) {
        viewModel.onAction(LocationChannelsAction.NotesHoldShown)
        onDispose { viewModel.onAction(LocationChannelsAction.NotesHoldHidden) }
    }
}

/** The screen itself, drawn from [uiState] and the Root's field; every gesture goes out. */
@Composable
internal fun LocationChannelsScreen(
    uiState: LocationChannelsUiState,
    customGeohash: String,
    customError: String?,
    onCustomGeohashChange: (String) -> Unit,
    onAction: (LocationChannelsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    // Not read by the top bar; kept as it was.
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.98f else 0f,
        animationSpec = tween(BitchatMotion.EMPHASIZED_MS, easing = FastOutSlowInEasing),
        label = "topBarAlpha"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        // A sheet applied the status bar inset; a full-screen destination has to. The keyboard
        // inset stays with the list below, which consumes it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
        ) {
            LazyColumn(
                state = listState,
                // Edge-to-edge + adjustResize reports the keyboard as WindowInsets.ime —
                // without consuming it here the list stays full-height and the teleport
                // field can't scroll above the keyboard.
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item(key = "mesh_card") {
                    MeshCard(row = uiState.mesh, onAction = onAction)
                }

                item(key = "location_channels_header") {
                    LocationChannelsHeader()
                }

                uiState.teleported?.let { row ->
                    item(key = "teleported_card") {
                        TeleportedCard(row = row, onAction = onAction)
                    }
                }

                if (uiState.bookmarks.isNotEmpty()) {
                    item(key = "bookmarks_card") {
                        BookmarksCard(rows = uiState.bookmarks, onAction = onAction)
                    }
                }

                if (uiState.showPermissionDenied) {
                    item(key = "permissions") {
                        PermissionDeniedNotice(onAction = onAction)
                    }
                }

                item(key = "channels_card") {
                    NearbyChannelsCard(
                        nearby = uiState.nearby,
                        appLocationEnabled = uiState.appLocationEnabled,
                        effectiveLocationEnabled = uiState.effectiveLocationEnabled,
                        customGeohash = customGeohash,
                        customError = customError,
                        onCustomGeohashChange = onCustomGeohashChange,
                        onAction = onAction,
                    )
                }

                item(key = "location_notes_card") {
                    LocationNotesCard(subtitle = uiState.notesSubtitle, onAction = onAction)
                }

                item(key = "tor_routing") {
                    TorRoutingCard(tor = uiState.tor, onAction = onAction)
                }

                if (uiState.appLocationEnabled) {
                    item(key = "location_toggle") {
                        LocationToggleButton(onAction = onAction)
                    }
                }
            }

            BitchatScreenTopBar(
                onBack = { onAction(LocationChannelsAction.BackClicked) },
                modifier = Modifier.align(Alignment.TopCenter),
                title = {
                    BitchatSheetTitle(
                        text = stringResource(R.string.location_channels_title)
                    )
                }
            )
        }
    }
}

/** Location on: synthetic nearby rows, mesh selected. Open ocean at 0°,0°; never a real place. */
internal fun previewLocationChannelsUiState(): LocationChannelsUiState {
    val availableChannels = listOf(
        GeohashChannel(GeohashChannelLevel.CITY, "s0000"),
        GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "s00000"),
        GeohashChannel(GeohashChannelLevel.BLOCK, "s000000"),
        GeohashChannel(GeohashChannelLevel.BUILDING, "s0000000"),
    )
    return locationChannelsUiState(
        snapshot = previewSnapshot().copy(
            availableChannels = availableChannels,
            locationNames = mapOf(
                GeohashChannelLevel.CITY to "Synthetic City",
                GeohashChannelLevel.BLOCK to "Synthetic Block",
            ),
            participantCounts = mapOf("s0000" to 4, "s000000" to 1),
            notesRevealed = true,
            noteCount = 2,
            bookmarks = listOf("s0000"),
        ),
        torAvailable = true,
    )
}

/** Location off: the enable button, no nearby rows, Tor off. */
internal fun previewLocationOffUiState(): LocationChannelsUiState = locationChannelsUiState(
    snapshot = previewSnapshot().copy(
        appLocationEnabled = false,
        effectiveLocationEnabled = false,
        permissionState = PermissionState.DENIED,
        torMode = TorMode.OFF,
    ),
    torAvailable = true,
)

/** Teleported to a synthetic geohash that is also bookmarked, plus a second bookmark. */
internal fun previewTeleportedUiState(): LocationChannelsUiState {
    val teleported = GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "s00000")
    val bookmarks = listOf("s00000", "bbbbbbb")
    return locationChannelsUiState(
        snapshot = previewSnapshot().copy(
            selectedChannel = ChannelID.Location(teleported),
            bookmarks = bookmarks,
            bookmarkNames = mapOf("bbbbbbb" to "Synthetic Block"),
            participantCounts = mapOf("s00000" to 3),
        ),
        torAvailable = true,
    )
}

private fun previewSnapshot() = LocationChannelsSnapshot(
    permissionState = PermissionState.AUTHORIZED,
    availableChannels = emptyList(),
    selectedChannel = ChannelID.Mesh,
    locationNames = emptyMap(),
    appLocationEnabled = true,
    systemLocationEnabled = true,
    effectiveLocationEnabled = true,
    notesRevealed = false,
    noteCount = 0,
    notesState = LocationNotesManager.State.READY,
    bookmarks = emptyList(),
    bookmarkNames = emptyMap(),
    participantCounts = emptyMap(),
    wifiAwareEnabled = false,
    meshPeopleCount = 2,
    torMode = TorMode.ON,
)

@Composable
private fun LocationChannelsPreviewContent(uiState: LocationChannelsUiState) {
    BitchatTheme {
        LocationChannelsScreen(
            uiState = uiState,
            customGeohash = "",
            customError = null,
            onCustomGeohashChange = {},
            onAction = {},
        )
    }
}

@Preview(name = "Location on")
@Preview(name = "Location on (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LocationChannelsPreview() {
    LocationChannelsPreviewContent(previewLocationChannelsUiState())
}

@Preview(name = "Location off")
@Preview(name = "Location off (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LocationChannelsOffPreview() {
    LocationChannelsPreviewContent(previewLocationOffUiState())
}

@Preview(name = "Teleported")
@Preview(name = "Teleported (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun LocationChannelsTeleportedPreview() {
    LocationChannelsPreviewContent(previewTeleportedUiState())
}
