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
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.feature.location.R
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
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
 * Location channels: grouped card rows matching About → Settings.
 *
 * A full-screen destination rather than a sheet, because it launches the
 * geohash picker for a result. A saved route re-registers that launcher after
 * process death, so a pick made while the app was killed still lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationChannelsScreen(
    onClose: () -> Unit,
    onShowLocationNotes: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationChannelsViewModel = hiltViewModel(),
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
    val meshPeopleCount by viewModel.meshPeopleCount.collectAsStateWithLifecycle()
    val wifiAwareEnabled by viewModel.wifiAwareEnabled.collectAsStateWithLifecycle()

    var customGeohash by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    val teleportToGeohash: (String) -> Unit = { value ->
        val channel = channelForManualGeohash(value)
        if (channel != null) {
            customError = null
            locationManager.selectManual(channel)
            onClose()
        } else {
            customGeohash = rejectedTeleportFieldText(value)
            customError = context.getString(R.string.invalid_geohash)
        }
    }

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
        nearbyChannelsOf(availableChannels)
    }
    val meshRow = meshRow(meshPeopleCount, wifiAwareEnabled, selectedChannel)
    // Not remembered: isBookmarked is a non-snapshot store read that refreshes only because
    // bookmarks is collected in this scope (Q-L5).
    val teleportedRow = teleportedRow(
        selectedChannel = selectedChannel,
        nearbyChannels = nearbyChannels,
        bookmarkNames = bookmarkNames,
        participantCounts = geohashParticipantCounts,
        isBookmarked = bookmarksStore::isBookmarked,
    )
    val bookmarkRows = buildBookmarkRows(
        bookmarks = bookmarks,
        bookmarkNames = bookmarkNames,
        participantCounts = geohashParticipantCounts,
        selectedChannel = selectedChannel,
    )
    val nearby = buildNearbyRows(
        nearbyChannels = nearbyChannels,
        locationNames = locationNames,
        participantCounts = geohashParticipantCounts,
        selectedChannel = selectedChannel,
        permissionState = permissionState,
        effectiveLocationEnabled = locationServicesEnabled,
        isBookmarked = bookmarksStore::isBookmarked,
    )
    val locationNotesSubtitle = when (
        val subtitle = notesSubtitle(notesRevealed, nearbyNotesState, nearbyNotes.size)
    ) {
        NotesSubtitle.Reveal -> stringResource(R.string.nearby_notes_reveal)
        NotesSubtitle.NoRelays -> stringResource(R.string.location_notes_relays_unavailable)
        NotesSubtitle.Loading -> stringResource(R.string.loading_location_notes)
        NotesSubtitle.One -> stringResource(R.string.nearby_notes_one)
        is NotesSubtitle.Many -> stringResource(R.string.nearby_notes_many, subtitle.n)
        NotesSubtitle.Empty -> stringResource(R.string.location_notes_empty_title)
    }

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
                                title = stringResource(R.string.mesh_title),
                                countText = channelCountText(meshRow.title),
                                subtitle = stringResource(
                                    if (meshRow.wifiAwareEnabled) {
                                        R.string.location_bluetooth_wifi_subtitle
                                    } else {
                                        R.string.location_bluetooth_subtitle
                                    },
                                    meshRangeString()
                                ),
                                isSelected = meshRow.isSelected,
                                participantCount = meshRow.peopleCount,
                                titleColor = standardBlue,
                                titleBold = meshRow.titleBold,
                                onClick = {
                                    locationManager.select(ChannelID.Mesh)
                                    onClose()
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

                teleportedRow?.let { row ->
                    val channel = row.channel
                    item(key = "teleported_card") {
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
                                    title = "#${row.geohash}",
                                    countText = channelCountText(row.title),
                                    subtitle = row.subtitle,
                                    isSelected = row.isSelected,
                                    participantCount = row.participantCount,
                                    titleColor = standardGreen,
                                    titleBold = row.titleBold,
                                    trailingContent = {
                                        ChannelBookmarkButton(
                                            bookmarked = row.isBookmarked,
                                            onClick = {
                                                bookmarksStore.toggle(channel.geohash)
                                            }
                                        )
                                    },
                                    onClick = {
                                        locationManager.select(ChannelID.Location(channel))
                                        onClose()
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
                                    bookmarkRows.forEachIndexed { index, row ->
                                        if (index > 0) SheetCardDivider()
                                        val gh = row.geohash
                                        val channel = row.channel

                                        ChannelOptionRow(
                                            title = "#$gh",
                                            countText = channelCountText(row.title),
                                            subtitle = row.subtitle,
                                            isSelected = row.isSelected,
                                            participantCount = row.participantCount,
                                            titleBold = row.titleBold,
                                            trailingContent = {
                                                ChannelBookmarkButton(
                                                    bookmarked = row.isBookmarked,
                                                    onClick = { bookmarksStore.toggle(gh) }
                                                )
                                            },
                                            onClick = {
                                                locationManager.selectManual(
                                                    channel = channel,
                                                    teleported = isBookmarkTeleported(
                                                        geohash = gh,
                                                        availableChannels = availableChannels,
                                                        appLocationEnabled = appLocationEnabled,
                                                    )
                                                )
                                                onClose()
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
                                    if (nearby.rows.isNotEmpty()) {
                                        nearby.rows.forEachIndexed { index, row ->
                                            if (index > 0) SheetCardDivider()
                                            val channel = row.channel

                                            ChannelOptionRow(
                                                title = channelLevelName(channel.level),
                                                countText = channelCountText(row.title),
                                                subtitle = row.subtitle,
                                                isSelected = row.isSelected,
                                                participantCount = row.participantCount,
                                                titleColor = standardGreen,
                                                titleBold = row.titleBold,
                                                trailingContent = {
                                                    ChannelBookmarkButton(
                                                        bookmarked = row.isBookmarked,
                                                        onClick = { bookmarksStore.toggle(channel.geohash) }
                                                    )
                                                },
                                                onClick = {
                                                    if (locationManager.selectNearby(channel)) {
                                                        onClose()
                                                    }
                                                }
                                            )
                                        }
                                        SheetCardDivider()
                                    } else if (nearby.showLoading) {
                                        ChannelLoadingRow()
                                        SheetCardDivider()
                                    }
                                }

                                CustomGeohashRow(
                                    customGeohash = customGeohash,
                                    onGeohashChange = { value ->
                                        customGeohash = normalizeGeohashInput(value)
                                        customError = null
                                    },
                                    onOpenMap = {
                                        val initial = mapPickerInitialGeohash(customGeohash, selectedChannel)
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
                            countText = null,
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
                                onShowLocationNotes()
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

            BitchatScreenTopBar(
                onBack = onClose,
                modifier = Modifier.align(Alignment.TopCenter),
                title = {
                    BitchatSheetTitle(
                        text = stringResource(R.string.location_channels_title)
                    )
                }
            )
        }
    }

    LifecycleResumeEffect(appLocationEnabled, systemLocationEnabled) {
        val currentPermission = locationManager.syncPermissionState()
        if (appLocationEnabled &&
            systemLocationEnabled &&
            currentPermission == LocationChannelManager.PermissionState.AUTHORIZED
        ) {
            // Retain the redesign branch's immediate refresh when the screen resumes while
            // honoring main's independent app/system privacy gates.
            locationManager.enableLocationChannels()
            locationManager.beginLiveRefresh()
        }

        onPauseOrDispose {
            locationManager.endLiveRefresh()
        }
    }

    // The notes row shows how many notes are nearby, which makes this a nearby-notes surface.
    // While channels opened as a sheet, chat stayed composed beneath it and held the
    // subscription; as a destination it replaces chat, which releases that hold and clears the
    // notes, so the screen holds its own and keeps availability current. A hold is not consent:
    // nothing subscribes until notes have been revealed.
    val buildingGeohash = availableChannels
        .firstOrNull { it.level == GeohashChannelLevel.BUILDING }
        ?.geohash
    DisposableEffect(buildingGeohash, locationServicesEnabled, permissionState, nearbyNotesController) {
        nearbyNotesController.updateAvailability(
            locationEnabled = locationServicesEnabled,
            locationAuthorized = permissionState == LocationChannelManager.PermissionState.AUTHORIZED,
            buildingGeohash = buildingGeohash,
        )
        nearbyNotesController.activate()
        onDispose { nearbyNotesController.deactivate() }
    }

    // Sampling management: update sampling when channels/bookmarks change
    LaunchedEffect(
        availableChannels,
        bookmarks,
        appLocationEnabled,
        notesRevealed
    ) {
        val sampling = samplingGeohashes(
            availableChannels = availableChannels,
            bookmarks = bookmarks,
            notesRevealed = notesRevealed,
        )
        viewModel.sampleGeohashes(
            liveLocationGeohashes = sampling.live,
            userSelectedGeohashes = sampling.userSelected
        )
    }
}

/**
 * Single channel option — settings-row geometry: 22.dp leading slot, title + subtitle, trailing.
 * Selected state is a 12.dp green dot centered in the leading slot (icon-sized footprint).
 */
@Composable
private fun ChannelOptionRow(
    title: String,
    countText: String?,
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
                    text = title,
                    fontSize = 14.sp,
                    fontFamily = BitchatFontFamily,
                    fontWeight = if (titleBold) FontWeight.SemiBold else FontWeight.Medium,
                    color = titleColor ?: colorScheme.onSurface
                )
                countText?.let { count ->
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
    onOpenMap: () -> Unit,
    onTeleport: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val isValid = isTeleportEnabled(customGeohash)
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

/** L10: the `people_count` label of [title]; "?" in place of 0 when the count is unknown. */
@Composable
private fun channelCountText(title: ChannelTitle): String {
    val ctx = LocalContext.current
    return channelCountText(title) { n ->
        ctx.resources.getQuantityString(R.plurals.people_count, n, n)
    }
}

/**
 * L10: a nearby row's title. "Building" is a hard-coded literal, unreachable because L1 drops
 * BUILDING channels (Q-L14).
 */
@Composable
private fun channelLevelName(level: GeohashChannelLevel): String = when (level) {
    GeohashChannelLevel.BUILDING -> "Building"
    GeohashChannelLevel.BLOCK -> stringResource(R.string.location_level_block)
    GeohashChannelLevel.NEIGHBORHOOD -> stringResource(R.string.location_level_neighborhood)
    GeohashChannelLevel.CITY -> stringResource(R.string.location_level_city)
    GeohashChannelLevel.PROVINCE -> stringResource(R.string.location_level_province)
    GeohashChannelLevel.REGION -> stringResource(R.string.location_level_region)
}
