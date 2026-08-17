package com.bitchat.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Public
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.nostr.geohashesForSampling
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.wifiaware.WifiAwareController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Leading column width matching settings rows: 22.dp glyph + 16.dp gutter before title text.
 * Selection dots and row icons sit in this column so every option lines up with About settings.
 */
private val ChannelLeadingSlot = SheetRowLeadingSlot
private val ChannelLeadingGutter = SheetRowLeadingGutter
private val ChannelRowHorizontal = SheetRowHorizontal
private val ChannelRowVertical = SheetRowVertical
private val ChannelDividerInset = SheetRowDividerInset
/** 2× the previous 6.dp selected indicator; sits centered in [ChannelLeadingSlot]. */
private val ChannelSelectedDot = SheetRowSelectedDot

/**
 * Pause between applying a channel selection and dismissing the sheet.
 *
 * Just enough for the active dot to land on the chosen row, so the tap is acknowledged rather than
 * answered by the sheet simply disappearing.
 */
private const val SelectionConfirmDelayMs = 180L

/**
 * Location Channels sheet: grouped card rows matching About → Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationChannelsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onLocationNotesClick: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationManager = LocationChannelManager.getInstance(context)
    val bookmarksStore = remember { GeohashBookmarksStore.getInstance(context) }
    val nearbyNotesController = remember { NearbyNotesController.shared }
    val notesManager = remember { LocationNotesManager.getInstance() }

    val permissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val availableChannels by locationManager.availableChannels.collectAsStateWithLifecycle()
    val notesRevealed by nearbyNotesController.revealed.collectAsStateWithLifecycle()
    val nearbyNotes by notesManager.notes.collectAsStateWithLifecycle()
    val nearbyNotesState by notesManager.state.collectAsStateWithLifecycle()
    val selectedChannel by locationManager.selectedChannel.collectAsStateWithLifecycle()
    val locationNames by locationManager.locationNames.collectAsStateWithLifecycle()
    val appLocationEnabled by locationManager.locationServicesEnabled.collectAsStateWithLifecycle()
    val systemLocationEnabled by locationManager.systemLocationEnabled.collectAsStateWithLifecycle()
    val locationServicesEnabled by locationManager.effectiveLocationEnabled.collectAsStateWithLifecycle()

    val bookmarks by bookmarksStore.bookmarks.collectAsStateWithLifecycle()
    val bookmarkNames by bookmarksStore.bookmarkNames.collectAsStateWithLifecycle()
    val geohashParticipantCounts by viewModel.geohashParticipantCounts.collectAsStateWithLifecycle()
    val wifiAwareEnabled by WifiAwareController.enabled.collectAsStateWithLifecycle()

    var customGeohash by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    val teleportToGeohash: (String) -> Unit = { value ->
        val channel = channelForManualGeohash(value)
        if (channel != null) {
            customError = null
            locationManager.selectManual(channel)
            onDismiss()
        } else {
            customGeohash = value.trim().lowercase().replace("#", "")
            customError = context.getString(R.string.invalid_geohash)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.98f else 0f,
        animationSpec = tween(BitchatMotion.EMPHASIZED_MS, easing = FastOutSlowInEasing),
        label = "topBarAlpha"
    )

    val mapPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val gh = result.data?.getStringExtra(GeohashPickerActivity.EXTRA_RESULT_GEOHASH)
            if (!gh.isNullOrBlank()) {
                teleportToGeohash(gh)
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val standardGreen = colorScheme.primary
    val standardBlue = colorScheme.secondary

    val nearbyChannels = remember(availableChannels) {
        availableChannels.filter { it.level != GeohashChannelLevel.BUILDING }
    }
    val selectedChannelOutsideNearby = remember(selectedChannel, nearbyChannels) {
        selectedLocationChannelOutsideNearby(selectedChannel, nearbyChannels)
    }
    val showNearbyLoading = nearbyChannels.isEmpty() &&
        permissionState == LocationChannelManager.PermissionState.AUTHORIZED &&
        locationServicesEnabled
    val locationNotesSubtitle = when {
        !notesRevealed -> stringResource(R.string.nearby_notes_reveal)
        nearbyNotesState == LocationNotesManager.State.NO_RELAYS ->
            stringResource(R.string.location_notes_relays_unavailable)
        nearbyNotesState == LocationNotesManager.State.LOADING ->
            stringResource(R.string.loading_location_notes)
        nearbyNotes.size == 1 -> stringResource(R.string.nearby_notes_one)
        nearbyNotes.size > 1 ->
            stringResource(R.string.nearby_notes_many, nearbyNotes.size)
        else -> stringResource(R.string.location_notes_empty_title)
    }

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            // Selection is applied immediately so the active dot snaps to the new row, then the
            // sheet slides away after a beat. Long enough to register the change, short enough
            // that it never feels like waiting.
            val animatedDismiss = LocalSheetDismiss.current
            val confirmSelectionThenDismiss: () -> Unit = {
                coroutineScope.launch {
                    delay(SelectionConfirmDelayMs)
                    animatedDismiss?.invoke() ?: onDismiss()
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
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
                    // Mesh section: icon + title header, offline subtitle, then selection card
                    item(key = "mesh_card") {
                        Column {
                            SheetIconSectionHeader(
                                iconRes = R.drawable.ic_spec_range,
                                title = stringResource(R.string.mesh_title),
                                subtitle = stringResource(R.string.mesh_section_subtitle),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding)
                                    .padding(top = 10.dp),
                                color = colorScheme.surface,
                                shape = AboutCardShape
                            ) {
                                ChannelOptionRow(
                                    title = meshTitleWithCount(viewModel),
                                    subtitle = stringResource(
                                        if (wifiAwareEnabled) {
                                            R.string.location_bluetooth_wifi_subtitle
                                        } else {
                                            R.string.location_bluetooth_subtitle
                                        },
                                        meshRangeString()
                                    ),
                                    isSelected = selectedChannel is ChannelID.Mesh,
                                    participantCount = meshCount(viewModel),
                                    titleColor = standardBlue,
                                    titleBold = meshCount(viewModel) > 0,
                                    onClick = {
                                        locationManager.select(ChannelID.Mesh)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    item(key = "location_channels_header") {
                        SheetIconSectionHeader(
                            iconRes = R.drawable.ic_spec_globe,
                            title = stringResource(R.string.location_channels_heading),
                            subtitle = stringResource(R.string.location_channels_desc),
                            modifier = Modifier.padding(top = 20.dp)
                        )
                    }

                    selectedChannelOutsideNearby?.let { channel ->
                        item(key = "teleported_card") {
                            val coverage = coverageString(channel.geohash.length)
                            val name = bookmarkNames[channel.geohash]
                            val subtitle = "#${channel.geohash} • $coverage" +
                                (name?.let { " • ${formattedNamePrefix(channel.level)}$it" } ?: "")
                            val participantCount = geohashParticipantCounts[channel.geohash] ?: 0
                            val isBookmarked = bookmarksStore.isBookmarked(channel.geohash)

                            Column {
                                AboutSectionLabel(text = stringResource(R.string.cd_teleported))
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
                                ) {
                                    ChannelOptionRow(
                                        title = geohashHashTitleWithCount(
                                            channel.geohash,
                                            participantCount
                                        ),
                                        subtitle = subtitle,
                                        isSelected = true,
                                        participantCount = participantCount,
                                        titleColor = standardGreen,
                                        titleBold = participantCount > 0,
                                        trailingContent = {
                                            ChannelBookmarkButton(
                                                bookmarked = isBookmarked,
                                                onClick = {
                                                    bookmarksStore.toggle(channel.geohash)
                                                }
                                            )
                                        },
                                        onClick = {
                                            locationManager.select(ChannelID.Location(channel))
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (bookmarks.isNotEmpty()) {
                        item(key = "bookmarks_card") {
                            Column {
                                AboutSectionLabel(text = stringResource(R.string.bookmarked))
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
                                ) {
                                    Column {
                                        bookmarks.forEachIndexed { index, gh ->
                                            if (index > 0) SheetCardDivider()
                                            val level = levelForLength(gh.length)
                                            val channel = GeohashChannel(level = level, geohash = gh)
                                            val coverage = coverageString(gh.length)
                                            val name = bookmarkNames[gh]
                                            val subtitle = "#$gh • $coverage" +
                                                (name?.let { " • ${formattedNamePrefix(level)}$it" } ?: "")
                                            val participantCount = geohashParticipantCounts[gh] ?: 0

                                            ChannelOptionRow(
                                                title = geohashHashTitleWithCount(gh, participantCount),
                                                subtitle = subtitle,
                                                isSelected = isChannelSelected(channel, selectedChannel),
                                                participantCount = participantCount,
                                                titleBold = participantCount > 0,
                                                trailingContent = {
                                                    ChannelBookmarkButton(
                                                        bookmarked = true,
                                                        onClick = { bookmarksStore.toggle(gh) }
                                                    )
                                                },
                                                onClick = {
                                                    val inRegional =
                                                        availableChannels.any { it.geohash == gh }
                                                    locationManager.selectManual(
                                                        channel = channel,
                                                        teleported = !appLocationEnabled ||
                                                            availableChannels.isEmpty() ||
                                                            !inRegional
                                                    )
                                                    onDismiss()
                                                }
                                            )
                                            LaunchedEffect(gh) {
                                                bookmarksStore.resolveNameIfNeeded(gh)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (locationServicesEnabled &&
                        permissionState == LocationChannelManager.PermissionState.DENIED
                    ) {
                        item(key = "permissions") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding)
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.location_permission_denied),
                                    fontSize = 12.sp,
                                    fontFamily = BitchatFontFamily,
                                    color = colorScheme.error
                                )
                                TextButton(
                                    onClick = {
                                        val intent =
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts(
                                                    "package",
                                                    context.packageName,
                                                    null
                                                )
                                            }
                                        context.startActivity(intent)
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.open_settings),
                                        fontSize = 12.sp,
                                        fontFamily = BitchatFontFamily
                                    )
                                }
                            }
                        }
                    }

                    // Nearby location channels and the control for teleporting somewhere new.
                    item(key = "channels_card") {
                        Column {
                            AboutSectionLabel(
                                text = stringResource(R.string.location_channels_nearby)
                            )
                            if (!appLocationEnabled) {
                                SheetDestructiveButton(
                                    text = stringResource(R.string.enable_location_services),
                                    isDestructive = false,
                                    onClick = { locationManager.enableLocationServices() },
                                    modifier = Modifier.padding(
                                        start = AboutHorizontalPadding,
                                        end = AboutHorizontalPadding,
                                        bottom = 10.dp
                                    )
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding),
                                color = colorScheme.surface,
                                shape = AboutCardShape
                            ) {
                                Column {
                                    if (locationServicesEnabled) {
                                        if (nearbyChannels.isNotEmpty()) {
                                            nearbyChannels.forEachIndexed { index, channel ->
                                                if (index > 0) SheetCardDivider()
                                                val coverage = coverageString(channel.geohash.length)
                                                val nameBase = locationNames[channel.level]
                                                val namePart = nameBase?.let { formattedNamePrefix(channel.level) + it }
                                                val subtitlePrefix = "#${channel.geohash} • $coverage"
                                                val participantCount = geohashParticipantCounts[channel.geohash] ?: 0
                                                val isBookmarked = bookmarksStore.isBookmarked(channel.geohash)

                                                ChannelOptionRow(
                                                    title = geohashTitleWithCount(channel, participantCount),
                                                    subtitle = subtitlePrefix + (namePart?.let { " • $it" } ?: ""),
                                                    isSelected = isChannelSelected(channel, selectedChannel),
                                                    participantCount = participantCount,
                                                    titleColor = standardGreen,
                                                    titleBold = participantCount > 0,
                                                    trailingContent = {
                                                        ChannelBookmarkButton(
                                                            bookmarked = isBookmarked,
                                                            onClick = { bookmarksStore.toggle(channel.geohash) }
                                                        )
                                                    },
                                                    onClick = {
                                                        if (locationManager.selectNearby(channel)) {
                                                            onDismiss()
                                                        }
                                                    }
                                                )
                                            }
                                            SheetCardDivider()
                                        } else if (showNearbyLoading) {
                                            ChannelLoadingRow()
                                            SheetCardDivider()
                                        }
                                    }

                                    CustomGeohashRow(
                                        customGeohash = customGeohash,
                                        onGeohashChange = { value ->
                                            val allowed = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()
                                            customGeohash = value
                                                .lowercase()
                                                .replace("#", "")
                                                .filter { it in allowed }
                                                .take(12)
                                            customError = null
                                        },
                                        onFocusGained = {
                                            coroutineScope.launch { sheetState.expand() }
                                        },
                                        onOpenMap = {
                                            val normalized = customGeohash.trim().lowercase().replace("#", "")
                                            val initial = when {
                                                normalized.isNotBlank() -> normalized
                                                selectedChannel is ChannelID.Location ->
                                                    (selectedChannel as ChannelID.Location).channel.geohash
                                                else -> ""
                                            }
                                            val intent = Intent(context, GeohashPickerActivity::class.java).apply {
                                                putExtra(GeohashPickerActivity.EXTRA_INITIAL_GEOHASH, initial)
                                            }
                                            mapPickerLauncher.launch(intent)
                                        },
                                        onTeleport = {
                                            teleportToGeohash(customGeohash)
                                        }
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = customError != null,
                                enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
                                    expandVertically(
                                        tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
                                    ),
                                exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
                                    shrinkVertically(
                                        tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing)
                                    )
                            ) {
                                // Held across the exit animation: by the time it plays, the error
                                // itself has already been cleared.
                                val shownError = remember(customError) { customError ?: "" }
                                Text(
                                    text = shownError,
                                    fontSize = 12.sp,
                                    fontFamily = BitchatFontFamily,
                                    color = colorScheme.error,
                                    modifier = Modifier.padding(
                                        start = AboutHorizontalPadding + ChannelRowHorizontal,
                                        top = 8.dp
                                    )
                                )
                            }
                        }
                    }

                    item(key = "location_notes_card") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AboutHorizontalPadding)
                                .padding(top = 10.dp),
                            color = colorScheme.surface,
                            shape = AboutCardShape
                        ) {
                            ChannelOptionRow(
                                title = stringResource(R.string.cd_location_notes),
                                subtitle = locationNotesSubtitle,
                                isSelected = false,
                                participantCount = 0,
                                titleColor = standardGreen,
                                leadingIconRes = R.drawable.ic_spec_chat_bubbles,
                                trailingContent = {
                                    Icon(
                                        imageVector = Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    locationManager.refreshChannels()
                                    nearbyNotesController.reveal()
                                    coroutineScope.launch {
                                        runCatching { sheetState.hide() }
                                        onDismiss()
                                        onLocationNotesClick()
                                    }
                                }
                            )
                        }
                    }

                    item(key = "tor_routing") {
                        val torProvider = remember { ArtiTorManager.getInstance() }
                        val torAvailable = remember { torProvider.isTorAvailable() }
                        var torMode by remember { mutableStateOf(TorPreferenceManager.get(context)) }

                        Column {
                            AboutSectionLabel(text = stringResource(R.string.about_network))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AboutHorizontalPadding),
                                color = colorScheme.surface,
                                shape = AboutCardShape
                            ) {
                                ChannelSettingsToggleRow(
                                    icon = Icons.Filled.Security,
                                    title = stringResource(R.string.location_tor_routing_title),
                                    subtitle = stringResource(R.string.location_tor_routing_desc),
                                    checked = torMode == TorMode.ON,
                                    enabled = torAvailable,
                                    statusIndicator = {
                                        BitchatBadge(text = stringResource(R.string.badge_recommended))
                                    },
                                    onCheckedChange = { enabled ->
                                        if (torAvailable) {
                                            torMode = if (enabled) TorMode.ON else TorMode.OFF
                                            TorPreferenceManager.set(context, torMode)
                                        }
                                    }
                                )
                            }
                            if (!torAvailable) {
                                Text(
                                    text = stringResource(R.string.tor_not_available_in_this_build),
                                    fontSize = 12.sp,
                                    fontFamily = BitchatFontFamily,
                                    color = palette.textTertiary,
                                    modifier = Modifier.padding(
                                        start = AboutHorizontalPadding + ChannelRowHorizontal,
                                        top = 8.dp
                                    )
                                )
                            }
                        }
                    }

                    if (appLocationEnabled) {
                        item(key = "location_toggle") {
                            SheetDestructiveButton(
                                text = stringResource(R.string.disable_location_services),
                                isDestructive = true,
                                onClick = { locationManager.disableLocationServices() },
                                modifier = Modifier.padding(
                                    start = AboutHorizontalPadding,
                                    end = AboutHorizontalPadding,
                                    top = 24.dp
                                )
                            )
                        }
                    }
                }

                BitchatSheetTopBar(
                    onClose = onDismiss,
                    modifier = modifier.align(Alignment.TopCenter),
                    title = {
                        BitchatSheetTitle(
                            text = stringResource(R.string.location_channels_title)
                        )
                    }
                )
            }
        }
    }

    LifecycleResumeEffect(isPresented, appLocationEnabled, systemLocationEnabled) {
        if (isPresented) {
            val currentPermission = locationManager.syncPermissionState()
            if (appLocationEnabled &&
                systemLocationEnabled &&
                currentPermission == LocationChannelManager.PermissionState.AUTHORIZED
            ) {
                // Retain the redesign branch's immediate refresh when the sheet resumes while
                // honoring main's independent app/system privacy gates.
                locationManager.enableLocationChannels()
                locationManager.beginLiveRefresh()
            }
        }

        onPauseOrDispose {
            locationManager.endLiveRefresh()
        }
    }

    // Sampling management: update sampling when channels/bookmarks change
    LaunchedEffect(
        isPresented,
        availableChannels,
        bookmarks,
        appLocationEnabled,
        notesRevealed
    ) {
        if (isPresented) {
            val liveLocationGeohashes = geohashesForSampling(
                availableChannels = availableChannels,
                bookmarks = emptyList(),
                notesRevealed = notesRevealed,
            )
            viewModel.beginGeohashSampling(
                liveLocationGeohashes = liveLocationGeohashes,
                userSelectedGeohashes = bookmarks
            )
        } else {
            viewModel.endGeohashSampling()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.endGeohashSampling() }
    }
}

/**
 * Single channel option — settings-row geometry: 22.dp leading slot, title + subtitle, trailing.
 * Selected state is a 12.dp green dot centered in the leading slot (icon-sized footprint).
 */
@Composable
private fun ChannelOptionRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    participantCount: Int,
    titleColor: Color? = null,
    titleBold: Boolean = false,
    leadingIcon: ImageVector? = null,
    leadingIconRes: Int? = null,
    trailingContent: (@Composable (() -> Unit))? = null,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val (baseTitle, countSuffix) = splitTitleAndCount(title)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(ChannelLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            when {
                isSelected -> {
                    Box(
                        modifier = Modifier
                            .size(ChannelSelectedDot)
                            .background(colorScheme.primary, CircleShape)
                    )
                }
                leadingIcon != null -> {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                leadingIconRes != null -> {
                    Icon(
                        painter = painterResource(leadingIconRes),
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(ChannelLeadingGutter))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = baseTitle,
                    fontSize = 14.sp,
                    fontFamily = BitchatFontFamily,
                    fontWeight = if (titleBold) FontWeight.SemiBold else FontWeight.Medium,
                    color = titleColor ?: colorScheme.onSurface
                )
                countSuffix?.let { count ->
                    AnimatedCountLabel(
                        count = participantCount,
                        text = count,
                        fontSize = 11.sp,
                        fontFamily = BitchatFontFamily,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = subtitle,
                fontSize = 12.sp,
                fontFamily = BitchatFontFamily,
                lineHeight = 17.sp,
                color = colorScheme.onSurfaceVariant
            )
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

@Composable
private fun ChannelBookmarkButton(
    bookmarked: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(
                if (bookmarked) {
                    R.drawable.ic_spec_bookmark_filled
                } else {
                    R.drawable.ic_spec_bookmark_outline
                }
            ),
            contentDescription = stringResource(
                if (bookmarked) R.string.cd_remove_bookmark else R.string.cd_add_bookmark
            ),
            tint = if (bookmarked) colorScheme.primary else colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ChannelLoadingRow() {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        horizontalArrangement = Arrangement.spacedBy(ChannelLeadingGutter),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(ChannelLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.finding_nearby_channels),
            fontSize = 12.sp,
            fontFamily = BitchatFontFamily,
            color = colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Teleport / custom geohash control unified into the same card as channel options.
 */
@Composable
private fun CustomGeohashRow(
    customGeohash: String,
    onGeohashChange: (String) -> Unit,
    onFocusGained: () -> Unit,
    onOpenMap: () -> Unit,
    onTeleport: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val normalized = customGeohash.trim().lowercase().replace("#", "")
    val isValid = validateGeohash(normalized)
    // Typing the last character of a valid geohash arms the button; cross-fading both the label
    // and its container makes that the moment the row confirms the input is usable.
    val teleportColor by animateColorAsState(
        targetValue = if (isValid) colorScheme.primary else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "teleportLabel"
    )
    val teleportContainer by animateColorAsState(
        targetValue = if (isValid) {
            colorScheme.primary.copy(alpha = 0.16f)
        } else {
            colorScheme.surfaceVariant
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "teleportContainer"
    )
    val teleportInteraction = remember { MutableInteractionSource() }
    val teleportScale = rememberPressScale(teleportInteraction, pressedScale = 0.92f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(ChannelLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PinDrop,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(ChannelLeadingGutter))

        Text(
            text = stringResource(R.string.hash_symbol),
            fontSize = 14.sp,
            fontFamily = BitchatFontFamily,
            color = palette.textTertiary
        )

        Spacer(modifier = Modifier.width(4.dp))

        BasicTextField(
            value = customGeohash,
            onValueChange = onGeohashChange,
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontFamily = BitchatFontFamily,
                color = colorScheme.primary
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) return@onFocusChanged
                    onFocusGained()
                    // Wait until IME insets have landed so the LazyColumn has shrunk; bringIntoView
                    // against the full-height viewport leaves the field tucked under the keyboard.
                    // Re-request once the IME animation settles — early insets are still growing.
                    coroutineScope.launch {
                        withTimeoutOrNull(750) {
                            snapshotFlow { imeInsets.getBottom(density) }
                                .first { it > 0 }
                        }
                        bringIntoViewRequester.bringIntoView()
                        delay(BitchatMotion.STANDARD_MS.toLong())
                        bringIntoViewRequester.bringIntoView()
                    }
                },
            decorationBox = { inner ->
                if (customGeohash.isEmpty()) {
                    Text(
                        text = stringResource(R.string.geohash_placeholder),
                        fontSize = 14.sp,
                        fontFamily = BitchatFontFamily,
                        color = palette.textTertiary
                    )
                }
                inner()
            }
        )

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .pressScaleClickable(onClick = onOpenMap),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Map,
                contentDescription = stringResource(R.string.cd_open_map),
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Surface(
            onClick = onTeleport,
            enabled = isValid,
            shape = RoundedCornerShape(8.dp),
            color = teleportContainer,
            interactionSource = teleportInteraction,
            modifier = Modifier.scale(teleportScale)
        ) {
            Text(
                text = stringResource(R.string.teleport).uppercase(),
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = BitchatFontFamily,
                color = teleportColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

/** Settings-toggle row geometry (icon + title/subtitle + switch), local copy for this sheet. */
@Composable
private fun ChannelSettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    statusIndicator: (@Composable () -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChannelRowHorizontal, vertical = ChannelRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colorScheme.primary else palette.textTertiary,
            modifier = Modifier.size(ChannelLeadingSlot)
        )
        Spacer(modifier = Modifier.width(ChannelLeadingGutter))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) colorScheme.onSurface else palette.textTertiary
                )
                statusIndicator?.invoke()
            }
            Text(
                text = subtitle,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (enabled) colorScheme.onSurfaceVariant else palette.textTertiary
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

// MARK: - Helper Functions

/** Separates the channel name from the animated people-count label in composed titles. */
private const val TITLE_COUNT_SEP = '\u001F'

private fun splitTitleAndCount(title: String): Pair<String, String?> {
    val sep = title.indexOf(TITLE_COUNT_SEP)
    if (sep != -1) {
        return Pair(title.substring(0, sep), title.substring(sep + 1).ifEmpty { null })
    }
    // Legacy "[n people]" form
    val lastBracketIndex = title.lastIndexOf('[')
    return if (lastBracketIndex != -1) {
        val count = title.substring(lastBracketIndex + 1).removeSuffix("]")
        Pair(title.substring(0, lastBracketIndex).trim(), count.ifEmpty { null })
    } else {
        Pair(title, null)
    }
}

@Composable
private fun meshTitleWithCount(viewModel: ChatViewModel): String {
    val meshCount = meshCount(viewModel)
    val ctx = LocalContext.current
    val peopleText = ctx.resources.getQuantityString(R.plurals.people_count, meshCount, meshCount)
    val meshLabel = stringResource(R.string.mesh_title)
    return "$meshLabel$TITLE_COUNT_SEP$peopleText"
}

private fun meshCount(viewModel: ChatViewModel): Int {
    val myID = viewModel.myPeerID
    return viewModel.connectedPeers.value?.count { it != myID } ?: 0
}

@Composable
private fun geohashTitleWithCount(channel: GeohashChannel, participantCount: Int): String {
    val ctx = LocalContext.current
    val isHighPrecision = channel.level.precision > 5
    val peopleText = if (isHighPrecision && participantCount == 0) {
        ctx.resources.getQuantityString(R.plurals.people_count, 0, 0).replace("0", "?")
    } else {
        ctx.resources.getQuantityString(R.plurals.people_count, participantCount, participantCount)
    }
    val levelName = when (channel.level) {
        GeohashChannelLevel.BUILDING -> "Building"
        GeohashChannelLevel.BLOCK -> stringResource(R.string.location_level_block)
        GeohashChannelLevel.NEIGHBORHOOD -> stringResource(R.string.location_level_neighborhood)
        GeohashChannelLevel.CITY -> stringResource(R.string.location_level_city)
        GeohashChannelLevel.PROVINCE -> stringResource(R.string.location_level_province)
        GeohashChannelLevel.REGION -> stringResource(R.string.location_level_region)
    }
    return "$levelName$TITLE_COUNT_SEP$peopleText"
}

@Composable
private fun geohashHashTitleWithCount(geohash: String, participantCount: Int): String {
    val ctx = LocalContext.current
    val level = levelForLength(geohash.length)
    val isHighPrecision = level.precision > 5
    val peopleText = if (isHighPrecision && participantCount == 0) {
        ctx.resources.getQuantityString(R.plurals.people_count, 0, 0).replace("0", "?")
    } else {
        ctx.resources.getQuantityString(R.plurals.people_count, participantCount, participantCount)
    }
    return "#$geohash$TITLE_COUNT_SEP$peopleText"
}

private fun isChannelSelected(channel: GeohashChannel, selectedChannel: ChannelID?): Boolean {
    return when (selectedChannel) {
        is ChannelID.Location -> selectedChannel.channel == channel
        else -> false
    }
}

/**
 * Returns the active location channel when it has no row in the nearby-channel list.
 *
 * This commonly happens after teleporting to a remote geohash. Keeping the selected channel in the
 * main channel card makes its selection and bookmark action available even before it is bookmarked.
 */
internal fun selectedLocationChannelOutsideNearby(
    selectedChannel: ChannelID?,
    nearbyChannels: List<GeohashChannel>
): GeohashChannel? {
    val selected = (selectedChannel as? ChannelID.Location)?.channel ?: return null
    return selected.takeUnless { active ->
        nearbyChannels.any { nearby ->
            nearby.geohash.equals(active.geohash, ignoreCase = true)
        }
    }
}

private fun validateGeohash(geohash: String): Boolean {
    if (geohash.isEmpty() || geohash.length > 12) return false
    val allowed = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()
    return geohash.all { it in allowed }
}

internal fun channelForManualGeohash(value: String): GeohashChannel? {
    val normalized = value.trim().lowercase().replace("#", "")
    if (!validateGeohash(normalized)) return null
    return GeohashChannel(
        level = levelForLength(normalized.length),
        geohash = normalized
    )
}

private fun levelForLength(length: Int): GeohashChannelLevel {
    return when (length) {
        in 0..2 -> GeohashChannelLevel.REGION
        in 3..4 -> GeohashChannelLevel.PROVINCE
        5 -> GeohashChannelLevel.CITY
        6 -> GeohashChannelLevel.NEIGHBORHOOD
        7 -> GeohashChannelLevel.BLOCK
        8 -> GeohashChannelLevel.BUILDING
        else -> if (length > 8) GeohashChannelLevel.BUILDING else GeohashChannelLevel.BLOCK
    }
}

private fun coverageString(precision: Int): String {
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
    return "~${formatDistance(maxMeters / 1000.0)} km"
}

private fun formatDistance(value: Double): String {
    return when {
        value >= 100 -> String.format("%.0f", value)
        value >= 10 -> String.format("%.1f", value)
        else -> String.format("%.1f", value)
    }
}

private fun meshRangeString(): String = "~10–50m"

private fun formattedNamePrefix(level: GeohashChannelLevel): String = "~"
