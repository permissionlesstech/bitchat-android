package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.feature.chat.R
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.colorForPeer
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay


/**
 * The mesh peer list: private conversations, joined channels and nearby people.
 *
 * A full screen. [onBack] returns to chat; opening a conversation pushes the
 * private chat over this screen, so Back from it lands here again.
 */

@Composable
fun MeshPeerListScreen(
    onBack: () -> Unit,
    onShowVerification: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeshPeerListViewModel = hiltViewModel(),
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadChannelMessages by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val conversationStoreState by viewModel.conversationStoreState.collectAsStateWithLifecycle()
    val peerDirect by viewModel.peerDirect.collectAsStateWithLifecycle()
    val geohashPeopleCount = geohashPeople.size
    val wifiAwareConnected by viewModel.wifiAwareConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lookups = remember(viewModel, context) {
        viewModel.peerListLookups(context.applicationContext)
    }
    val wifiAwarePeerIDs = remember(wifiAwareConnected) { wifiAwarePeerIDs(wifiAwareConnected) }
    val directPeerIdentityIDs = remember(peerDirect) { directPeerIdentityIDs(peerDirect) }
    val wifiAwareIdentityIDs = remember(wifiAwarePeerIDs) {
        wifiAwareIdentityIDs(wifiAwarePeerIDs)
    }
    val conversationIdentityAliases = remember(conversations) {
        conversationIdentityAliases(conversations)
    }
    val visibleConnectedPeers =
        visibleConnectedPeers(connectedPeers, conversationIdentityAliases, lookups)
    var pendingConversationDelete by remember {
        mutableStateOf<ConversationSummary?>(null)
    }
    var conversationQuery by rememberSaveable { mutableStateOf("") }
    val filteredConversations = remember(conversations, conversationQuery) {
        filterConversations(conversations, conversationQuery)
    }
    val onlineConversations = remember(filteredConversations) {
        onlineConversations(filteredConversations)
    }
    val offlineConversations = remember(filteredConversations) {
        offlineConversations(filteredConversations)
    }
    val sheetScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Scroll state for animated top bar
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

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        LazyColumn(
            state = listState,
            // The search field sits in the list; consuming the keyboard inset here
            // lets it scroll above the keyboard.
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp)
        ) {
            val peopleCount = when (selectedLocationChannel) {
                is ChannelID.Location -> geohashPeopleCount
                else -> meshPeopleCount(visibleConnectedPeers, viewModel.myPeerID)
            }

            item(key = "private_conversations_header") {
                SheetIconSectionHeader(
                    iconRes = R.drawable.ic_spec_envelope,
                    title = stringResource(R.string.conversations),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (showConversationSearch(conversations)) {
                item(key = "private_conversations_search") {
                    OutlinedTextField(
                        value = conversationQuery,
                        onValueChange = { conversationQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AboutHorizontalPadding)
                            .padding(top = 10.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = BitchatFontFamily
                        ),
                        placeholder = {
                            Text(
                                stringResource(R.string.search_conversations),
                                fontFamily = BitchatFontFamily
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        trailingIcon = if (conversationQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { conversationQuery = "" }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.clear)
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }

            when (
                conversationSectionStatus(
                    conversationStoreState,
                    conversations,
                    filteredConversations
                )
            ) {
                ConversationStatusKind.Loading -> {
                    item(key = "private_conversations_loading") {
                        ConversationSectionStatus(
                            icon = { CircularProgressIndicator(Modifier.size(20.dp)) },
                            text = stringResource(R.string.loading_conversations)
                        )
                    }
                }

                ConversationStatusKind.Error -> {
                    item(key = "private_conversations_error") {
                        ConversationSectionStatus(
                            icon = {
                                Icon(
                                    Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            text = stringResource(R.string.conversation_storage_error)
                        )
                    }
                }

                ConversationStatusKind.Empty -> {
                    item(key = "private_conversations_empty") {
                        ConversationSectionStatus(
                            icon = {
                                Icon(
                                    painterResource(R.drawable.ic_spec_envelope),
                                    contentDescription = null
                                )
                            },
                            text = stringResource(R.string.no_conversations_yet)
                        )
                    }
                }

                ConversationStatusKind.NoResults -> {
                    item(key = "private_conversations_no_results") {
                        ConversationSectionStatus(
                            icon = {
                                Icon(Icons.Outlined.SearchOff, contentDescription = null)
                            },
                            text = stringResource(R.string.no_conversation_results)
                        )
                    }
                }

                null -> Unit
            }

            if (onlineConversations.isNotEmpty()) {
                item(key = "private_conversations_online_label") {
                    ConversationGroupLabel(
                        text = stringResource(R.string.online_conversations)
                    )
                }
                itemsIndexed(
                    items = onlineConversations,
                    key = { _, conversation ->
                        "conversation:${conversation.conversationID}"
                    }
                ) { index, conversation ->
                    ConversationSwipeItem(
                        conversation = conversation,
                        directPeerIdentityIDs = directPeerIdentityIDs,
                        wifiAwareIdentityIDs = wifiAwareIdentityIDs,
                        viewModel = viewModel,
                        lookups = lookups,
                        isFirst = index == 0,
                        isLast = index == onlineConversations.lastIndex,
                        onPrivateChatStart = viewModel::openPrivateChat,
                        onDeleteRequested = { pendingConversationDelete = it },
                        onReadStateRequested = { item, isRead ->
                            sheetScope.launch {
                                viewModel.setConversationRead(item.conversationID, isRead)
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (offlineConversations.isNotEmpty()) {
                item(key = "private_conversations_offline_label") {
                    ConversationGroupLabel(
                        text = stringResource(R.string.offline_conversations)
                    )
                }
                itemsIndexed(
                    items = offlineConversations,
                    key = { _, conversation ->
                        "conversation:${conversation.conversationID}"
                    }
                ) { index, conversation ->
                    ConversationSwipeItem(
                        conversation = conversation,
                        directPeerIdentityIDs = directPeerIdentityIDs,
                        wifiAwareIdentityIDs = wifiAwareIdentityIDs,
                        viewModel = viewModel,
                        lookups = lookups,
                        isFirst = index == 0,
                        isLast = index == offlineConversations.lastIndex,
                        onPrivateChatStart = viewModel::openPrivateChat,
                        onDeleteRequested = { pendingConversationDelete = it },
                        onReadStateRequested = { item, isRead ->
                            sheetScope.launch {
                                viewModel.setConversationRead(item.conversationID, isRead)
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            // Channels section
            if (joinedChannels.isNotEmpty()) {
                item(key = "channels_section") {
                    Column {
                        SheetIconSectionHeader(
                            iconRes = R.drawable.ic_spec_chat_bubbles,
                            title = stringResource(R.string.channels),
                            modifier = Modifier.padding(
                                top = if (conversations.isNotEmpty()) 20.dp else 8.dp
                            )
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AboutHorizontalPadding)
                                .padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shape = AboutCardShape
                        ) {
                            Column {
                                buildChannelRows(
                                    joinedChannels,
                                    currentChannel,
                                    unreadChannelMessages
                                ).forEachIndexed { index, row ->
                                    if (index > 0) SheetCardDivider()
                                    val channel = row.name
                                    ChannelRow(
                                        channel = channel,
                                        isSelected = row.isSelected,
                                        unreadCount = row.unread,
                                        colorScheme = colorScheme,
                                        onChannelClick = {
                                            if (channel.startsWith("@")) {
                                                val peerName = channel.removePrefix("@")
                                                val peerID =
                                                    peerNicknames.entries.firstOrNull { it.value == peerName }?.key
                                                if (peerID != null) {
                                                    viewModel.openPrivateChat(peerID)
                                                }
                                            } else {
                                                viewModel.switchToChannel(channel)
                                                onBack()
                                            }
                                        },
                                        onLeaveChannel = {
                                            viewModel.leaveChannel(channel)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // People / geohash participants
            item(key = "people_section") {
                when (selectedLocationChannel) {
                    is ChannelID.Location -> {
                        GeohashPeopleList(
                            viewModel = viewModel,
                            lookups = lookups,
                            excludedIdentityAliases = conversationIdentityAliases,
                            modifier = Modifier.padding(
                                top = if (
                                    joinedChannels.isNotEmpty() ||
                                    conversations.isNotEmpty()
                                ) 20.dp else 8.dp
                            )
                        )
                    }

                    else -> {
                        PeopleSection(
                            modifier = Modifier.padding(
                                top = if (
                                    joinedChannels.isNotEmpty() ||
                                    conversations.isNotEmpty()
                                ) 20.dp else 8.dp
                            ),
                            connectedPeers = visibleConnectedPeers,
                            peerNicknames = peerNicknames,
                            peerRSSI = peerRSSI,
                            nickname = nickname,
                            colorScheme = colorScheme,
                            selectedPrivatePeer = selectedPrivatePeer,
                            wifiAwarePeerIDs = wifiAwarePeerIDs,
                            peopleCount = peopleCount,
                            excludedIdentityAliases = conversationIdentityAliases,
                            viewModel = viewModel,
                            lookups = lookups,
                            onPrivateChatStart = viewModel::openPrivateChat
                        )
                    }
                }
            }
        }

        // TopBar (animated)
        BitchatScreenTopBar(
            title = {
                BitchatSheetTitle(text = stringResource(id = R.string.your_network))
            },
            backgroundAlpha = topBarAlpha,
            actions = {
                if (selectedLocationChannel !is ChannelID.Location) {
                    IconButton(
                        onClick = onShowVerification,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode,
                            contentDescription = stringResource(R.string.verify_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(HeaderIconSize)
                        )
                    }
                }
            },
            onBack = onBack,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(16.dp)
        )
    }
    }

    pendingConversationDelete?.let { conversation ->
        val deleteFailedMessage = stringResource(R.string.conversation_delete_failed)
        val deletedMessage = stringResource(
            R.string.conversation_deleted,
            conversation.displayName
        )
        val undoLabel = stringResource(R.string.undo)
        val restoreFailedMessage =
            stringResource(R.string.conversation_restore_failed)
        AlertDialog(
            onDismissRequest = { pendingConversationDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.delete_conversation_title),
                    fontFamily = BitchatFontFamily
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.delete_conversation_message,
                        conversation.displayName
                    ),
                    fontFamily = BitchatFontFamily
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConversationDelete = null
                        sheetScope.launch {
                            val deletion = viewModel.deletePrivateConversation(
                                conversation.conversationID
                            )
                            if (deletion == null) {
                                snackbarHostState.showSnackbar(
                                    message = deleteFailedMessage
                                )
                                return@launch
                            }
                            val result = snackbarHostState.showSnackbar(
                                message = deletedMessage,
                                actionLabel = undoLabel,
                                withDismissAction = true,
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                if (!viewModel.restoreDeletedConversation(deletion)) {
                                    snackbarHostState.showSnackbar(
                                        restoreFailedMessage
                                    )
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = BitchatFontFamily
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConversationDelete = null }) {
                    Text(
                        text = stringResource(android.R.string.cancel),
                        fontFamily = BitchatFontFamily
                    )
                }
            }
        )
    }
}

/** Icon size for trailing actions on peer rows (matches settings glyph scale). */
private val PeerRowIconSize = 22.dp

@Composable
private fun ChannelRow(
    channel: String,
    isSelected: Boolean,
    unreadCount: Int,
    colorScheme: ColorScheme,
    onChannelClick: () -> Unit,
    onLeaveChannel: () -> Unit,
) {
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChannelClick)
            .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(SheetRowLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(SheetRowSelectedDot)
                        .background(colorScheme.primary, CircleShape)
                )
            } else if (unreadCount > 0) {
                UnreadBadge(count = unreadCount, colorScheme = colorScheme)
            } else {
                Text(
                    text = "#",
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.width(SheetRowLeadingGutter))

        Text(
            text = channel,
            fontFamily = BitchatFontFamily,
            fontSize = 14.sp,
            color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        CloseButton(onClick = onLeaveChannel)
    }
}



@Composable
internal fun PeopleSection(
    modifier: Modifier  = Modifier,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    nickname: String,
    colorScheme: ColorScheme,
    selectedPrivatePeer: String?,
    wifiAwarePeerIDs: Set<String> = emptySet(),
    peopleCount: Int = 0,
    excludedIdentityAliases: Set<String> = emptySet(),
    viewModel: MeshPeerListViewModel,
    lookups: PeerListLookups,
    onPrivateChatStart: (String) -> Unit
) {
    val palette = LocalBitchatPalette.current

    Column(modifier = modifier) {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_people,
            title = stringResource(R.string.people_count_title, peopleCount)
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding)
                .padding(top = 10.dp),
            color = colorScheme.surface,
            shape = AboutCardShape
        ) {
            Column {
                if (connectedPeers.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.no_one_connected),
                        fontFamily = BitchatFontFamily,
                        fontSize = 12.sp,
                        color = palette.textTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical)
                    )
                }

        // Observe reactive state for favorites and fingerprints
        val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
        val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
        val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
        val peerFavoritedUs by viewModel.peerFavoritedUs.collectAsStateWithLifecycle()
        val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()
        val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()

        // Reactive favorite computation for all peers
        val peerFavoriteStates = remember(favoritePeers, peerFingerprints, connectedPeers) {
            peerFavoriteStates(connectedPeers, favoritePeers, peerFingerprints, lookups)
        }

        // Same "they favorited us" signal the private-chat header uses for orange outline stars.
        val peerTheyFavoritedUsStates = remember(peerFavoritedUs, peerFingerprints, connectedPeers) {
            peerTheyFavoritedUsStates(connectedPeers, peerFavoritedUs, peerFingerprints, lookups)
        }

        val peerVerifiedStates = remember(verifiedFingerprints, peerFingerprints, connectedPeers) {
            peerVerifiedStates(connectedPeers, verifiedFingerprints, lookups)
        }

        // Collected once for the whole card rather than once per row.
        val directMap by viewModel.peerDirect.collectAsStateWithLifecycle()

        // Every row this card will show, in final order, so the animated list can key on identity
        // and animate reordering. Offline favourites are appended after the connected peers.
        val rows = meshPeopleRows(
            connectedPeers = connectedPeers,
            peerNicknames = peerNicknames,
            peerDirect = directMap,
            wifiAwarePeerIDs = wifiAwarePeerIDs,
            verifiedFingerprints = verifiedFingerprints,
            unreadPrivateMessages = hasUnreadPrivateMessages,
            privateChats = privateChats,
            selectedPrivatePeer = selectedPrivatePeer,
            nickname = nickname,
            excludedIdentityAliases = excludedIdentityAliases,
            peerFavoriteStates = peerFavoriteStates,
            peerTheyFavoritedUsStates = peerTheyFavoritedUsStates,
            peerVerifiedStates = peerVerifiedStates,
            lookups = lookups,
        )

        AnimatedRowColumn(items = rows, key = { it.key }) { rowIndex, row ->
        Column {
        if (rowIndex > 0) SheetCardDivider()
        PeerItem(
            row = row,
            colorScheme = colorScheme,
            onItemClick = { onPrivateChatStart(row.openPeerID) }
        )
        }
        }
            }
        }

    }
}

@Composable
private fun ConversationSectionStatus(
    icon: @Composable () -> Unit,
    text: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = AboutCardShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = BitchatFontFamily
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationGroupLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding + 4.dp)
            .padding(top = 12.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = BitchatFontFamily,
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSwipeItem(
    conversation: ConversationSummary,
    directPeerIdentityIDs: Set<String>,
    wifiAwareIdentityIDs: Set<String>,
    viewModel: MeshPeerListViewModel,
    lookups: PeerListLookups,
    isFirst: Boolean,
    isLast: Boolean,
    onPrivateChatStart: (String) -> Unit,
    onDeleteRequested: (ConversationSummary) -> Unit,
    onReadStateRequested: (ConversationSummary, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val hapticFeedback = LocalHapticFeedback.current
    val deleteDescription = stringResource(R.string.delete_conversation_action)
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerFavoritedUs by viewModel.peerFavoritedUs.collectAsStateWithLifecycle()
    val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()
    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
    val favoriteRelationship = remember(
        conversation.identityAliases,
        favoritePeers,
        peerFavoritedUs
    ) {
        conversationFavoriteRelationship(conversation.identityAliases, lookups)
    }
    val favoriteFlags = conversationFavoriteFlags(
        conversation = conversation,
        favoriteRelationship = favoriteRelationship,
        favoritePeers = favoritePeers,
        peerFavoritedUs = peerFavoritedUs,
        peerFingerprints = peerFingerprints,
        verifiedFingerprints = verifiedFingerprints,
        lookups = lookups
    )
    val dismissState = rememberSwipeToDismissBoxState()
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 14.dp else 0.dp,
        topEnd = if (isFirst) 14.dp else 0.dp,
        bottomStart = if (isLast) 14.dp else 0.dp,
        bottomEnd = if (isLast) 14.dp else 0.dp
    )

    LaunchedEffect(dismissState.currentValue, conversation.conversationID) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onReadStateRequested(conversation, conversation.unreadCount > 0)
                dismissState.reset()
            }

            SwipeToDismissBoxValue.EndToStart -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onDeleteRequested(conversation)
                dismissState.reset()
            }

            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .clip(shape),
        color = colorScheme.surface,
        shape = shape
    ) {
        Column {
            if (!isFirst) SheetCardDivider()
            SwipeToDismissBox(
                state = dismissState,
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = true,
                backgroundContent = {
                    val markRead = conversation.unreadCount > 0
                    val startToEnd =
                        dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (startToEnd) {
                                    colorScheme.secondaryContainer
                                } else {
                                    colorScheme.errorContainer
                                }
                            )
                            .padding(horizontal = SheetRowHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (startToEnd) {
                            Arrangement.Start
                        } else {
                            Arrangement.End
                        }
                    ) {
                        Icon(
                            imageVector = if (startToEnd) {
                                if (markRead) Icons.Outlined.MarkEmailRead
                                else Icons.Outlined.MarkEmailUnread
                            } else {
                                Icons.Outlined.Delete
                            },
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (startToEnd) {
                                stringResource(
                                    if (markRead) R.string.mark_read else R.string.mark_unread
                                )
                            } else {
                                stringResource(R.string.delete)
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = BitchatFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            ) {
                ConversationRow(
                    conversation = conversation,
                    directPeerIdentityIDs = directPeerIdentityIDs,
                    wifiAwareIdentityIDs = wifiAwareIdentityIDs,
                    lookups = lookups,
                    favoriteFlags = favoriteFlags,
                    deleteDescription = deleteDescription,
                    onClick = { onPrivateChatStart(conversation.conversationID) },
                    onTogglePinned = {
                        viewModel.toggleConversationPinned(conversation.conversationID)
                    },
                    onToggleMuted = {
                        viewModel.toggleConversationMuted(conversation.conversationID)
                    },
                    onReadStateRequested = {
                        onReadStateRequested(conversation, conversation.unreadCount > 0)
                    },
                    onDeleteRequested = { onDeleteRequested(conversation) }
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    directPeerIdentityIDs: Set<String>,
    wifiAwareIdentityIDs: Set<String>,
    lookups: PeerListLookups,
    favoriteFlags: ConversationFavoriteFlags,
    deleteDescription: String,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleMuted: () -> Unit,
    onReadStateRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme
    var showActions by remember { mutableStateOf(false) }
    val row = buildConversationRow(
        conversation = conversation,
        favoriteFlags = favoriteFlags,
        directPeerIdentityIDs = directPeerIdentityIDs,
        wifiAwareIdentityIDs = wifiAwareIdentityIDs,
        lookups = lookups
    )
    val isWifiAware = row.isWifiAware
    val isDirect = row.isDirect
    val isFavorite = row.isFavorite
    val theyFavoritedUs = row.theyFavoritedUs
    val isVerified = row.isVerified
    val connectionDescription = meshConnectionDescription(row.connection)
    val basePreview = when (val kind = row.previewKind) {
        is ConversationPreviewKind.Draft -> stringResource(
            R.string.conversation_draft_preview,
            kind.text
        )
        ConversationPreviewKind.Image -> stringResource(R.string.notification_sent_image)
        ConversationPreviewKind.Voice -> stringResource(R.string.notification_sent_voice)
        ConversationPreviewKind.File -> stringResource(R.string.notification_sent_file)
        is ConversationPreviewKind.Literal -> kind.text
    }
    val messagePreview = if (row.previewIsOutgoing) {
        stringResource(R.string.conversation_you_preview, basePreview)
    } else {
        basePreview
    }
    val presenceDescription = when (row.presence) {
        ConversationPresence.Connected -> connectionDescription
        ConversationPresence.ReachableViaNostr ->
            stringResource(R.string.offline_reachable_via_nostr)
        ConversationPresence.NotInMesh -> stringResource(R.string.offline_not_in_mesh)
    }
    val assignedColor = colorForPeer(row.colorKey, palette)
    val baseNameRaw = row.baseName
    val suffix = row.suffix
    val relativeTime by produceState(
        initialValue = conversationRelativeTime(conversation.latestMessageAt),
        conversation.latestMessageAt
    ) {
        while (true) {
            value = conversationRelativeTime(conversation.latestMessageAt)
            delay(DateUtils.MINUTE_IN_MILLIS)
        }
    }
    val unreadDescription = if (conversation.unreadCount > 0) {
        stringResource(R.string.conversation_unread_count, conversation.unreadCount)
    } else {
        stringResource(R.string.conversation_read)
    }
    val pinDescription = stringResource(
        if (conversation.isPinned) R.string.unpin_conversation else R.string.pin_conversation
    )
    val muteDescription = stringResource(
        if (conversation.isMuted) R.string.unmute_conversation else R.string.mute_conversation
    )
    val readActionDescription = stringResource(
        if (conversation.unreadCount > 0) R.string.mark_read else R.string.mark_unread
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(
                    conversation.displayName,
                    unreadDescription,
                    messagePreview,
                    relativeTime,
                    presenceDescription
                ).joinToString(", ")
                stateDescription = presenceDescription
                customActions = listOf(
                    CustomAccessibilityAction(readActionDescription) {
                        onReadStateRequested()
                        true
                    },
                    CustomAccessibilityAction(pinDescription) {
                        onTogglePinned()
                        true
                    },
                    CustomAccessibilityAction(muteDescription) {
                        onToggleMuted()
                        true
                    },
                    CustomAccessibilityAction(deleteDescription) {
                        onDeleteRequested()
                        true
                    }
                )
            }
            .clickable(onClick = onClick)
            .padding(
                horizontal = SheetRowHorizontal,
                vertical = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            name = baseNameRaw,
            color = assignedColor,
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            isVerified = isVerified,
            badge = {
                when (row.presence) {
                    ConversationPresence.Connected -> Icon(
                        painter = painterResource(
                            conversationTransportIcon(
                                isReachedOverInternet = false,
                                isWifiAware = isWifiAware,
                                isDirect = isDirect
                            )
                        ),
                        contentDescription = connectionDescription,
                        modifier = Modifier.size(13.dp),
                        tint = colorScheme.primary
                    )

                    ConversationPresence.ReachableViaNostr -> Icon(
                        painter = painterResource(R.drawable.ic_spec_globe),
                        contentDescription = stringResource(
                            R.string.offline_reachable_via_nostr
                        ),
                        modifier = Modifier.size(13.dp),
                        tint = palette.accentPurple
                    )

                    ConversationPresence.NotInMesh -> Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(R.string.offline_not_in_mesh),
                        modifier = Modifier.size(11.dp),
                        tint = palette.textTertiary
                    )
                }
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = truncateNickname(baseNameRaw),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = BitchatFontFamily,
                        fontWeight = if (conversation.unreadCount > 0) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        }
                    ),
                    color = assignedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = BitchatFontFamily,
                            fontWeight = FontWeight.Medium
                        ),
                        color = assignedColor.copy(alpha = SUFFIX_ALPHA)
                    )
                }
                if (conversation.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = pinDescription,
                        modifier = Modifier.size(14.dp),
                        tint = palette.textTertiary
                    )
                }
                if (conversation.isMuted) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = muteDescription,
                        modifier = Modifier.size(14.dp),
                        tint = palette.textTertiary
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = messagePreview,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = BitchatFontFamily,
                        fontWeight = if (row.hasDraft) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        }
                    ),
                    color = if (row.hasDraft) {
                        palette.accentOrange
                    } else {
                        palette.textTertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = stringResource(
                        R.string.conversation_preview_timestamp,
                        relativeTime
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = BitchatFontFamily
                    ),
                    color = palette.textTertiary,
                    maxLines = 1
                )
            }
        }

        UnreadBadge(
            count = conversation.unreadCount,
            colorScheme = colorScheme,
            modifier = Modifier.padding(start = 4.dp)
        )

        Box {
            IconButton(
                onClick = { showActions = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.conversation_actions),
                    tint = palette.textTertiary
                )
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false }
            ) {
                DropdownMenuItem(
                    text = { Text(pinDescription) },
                    leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                    onClick = {
                        showActions = false
                        onTogglePinned()
                    }
                )
                DropdownMenuItem(
                    text = { Text(muteDescription) },
                    leadingIcon = {
                        Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
                    },
                    onClick = {
                        showActions = false
                        onToggleMuted()
                    }
                )
                DropdownMenuItem(
                    text = { Text(readActionDescription) },
                    leadingIcon = {
                        Icon(
                            if (conversation.unreadCount > 0) {
                                Icons.Outlined.MarkEmailRead
                            } else {
                                Icons.Outlined.MarkEmailUnread
                            },
                            contentDescription = null
                        )
                    },
                    onClick = {
                        showActions = false
                        onReadStateRequested()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.delete),
                            color = colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = colorScheme.error
                        )
                    },
                    onClick = {
                        showActions = false
                        onDeleteRequested()
                    }
                )
            }
        }
    }
}

private fun conversationRelativeTime(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()

@Composable
private fun PeerItem(
    row: MeshPersonRowUi,
    colorScheme: ColorScheme,
    onItemClick: () -> Unit,
) {
    val isDirect = row.isDirect
    val isWifiAware = row.isWifiAware
    val isConnected = row.isConnected
    val isSelected = row.isSelected
    val isFavorite = row.isFavorite
    val theyFavoritedUs = row.theyFavoritedUs
    val isVerified = row.isVerified
    val unreadCount = row.unreadCount
    val showNostrGlobe = row.showNostrGlobe
    val connectionDescription = meshConnectionDescription(
        meshConnectionKind(isWifiAware = isWifiAware, isDirect = isDirect)
    )
    // Split display name for hashtag suffix support (iOS-compatible)
    val baseNameRaw = row.baseName
    val baseName = truncateNickname(baseNameRaw)
    val suffix = row.suffix
    val isMe = row.isMe

    // Get consistent peer color (iOS-compatible)
    val palette = LocalBitchatPalette.current
    val assignedColor = colorForPeer(row.colorKey, palette)
    val baseColor = if (isMe) palette.accentOrange else assignedColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .padding(horizontal = SheetRowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            name = baseNameRaw,
            color = baseColor,
            isFavorite = isFavorite,
            theyFavoritedUs = theyFavoritedUs,
            isVerified = isVerified,
            badge = {
                when {
                    isConnected -> Icon(
                        painter = painterResource(
                            conversationTransportIcon(
                                isReachedOverInternet = false,
                                isWifiAware = isWifiAware,
                                isDirect = isDirect
                            )
                        ),
                        contentDescription = connectionDescription,
                        modifier = Modifier.size(13.dp),
                        tint = colorScheme.primary
                    )

                    showNostrGlobe -> Icon(
                        painter = painterResource(R.drawable.ic_spec_globe),
                        contentDescription = stringResource(R.string.cd_reachable_via_nostr),
                        modifier = Modifier.size(13.dp),
                        tint = palette.accentPurple
                    )

                    else -> Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(R.string.cd_offline_favorite),
                        modifier = Modifier.size(11.dp),
                        tint = palette.textTertiary
                    )
                }
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = baseName,
                fontFamily = BitchatFontFamily,
                fontSize = 14.sp,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    color = baseColor.copy(alpha = SUFFIX_ALPHA)
                )
            }
        }

        UnreadBadge(
            count = unreadCount,
            colorScheme = colorScheme,
            modifier = Modifier.padding(start = 4.dp)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(SheetRowSelectedDot)
                    .background(colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun meshConnectionDescription(connection: MeshConnectionKind): String = stringResource(
    when (connection) {
        MeshConnectionKind.WifiAware -> R.string.cd_direct_wifi_aware
        MeshConnectionKind.Bluetooth -> R.string.cd_direct_bluetooth
        MeshConnectionKind.Routed -> R.string.cd_routed_mesh
    }
)

/**
 * Reusable unread badge component for both channels and private messages
 */
@Composable
private fun UnreadBadge(
    count: Int,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    // Scale/fade in and out so a badge appearing while the sheet is open is noticed, and one
    // clearing does not just blink away.
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
            scaleIn(
                initialScale = 0.5f,
                animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
            ),
        exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
            scaleOut(
                targetScale = 0.5f,
                animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing)
            ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = palette.accentOrange,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedCountLabel(
                count = count,
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = BitchatFontFamily,
                color = Color.Black
            )
        }
    }
}

/**
 * Convert RSSI value (dBm) to signal strength percentage (0-100)
 * RSSI typically ranges from -30 (excellent) to -100 (very poor)
 * Maps to 0-100 scale where:
 * - 0-32: No signal (0 bars)
 * - 33-65: Weak (1 bar) 
 * - 66-98: Good (2 bars)
 * - 99-100: Excellent (3 bars)
 */
private fun convertRSSIToSignalStrength(rssi: Int?): Int {
    if (rssi == null) return 0
    
    return when {
        rssi >= -40 -> 100  // Excellent signal
        rssi >= -55 -> 85   // Very good signal  
        rssi >= -70 -> 70   // Good signal
        rssi >= -85 -> 50   // Fair signal
        rssi >= -100 -> 25  // Poor signal
        else -> 0           // Very poor or no signal
    }
}
