package com.bitchat.android.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.bitchat.android.geohash.ChannelID
import kotlinx.coroutines.launch
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R

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
    val bookmarksStore = remember { GeohashBookmarksStore.getInstance(context) }

    // Observe location manager state
    val permissionState by locationManager.permissionState.observeAsState()
    val availableChannels by locationManager.availableChannels.observeAsState(emptyList())
    val selectedChannel by locationManager.selectedChannel.observeAsState()
    val locationNames by locationManager.locationNames.observeAsState(emptyMap())
    val locationServicesEnabled by locationManager.locationServicesEnabled.observeAsState(false)

    // Observe bookmarks state
    val bookmarks by bookmarksStore.bookmarks.observeAsState(emptyList())
    val bookmarkNames by bookmarksStore.bookmarkNames.observeAsState(emptyMap())

    // Observe reactive participant counts
    val geohashParticipantCounts by viewModel.geohashParticipantCounts.observeAsState(emptyMap())

    // UI state
    var customGeohash by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    var isInputFocused by remember { mutableStateOf(false) }

    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val coroutineScope = rememberCoroutineScope()

    // Scroll state for LazyColumn with animated top bar
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    val mapPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val gh = result.data?.getStringExtra(GeohashPickerActivity.EXTRA_RESULT_GEOHASH)
            if (!gh.isNullOrBlank()) {
                customGeohash = gh
                customError = null
            }
        }
    }

    // iOS system colors (matches iOS exactly)
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val standardGreen = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D) // iOS green
    val standardBlue = Color(0xFF007AFF) // iOS blue

    if (isPresented) {
        ModalBottomSheet(
            modifier = modifier.statusBarsPadding(),
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = null
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 48.dp, bottom = 16.dp)
                ) {
                    // Header Section
                    item(key = "header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.location_channels_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            Text(
                                text = stringResource(R.string.location_channels_desc),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Permission controls if services enabled
                    if (locationServicesEnabled) {
                        item(key = "permissions") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .padding(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
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
                                                text = stringResource(R.string.grant_location_permission),
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    LocationChannelManager.PermissionState.DENIED,
                                    LocationChannelManager.PermissionState.RESTRICTED -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = stringResource(R.string.location_permission_denied),
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
                                                    text = stringResource(R.string.open_settings),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }
                                    LocationChannelManager.PermissionState.AUTHORIZED -> {
                                        Text(
                                            text = stringResource(R.string.location_permission_granted),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = standardGreen
                                        )
                                    }
                                    null -> {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(12.dp))
                                            Text(
                                                text = stringResource(R.string.checking_permissions),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Mesh option first
                    item(key = "mesh") {
                        ChannelRow(
                            title = meshTitleWithCount(viewModel),
                            subtitle = stringResource(R.string.location_bluetooth_subtitle, bluetoothRangeString()),
                            isSelected = selectedChannel is ChannelID.Mesh,
                            titleColor = standardBlue,
                            titleBold = meshCount(viewModel) > 0,
                            trailingContent = null,
                            onClick = {
                                locationManager.select(ChannelID.Mesh)
                                onDismiss()
                            }
                        )
                    }

                    // Nearby options (only show if location services are enabled)
                    // CRITICAL: Filter out .building level (precision 8) - iOS pattern
                    // iOS: let nearby = manager.availableChannels.filter { $0.level != .building }
                    if (availableChannels.isNotEmpty() && locationServicesEnabled) {
                        val nearbyChannels = availableChannels.filter { it.level != GeohashChannelLevel.BUILDING }
                        items(nearbyChannels) { channel ->
                            val coverage = coverageString(channel.geohash.length)
                            val nameBase = locationNames[channel.level]
                            val namePart = nameBase?.let { formattedNamePrefix(channel.level) + it }
                            val subtitlePrefix = "#${channel.geohash} • $coverage"
                            val participantCount = geohashParticipantCounts[channel.geohash] ?: 0
                            val highlight = participantCount > 0
                            val isBookmarked = bookmarksStore.isBookmarked(channel.geohash)

                            ChannelRow(
                                title = geohashTitleWithCount(channel, participantCount),
                                subtitle = subtitlePrefix + (namePart?.let { " • $it" } ?: ""),
                                isSelected = isChannelSelected(channel, selectedChannel),
                                titleColor = standardGreen,
                                titleBold = highlight,
                                trailingContent = {
                                IconButton(onClick = { bookmarksStore.toggle(channel.geohash) }) {
                                    Icon(
                                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                        contentDescription = if (isBookmarked) stringResource(R.string.cd_remove_bookmark) else stringResource(R.string.cd_add_bookmark),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                    )
                                }
                                },
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
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text(
                                    text = stringResource(R.string.finding_nearby_channels),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Bookmarked geohashes
                    if (bookmarks.isNotEmpty()) {
                        item(key = "bookmarked_header") {
                            Text(
                                text = stringResource(R.string.bookmarked),
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(bookmarks) { gh ->
                            val level = levelForLength(gh.length)
                            val channel = GeohashChannel(level = level, geohash = gh)
                            val coverage = coverageString(gh.length)
                            val subtitlePrefix = "#${gh} • $coverage"
                            val name = bookmarkNames[gh]
                            val subtitle = subtitlePrefix + (name?.let { " • ${formattedNamePrefix(level)}$it" } ?: "")
                            val participantCount = geohashParticipantCounts[gh] ?: 0
                            val title = geohashHashTitleWithCount(gh, participantCount)

                            ChannelRow(
                                title = title,
                                subtitle = subtitle,
                                isSelected = isChannelSelected(channel, selectedChannel),
                                titleColor = null,
                                titleBold = participantCount > 0,
                                trailingContent = {
                                    IconButton(onClick = { bookmarksStore.toggle(gh) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Bookmark,
                                            contentDescription = stringResource(R.string.cd_remove_bookmark),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        )
                                    }
                                },
                                onClick = {
                                    // For bookmarked selection, mark teleported based on regional membership
                                    val inRegional = availableChannels.any { it.geohash == gh }
                                    if (!inRegional && availableChannels.isNotEmpty()) {
                                        locationManager.setTeleported(true)
                                    } else {
                                        locationManager.setTeleported(false)
                                    }
                                    locationManager.select(ChannelID.Location(channel))
                                    onDismiss()
                                }
                            )
                            LaunchedEffect(gh) { bookmarksStore.resolveNameIfNeeded(gh) }
                        }
                    }

                    // Custom geohash teleport (iOS-style inline form)
                    item(key = "custom_geohash") {
                        Surface(
                            color = Color.Transparent,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.hash_symbol),
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
                                                text = stringResource(R.string.geohash_placeholder),
                                                fontSize = BASE_FONT_SIZE.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                )

                                val normalized = customGeohash.trim().lowercase().replace("#", "")
                                
                                // Map picker button
                                IconButton(onClick = {
                                    val initial = when {
                                        normalized.isNotBlank() -> normalized
                                        selectedChannel is ChannelID.Location -> (selectedChannel as ChannelID.Location).channel.geohash
                                        else -> ""
                                    }
                                    val intent = Intent(context, GeohashPickerActivity::class.java).apply {
                                        putExtra(GeohashPickerActivity.EXTRA_INITIAL_GEOHASH, initial)
                                    }
                                    mapPickerLauncher.launch(intent)
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Map,
                                        contentDescription = stringResource(R.string.cd_open_map),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                    )
                                }

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
                                            customError = context.getString(R.string.invalid_geohash)
                                        }
                                    },
                                    enabled = isValid,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.teleport),
                                            fontSize = BASE_FONT_SIZE.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.PinDrop,
                                            contentDescription = stringResource(R.string.cd_teleport),
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Error message for custom geohash
                    if (customError != null) {
                        item(key = "geohash_error") {
                            Text(
                                text = customError!!,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Red,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                            )
                        }
                    }

                    // Location services toggle button
                    item(key = "location_toggle") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .padding(top = 8.dp)
                        ) {
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
                                    text = if (locationServicesEnabled) stringResource(R.string.disable_location_services) else stringResource(R.string.enable_location_services),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // TopBar (animated)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha))
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.close_plain),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }

    // Lifecycle management: when presented, sample both nearby and bookmarked geohashes
    LaunchedEffect(isPresented, availableChannels, bookmarks) {
        if (isPresented) {
            if (permissionState == LocationChannelManager.PermissionState.AUTHORIZED && locationServicesEnabled) {
                locationManager.refreshChannels()
                locationManager.beginLiveRefresh()
            }
            val geohashes = (availableChannels.map { it.geohash } + bookmarks).toSet().toList()
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
}

@Composable
private fun ChannelRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    titleColor: Color? = null,
    titleBold: Boolean = false,
    trailingContent: (@Composable (() -> Unit))? = null,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        tint = Color(0xFF32D74B), // iOS green for checkmark
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                if (trailingContent != null) {
                    trailingContent()
                }
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

@Composable
private fun meshTitleWithCount(viewModel: ChatViewModel): String {
    val meshCount = meshCount(viewModel)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val peopleText = ctx.resources.getQuantityString(com.bitchat.android.R.plurals.people_count, meshCount, meshCount)
    val meshLabel = stringResource(com.bitchat.android.R.string.mesh_label)
    return "$meshLabel [$peopleText]"
}

private fun meshCount(viewModel: ChatViewModel): Int {
    val myID = viewModel.meshService.myPeerID
    return viewModel.connectedPeers.value?.count { peerID ->
        peerID != myID
    } ?: 0
}

@Composable
private fun geohashTitleWithCount(channel: GeohashChannel, participantCount: Int): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val peopleText = ctx.resources.getQuantityString(com.bitchat.android.R.plurals.people_count, participantCount, participantCount)
    val levelName = when (channel.level) {
        com.bitchat.android.geohash.GeohashChannelLevel.BUILDING -> "Building" // iOS: precision 8 for location notes
        com.bitchat.android.geohash.GeohashChannelLevel.BLOCK -> stringResource(com.bitchat.android.R.string.location_level_block)
        com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD -> stringResource(com.bitchat.android.R.string.location_level_neighborhood)
        com.bitchat.android.geohash.GeohashChannelLevel.CITY -> stringResource(com.bitchat.android.R.string.location_level_city)
        com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE -> stringResource(com.bitchat.android.R.string.location_level_province)
        com.bitchat.android.geohash.GeohashChannelLevel.REGION -> stringResource(com.bitchat.android.R.string.location_level_region)
    }
    return "$levelName [$peopleText]"
}

@Composable
private fun geohashHashTitleWithCount(geohash: String, participantCount: Int): String {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val peopleText = ctx.resources.getQuantityString(com.bitchat.android.R.plurals.people_count, participantCount, participantCount)
    return "#$geohash [$peopleText]"
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
        8 -> GeohashChannelLevel.BUILDING // iOS: precision 8 for building-level
        else -> if (length > 8) GeohashChannelLevel.BUILDING else GeohashChannelLevel.BLOCK
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
    return "~"
}
