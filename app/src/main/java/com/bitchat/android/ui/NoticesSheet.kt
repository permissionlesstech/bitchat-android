package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.board.BoardPostPacket
import com.bitchat.android.board.NoticeSource
import com.bitchat.android.board.UnifiedNotice
import com.bitchat.android.board.UnifiedNotices
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import java.text.DateFormat
import java.util.Date

private enum class NoticesTab {
    GEO,
    MESH
}

@Composable
fun NoticesSheetPresenter(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val availableChannels by locationManager.availableChannels.collectAsStateWithLifecycle()
    val selectedChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val selectedGeohash = (selectedChannel as? ChannelID.Location)?.channel?.geohash
    val buildingGeohash = availableChannels
        .firstOrNull { it.level == GeohashChannelLevel.BUILDING }
        ?.geohash

    LaunchedEffect(Unit) {
        locationManager.refreshChannels()
    }

    NoticesSheet(
        viewModel = viewModel,
        geoGeohash = selectedGeohash ?: buildingGeohash,
        startOnGeo = selectedGeohash != null,
        onEnableLocation = {
            locationManager.enableLocationServices()
            locationManager.enableLocationChannels()
            locationManager.refreshChannels()
        },
        onDismiss = onDismiss
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NoticesSheet(
    viewModel: ChatViewModel,
    geoGeohash: String?,
    startOnGeo: Boolean,
    onEnableLocation: () -> Unit,
    onDismiss: () -> Unit
) {
    val boardPosts by viewModel.boardManager.posts.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val notesManager = remember { LocationNotesManager.getInstance() }
    val relayNotes by notesManager.notes.collectAsStateWithLifecycle()
    var selectedTab by remember {
        mutableStateOf(if (startOnGeo) NoticesTab.GEO else NoticesTab.MESH)
    }
    var draft by remember { mutableStateOf("") }
    var urgent by remember { mutableStateOf(false) }
    var expiryDays by remember { mutableIntStateOf(if (startOnGeo) 0 else 7) }
    var sendFailed by remember { mutableStateOf(false) }

    val scope = if (selectedTab == NoticesTab.GEO) geoGeohash.orEmpty() else ""
    val notices = remember(scope, boardPosts, relayNotes) {
        UnifiedNotices.merge(
            geohash = scope,
            boardPosts = boardPosts,
            relayNotes = if (scope.isEmpty()) emptyList() else relayNotes
        )
    }
    val geoCount = remember(geoGeohash, boardPosts, relayNotes) {
        geoGeohash?.let { UnifiedNotices.merge(it, boardPosts, relayNotes).size } ?: 0
    }
    val meshCount = remember(boardPosts) {
        boardPosts.count { it.geohash.isEmpty() }
    }

    LaunchedEffect(selectedTab, geoGeohash) {
        if (selectedTab == NoticesTab.GEO && geoGeohash != null) {
            if (notesManager.geohash.value == geoGeohash.lowercase() &&
                notesManager.state.value == LocationNotesManager.State.IDLE
            ) {
                notesManager.refresh()
            } else {
                notesManager.setGeohash(geoGeohash)
            }
        } else {
            notesManager.cancel()
        }
        expiryDays = if (selectedTab == NoticesTab.GEO) 0 else 7
        urgent = false
        viewModel.boardManager.markSeen(setOf(scope))
    }
    LaunchedEffect(geoGeohash) {
        viewModel.boardManager.markSeen(
            buildSet {
                add("")
                geoGeohash?.let(::add)
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { notesManager.cancel() }
    }

    BitchatBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            BitchatSheetTopBar(
                onClose = onDismiss,
                title = {
                    BitchatSheetTitle(
                        text = if (scope.isEmpty() && selectedTab == NoticesTab.GEO) {
                            stringResource(R.string.notices_title)
                        } else {
                            "${stringResource(R.string.notices_title)} @ #" +
                                if (scope.isEmpty()) "mesh" else scope
                        }
                    )
                }
            )

            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == NoticesTab.GEO,
                    onClick = { selectedTab = NoticesTab.GEO },
                    text = {
                        Text(stringResource(R.string.notices_tab_geo_count, geoCount))
                    }
                )
                Tab(
                    selected = selectedTab == NoticesTab.MESH,
                    onClick = { selectedTab = NoticesTab.MESH },
                    text = {
                        Text(stringResource(R.string.notices_tab_mesh_count, meshCount))
                    }
                )
            }

            if (selectedTab == NoticesTab.GEO && geoGeohash == null) {
                LocationUnavailable(
                    onEnableLocation = onEnableLocation,
                    modifier = Modifier.weight(1f)
                )
            } else {
                NoticesContent(
                    notices = notices,
                    scope = scope,
                    isGeo = selectedTab == NoticesTab.GEO,
                    showsSource = selectedTab == NoticesTab.GEO,
                    viewModel = viewModel,
                    notesManager = notesManager,
                    modifier = Modifier.weight(1f)
                )
            }

            if (selectedTab == NoticesTab.MESH || geoGeohash != null) {
                HorizontalDivider()
                Composer(
                    draft = draft,
                    onDraftChange = {
                        if (it.toByteArray(Charsets.UTF_8).size <= 512) {
                            draft = it
                            sendFailed = false
                        }
                    },
                    isGeo = selectedTab == NoticesTab.GEO,
                    urgent = urgent,
                    onUrgentChange = { urgent = it },
                    expiryDays = expiryDays,
                    onExpiryChange = { expiryDays = it },
                    enabled = true,
                    sendFailed = sendFailed,
                    onSend = {
                        if (NoticeComposerPolicy.isPermanentRelayOnlyGeo(
                                isGeo = selectedTab == NoticesTab.GEO,
                                expiryDays = expiryDays
                            )
                        ) {
                            notesManager.send(
                                content = draft,
                                nickname = nickname,
                                expiresAt = null
                            )
                            draft = ""
                            sendFailed = false
                        } else {
                            val sent = viewModel.boardManager.createPost(
                                content = draft,
                                geohash = scope,
                                nickname = nickname,
                                urgent = selectedTab == NoticesTab.MESH && urgent,
                                expiryDays = expiryDays
                            )
                            if (sent) draft = "" else sendFailed = true
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun NoticesContent(
    notices: List<UnifiedNotice>,
    scope: String,
    isGeo: Boolean,
    showsSource: Boolean,
    viewModel: ChatViewModel,
    notesManager: LocationNotesManager,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "description") {
            Column {
                Text(
                    text = if (isGeo) {
                        stringResource(R.string.location_notes_description)
                    } else {
                        stringResource(R.string.notices_description_mesh)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (scope.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "#$scope",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (notices.isEmpty()) {
            item(key = "empty") {
                Column(modifier = Modifier.padding(vertical = 32.dp)) {
                    Text(
                        text = stringResource(R.string.location_notes_empty_title),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.location_notes_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(notices, key = { it.id }) { notice ->
                val ownBoard = notice.boardPost?.let(viewModel.boardManager::isOwnPost) == true
                val ownRelay = notice.nostrNote?.let(notesManager::isOwnNote) == true
                NoticeRow(
                    notice = notice,
                    showsSource = showsSource,
                    canDelete = ownBoard || ownRelay,
                    onDelete = {
                        notice.boardPost?.let(viewModel.boardManager::deletePost)
                            ?: notice.nostrNote?.let(notesManager::delete)
                    }
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(
    notice: UnifiedNotice,
    showsSource: Boolean,
    canDelete: Boolean,
    onDelete: () -> Unit
) {
    val boardAuthor = notice.boardPost?.let { post ->
        post.authorNickname.trim().ifEmpty {
            "anon#${post.authorSigningKey.takeLast(2).toByteArray().toHex()}"
        }
    }
    val relayAuthor = notice.nostrNote?.displayName
    val author = boardAuthor ?: relayAuthor ?: notice.nickname.ifEmpty { "anon" }
    val time = remember(notice.createdAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(notice.createdAtMs))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (notice.urgent) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = stringResource(R.string.notices_urgent),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = "@$author",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (showsSource) {
                Text(
                    text = if (notice.source == NoticeSource.MESH) {
                        stringResource(R.string.notices_source_mesh)
                    } else {
                        stringResource(R.string.notices_source_network)
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.notices_delete),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Text(
            text = notice.content,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = notice.expiresAtMs?.let {
                "$time · ${stringResource(R.string.notices_fades, relativeExpiry(it))}"
            } ?: time,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LocationUnavailable(
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.notices_location_unavailable),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.notices_location_unavailable_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onEnableLocation) {
                Text(stringResource(R.string.notices_enable_location))
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    isGeo: Boolean,
    urgent: Boolean,
    onUrgentChange: (Boolean) -> Unit,
    expiryDays: Int,
    onExpiryChange: (Int) -> Unit,
    enabled: Boolean,
    sendFailed: Boolean,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (!isGeo) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.notices_urgent),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = urgent, onCheckedChange = onUrgentChange)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.notices_expiry),
                style = MaterialTheme.typography.labelMedium
            )
            NoticeComposerPolicy.expiryOptions(isGeo).forEach { days ->
                val optionDescription = if (days == 0) {
                    stringResource(R.string.notices_permanent)
                } else {
                    stringResource(R.string.notices_days, days)
                }
                FilterChip(
                    selected = expiryDays == days,
                    onClick = { onExpiryChange(days) },
                    modifier = Modifier.semantics {
                        contentDescription = optionDescription
                    },
                    label = {
                        Text(if (days == 0) "∞" else "${days}d")
                    }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.notices_placeholder)) },
                supportingText = if (sendFailed) {
                    { Text(stringResource(R.string.notices_send_failed)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.notices_post)
                )
            }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun relativeExpiry(expiresAtMs: Long): String {
    val remainingMs = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)
    val hours = remainingMs / 3_600_000L
    return if (hours >= 24) "${hours / 24}d" else "${hours}h"
}
