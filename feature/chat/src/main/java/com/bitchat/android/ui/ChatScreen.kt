package com.bitchat.android.ui

import android.os.Build
import com.bitchat.android.ui.theme.BitchatFontFamily
// [Goose] Bridge file share events to ViewModel via dispatcher is installed in ChatScreen composition

// [Goose] Installing FileShareDispatcher handler in ChatScreen to forward file sends to ViewModel


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.feature.chat.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.theme.BitchatMotion

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - AboutScreen: a route; this screen only raises the request to open it
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(
    // No default. A default would let a future call site silently keep showing
    // About as a sheet instead of navigating to the route.
    onShowAbout: () -> Unit,
    onShowLocationNotes: () -> Unit,
    onShowChatUser: (nickname: String, messageId: String) -> Unit,
    onShowLocationChannels: () -> Unit,
    onShowPeerList: () -> Unit,
    // The private chat shown over this screen, if any. It comes from the back
    // stack, and covers the moment between opening the chat and the ViewModel
    // selecting it, when the selection is still empty.
    openPrivateChatID: String?,
    // Scoped to the chat's navigation entry. The session it reads lives in the
    // Activity's retained scope, so recreating this loses nothing.
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val colorScheme = MaterialTheme.colorScheme
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onAction = viewModel::onAction
    val timeline = uiState.timeline
    val composer = uiState.composer
    val dialogs = uiState.dialogs
    val connectedPeers = timeline.connectedPeers
    val peerNicknames = timeline.peerNicknames
    val geohashPeople = timeline.geohashPeople
    val nickname = timeline.nickname
    val selectedPrivatePeer = timeline.selectedPrivatePeer
    val currentChannel = timeline.currentChannel
    val selectedLocationChannel = timeline.selectedLocationChannel

    var messageText by rememberComposerText(selectedPrivatePeer, viewModel::conversationDraft)
    var passwordInput by remember { mutableStateOf("") }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }

    // A prompt that closed, by a join or a dismissal, leaves no password behind.
    LaunchedEffect(dialogs.showPasswordPrompt) {
        if (!dialogs.showPasswordPrompt) passwordInput = ""
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            ChatEvent.MessageSent -> {
                messageText = TextFieldValue("")
                forceScrollToBottom = !forceScrollToBottom
            }
            is ChatEvent.ReplaceComposerText -> {
                messageText = TextFieldValue(
                    text = event.text,
                    selection = TextRange(event.text.length)
                )
            }
        }
    }
    val context = LocalContext.current
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val nearbyNotesController = remember { NearbyNotesController.shared }
    val liveVoiceManager = remember(context) {
        com.bitchat.android.features.voice.LiveVoiceManager.getInstance(context)
    }
    val nearbyNotesRevealed by nearbyNotesController.revealed.collectAsStateWithLifecycle()
    val locationPermissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val locationEnabled by locationManager.effectiveLocationEnabled.collectAsStateWithLifecycle(false)
    val availableLocationChannels by locationManager.availableChannels.collectAsStateWithLifecycle()
    val nearbyNotes by remember { LocationNotesManager.getInstance() }
        .notes
        .collectAsStateWithLifecycle()
    val buildingGeohash = availableLocationChannels
        .firstOrNull { it.level == GeohashChannelLevel.BUILDING }
        ?.geohash
    // The open chat wins over the selection, which trails it until the chat has
    // started and may still name a chat begun with /msg. Keyed on the selection
    // too, so the id is canonicalized again as the conversation resolves.
    val visiblePrivatePeer = remember(openPrivateChatID, selectedPrivatePeer) {
        openPrivateChatID?.let(ContactDirectory::canonicalConversationId) ?: selectedPrivatePeer
    }
    val isMeshTimeline =
        currentChannel == null &&
            selectedLocationChannel is ChannelID.Mesh &&
            visiblePrivatePeer == null

    // What live voice treats as on screen while this screen is composed: the
    // mesh timeline or a chat begun with /msg; a channel claims nothing. The
    // private chat screen claims for itself. Chat leaves composition under a
    // full screen (peer list, notes, About...) and releases its claim then, so
    // nothing it showed stays live there; under a sheet it stays composed and
    // keeps it. A release drops only this screen's claim, so a private chat
    // that claimed while chat slid out stays visible. Keyed on the selection
    // too, so it shows the mesh again once a popped private chat has ended.
    val liveVoiceOwner = remember { Any() }
    DisposableEffect(liveVoiceManager, isMeshTimeline, visiblePrivatePeer) {
        when {
            visiblePrivatePeer != null ->
                liveVoiceManager.claimDirectMessage(liveVoiceOwner, visiblePrivatePeer)
            isMeshTimeline -> liveVoiceManager.claimPublicMesh(liveVoiceOwner)
            else -> liveVoiceManager.releaseVisible(liveVoiceOwner)
        }
        onDispose { liveVoiceManager.releaseVisible(liveVoiceOwner) }
    }

    DisposableEffect(
        isMeshTimeline,
        locationEnabled,
        locationPermissionState,
        buildingGeohash,
        nearbyNotesController,
    ) {
        nearbyNotesController.updateAvailability(
            locationEnabled = locationEnabled,
            locationAuthorized =
                locationPermissionState == LocationChannelManager.PermissionState.AUTHORIZED,
            buildingGeohash = buildingGeohash,
        )
        if (isMeshTimeline) nearbyNotesController.activate()
        onDispose {
            if (isMeshTimeline) nearbyNotesController.deactivate()
        }
    }

    val displayMessages = timeline.messages
    val conversationKey = timeline.conversationKey

    val knownMentionPeers = remember(
        currentChannel,
        selectedLocationChannel,
        connectedPeers,
        peerNicknames,
        geohashPeople,
    ) {
        if (
            currentChannel == null && selectedLocationChannel is ChannelID.Location
        ) {
            val duplicateNames = duplicateGeohashBaseNames(geohashPeople)
            geohashPeople.mapNotNull { person ->
                if (isUnannouncedNickname(person.displayName)) return@mapNotNull null
                val displayName = disambiguatedGeohashDisplayName(person, duplicateNames)
                displayName to PeerIdentity.nostr(person.id)
            }
        } else {
            connectedPeers.mapNotNull { peerID ->
                peerNicknames[peerID]?.let { displayName ->
                    displayName to PeerIdentity.mesh(peerID)
                }
            }
        }
    }
    val mentionPeerIdentities = rememberMentionPeerIdentities(
        displayMessages, knownMentionPeers, conversationKey
    )

    // Determine whether to show media buttons (only hide in geohash location chats)
    val showMediaButtons = when {
        currentChannel != null -> true
        else -> selectedLocationChannel !is com.bitchat.android.geohash.ChannelID.Location
    }

    // Use WindowInsets to handle keyboard properly
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background) // Extend background to fill entire screen including status bar
    ) {
        val headerHeight = ChatHeaderHeight
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        // Both bars are translucent and the conversation scrolls underneath them, so their
        // heights are reserved as list padding instead of as layout space. The composer's height
        // varies (suggestion rows, wrapped lines), so it is measured rather than assumed.
        var composerHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11+: Handle both IME and navigation bar insets in Compose
                        Modifier.windowInsetsPadding(
                            WindowInsets.ime.union(WindowInsets.navigationBars)
                        )
                    } else {

                        // Android 10 and below: Window is resized by the system (adjustResize),
                        // so only account for the navigation bar.
                        Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    }
                )
        ) {
          Box(modifier = Modifier.weight(1f)) {
            // Messages area - takes up available space, will compress when keyboard appears
            // Nearby-notes strip and the reveal hint both live in this Box alongside the
            // list, rather than in a Column above it, because the conversation has to scroll
            // underneath the translucent bars. Their heights are reserved as list padding.
            var notesStripHeight by remember { mutableStateOf(0.dp) }
            val showNotesStrip =
                isMeshTimeline && nearbyNotesRevealed && nearbyNotes.isNotEmpty()

            MessagesList(
                messages = displayMessages,
                currentUserNickname = nickname,
                meshService = viewModel.meshService,
                mentionPeerIdentities = mentionPeerIdentities,
                modifier = Modifier.fillMaxSize(),
                conversationKey = conversationKey,
                contentPadding = PaddingValues(
                    top = statusBarHeight + headerHeight +
                        (if (showNotesStrip) notesStripHeight else 0.dp),
                    bottom = composerHeight
                ),
                forceScrollToBottom = forceScrollToBottom,
                onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                onNicknameClick = { fullSenderName ->
                    // Single click - mention user in text input
                    val currentText = messageText.text

                    // Extract base nickname and hash suffix from full sender name
                    val (baseName, hashSuffix) = splitSuffix(fullSenderName)

                    // Check if we're in a geohash channel to include hash suffix
                    val mentionText = if (
                        selectedLocationChannel is ChannelID.Location &&
                        hashSuffix.isNotEmpty()
                    ) {
                        // In geohash chat - include the hash suffix from the full display name
                        "@$baseName$hashSuffix"
                    } else {
                        // Regular chat - just the base nickname
                        "@$baseName"
                    }

                    val newText = when {
                        currentText.isEmpty() -> "$mentionText "
                        currentText.endsWith(" ") -> "$currentText$mentionText "
                        else -> "$currentText $mentionText "
                    }

                    messageText = TextFieldValue(
                        text = newText,
                        selection = TextRange(newText.length)
                    )
                },
                onMessageLongPress = { message ->
                    // Message long press - open user action sheet with message context
                    // Extract base nickname from message sender (contains all necessary info)
                    val (baseName, _) = splitSuffix(message.sender)
                    onShowChatUser(baseName, message.id)
                },
                onCancelTransfer = { msg ->
                    onAction(ChatAction.CancelMediaSend(msg.id))
                },
                onImageClick = { currentPath, allImagePaths, initialIndex ->
                    viewerImagePaths = allImagePaths
                    initialViewerIndex = initialIndex
                    showFullScreenImageViewer = true
                }
            )

            if (showNotesStrip) {
                NearbyNotesStrip(
                    noteCount = nearbyNotes.size,
                    onClick = onShowLocationNotes,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = statusBarHeight + headerHeight)
                        .onSizeChanged { size ->
                            notesStripHeight = with(density) { size.height.toDp() }
                        },
                )
            }

            // Input area - overlays the bottom of the conversation
        // Bridge file share from lower-level input to ViewModel
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.bitchat.android.ui.events.FileShareDispatcher.setHandler { peer, channel, path ->
            onAction(ChatAction.SendFileNote(peer, channel, path))
        }
    }

    ChatInputSection(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .onSizeChanged { size ->
                composerHeight = with(density) { size.height.toDp() }
            },
        messageText = messageText,
        onMessageTextChange = { newText: TextFieldValue ->
            messageText = newText
            onAction(ChatAction.ComposerTextChanged(newText.text, selectedPrivatePeer))
        },
        onSend = {
            if (messageText.text.trim().isNotEmpty()) {
                onAction(ChatAction.Send(messageText.text.trim(), selectedPrivatePeer))
            }
        },
        onSendVoiceNote = { peer, onionOrChannel, path ->
            onAction(ChatAction.SendVoiceNote(peer, onionOrChannel, path))
        },
        onSendImageNote = { peer, onionOrChannel, path ->
            onAction(ChatAction.SendImageNote(peer, onionOrChannel, path))
        },
        onSendFileNote = { peer, onionOrChannel, path ->
            onAction(ChatAction.SendFileNote(peer, onionOrChannel, path))
        },
        recorderFactory = viewModel::createVoiceRecorder,
        
        showCommandSuggestions = composer.showCommandSuggestions,
        commandSuggestions = composer.commandSuggestions,
        showMentionSuggestions = composer.showMentionSuggestions,
        mentionSuggestions = composer.mentionSuggestions,
        mentionPeerIdentities = mentionPeerIdentities,
        onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                    onAction(ChatAction.SelectCommandSuggestion(suggestion))
                },
                onMentionSuggestionClick = { mention: String ->
                    onAction(ChatAction.SelectMentionSuggestion(mention, messageText.text))
                },
                selectedPrivatePeer = null,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme,
                showMediaButtons = showMediaButtons
            )
          }
        }

        // Floating header - positioned absolutely at top, ignores keyboard
        ChatFloatingHeader(
            selectedPrivatePeer = null,
            currentChannel = currentChannel,
            nickname = nickname,
            header = uiState.header,
            onAction = onAction,
            colorScheme = colorScheme,
            onSidebarToggle = onShowPeerList,
            onShowAppInfo = onShowAbout,
            onPanicClear = { onAction(ChatAction.PanicClear) },
            onLocationChannelsClick = onShowLocationChannels,
            onLocationNotesClick = {
                nearbyNotesController.reveal()
                onShowLocationNotes()
            }
        )

        // Scroll-to-bottom floating button
        AnimatedVisibility(
            visible = isScrolledUp,
            // Short and eased: the button appears mid-scroll, so a slow entrance draws the eye
            // away from the messages the user is actually reading.
            enter = slideInVertically(
                animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 2 }
            ) + fadeIn(tween(BitchatMotion.STANDARD_MS)),
            exit = slideOutVertically(
                animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing),
                targetOffsetY = { it / 2 }
            ) + fadeOut(tween(BitchatMotion.QUICK_MS)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = composerHeight + 8.dp)
                .zIndex(1.5f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, colorScheme.primary)
            ) {
                IconButton(onClick = { forceScrollToBottom = !forceScrollToBottom }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(com.bitchat.android.feature.chat.R.string.cd_scroll_to_bottom),
                        modifier = Modifier.size(22.dp),
                        tint = colorScheme.primary
                    )
                }
            }
        }
    }

    // Full-screen image viewer - separate from other sheets to allow image browsing without navigation
    if (showFullScreenImageViewer) {
        FullScreenImageViewer(
            imagePaths = viewerImagePaths,
            initialIndex = initialViewerIndex,
            onClose = { showFullScreenImageViewer = false }
        )
    }

    // Dialogs and Sheets
    ChatDialogs(
        showPasswordDialog = dialogs.showPasswordPrompt,
        passwordPromptChannel = dialogs.passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            val channel = dialogs.passwordPromptChannel
            if (passwordInput.isNotEmpty() && channel != null) {
                onAction(ChatAction.SubmitChannelPassword(channel, passwordInput))
            }
        },
        onPasswordDismiss = { onAction(ChatAction.DismissPasswordPrompt) },
    )

    dialogs.legacyPrivateMediaConsent?.let { request ->
        AlertDialog(
            onDismissRequest = { onAction(ChatAction.CancelLegacyPrivateMedia(request.requestId)) },
            title = { Text(stringResource(com.bitchat.android.feature.chat.R.string.private_media_legacy_title)) },
            text = {
                Text(
                    stringResource(
                        com.bitchat.android.feature.chat.R.string.private_media_legacy_body,
                        request.fileName,
                        request.recipientNickname,
                        request.warning
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(ChatAction.ApproveLegacyPrivateMedia(request.requestId)) }) {
                    Text(stringResource(com.bitchat.android.feature.chat.R.string.private_media_legacy_send_once))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(ChatAction.CancelLegacyPrivateMedia(request.requestId)) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun NearbyNotesStrip(
    noteCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "📍 " + if (noteCount == 1) {
                    stringResource(R.string.nearby_notes_one)
                } else {
                    stringResource(R.string.nearby_notes_many, noteCount)
                },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
            )
            Text(
                text = "›",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    mentionPeerIdentities: Map<String, PeerIdentity> = emptyMap(),
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    showMediaButtons: Boolean,
    recorderFactory: ((String?, String?) -> com.bitchat.android.features.voice.VoiceRecorder)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activePublicTalker by remember(context) {
        com.bitchat.android.features.voice.LiveVoiceManager.getInstance(context).activePublicTalker
    }.collectAsState()
    Column(
        // Flat, slightly translucent screen background — the same treatment as the top bar, so the
        // two bars are visibly the same kind of surface. No gradient: a soft ramp here just looked
        // like a smudge above a crisp hairline. The rule is inside the background so the whole bar
        // is one surface with a top border, rather than a line floating over the conversation.
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.background.copy(alpha = BarBackgroundAlpha))
    ) {
        // Hairline marking where chrome begins. Faint on purpose — it is a hint, not a border.
        HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)

        // Command suggestions box
        if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
            CommandSuggestionsBox(
                suggestions = commandSuggestions,
                onSuggestionClick = onCommandSuggestionClick,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
        }
        // Retain the final populated list while the picker exits. The state layer clears
        // suggestions together with visibility; without this snapshot the panel would empty and
        // snap shut before its shrink/fade animation had a frame to run.
        var retainedMentionSuggestions by remember { mutableStateOf(emptyList<String>()) }
        LaunchedEffect(mentionSuggestions) {
            if (mentionSuggestions.isNotEmpty()) {
                retainedMentionSuggestions = mentionSuggestions
            }
        }
        val mentionPickerVisible = showMentionSuggestions && mentionSuggestions.isNotEmpty()
        val displayedMentionSuggestions = mentionSuggestions.ifEmpty {
            retainedMentionSuggestions
        }

        AnimatedVisibility(
            visible = mentionPickerVisible,
            enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
                expandVertically(
                    animationSpec = tween(
                        BitchatMotion.STANDARD_MS,
                        easing = FastOutSlowInEasing
                    ),
                    expandFrom = Alignment.Bottom
                ),
            exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
                shrinkVertically(
                    animationSpec = tween(
                        BitchatMotion.QUICK_MS,
                        easing = FastOutSlowInEasing
                    ),
                    shrinkTowards = Alignment.Bottom
                )
        ) {
            Column {
                MentionSuggestionsBox(
                    suggestions = displayedMentionSuggestions,
                    mentionPeerIdentities = mentionPeerIdentities,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
            }
        }
        MessageInput(
            value = messageText,
            onValueChange = onMessageTextChange,
            onSend = onSend,
            onSendVoiceNote = onSendVoiceNote,
            onSendImageNote = onSendImageNote,
            onSendFileNote = onSendFileNote,
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            showMediaButtons = showMediaButtons,
            mentionPeerIdentities = mentionPeerIdentities,
            recorderFactory = recorderFactory,
            activePublicTalker = activePublicTalker,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Opacity shared by both bars.
 *
 * Slight, so the conversation scrolling underneath stays faintly perceptible and the chrome reads
 * as sitting over the content rather than boxing it in — without ever costing legibility.
 */
private const val BarBackgroundAlpha = 0.88f

/**
 * Fraction of the header that stays fully opaque, measured from the top.
 *
 * The header is the one place a gradient earns its keep: the status bar is transparent, so the
 * header has to be the true background colour where the two meet or the system bar stops looking
 * like part of the app. Everything below that stop matches the composer's flat translucency.
 */
private const val HeaderOpaqueStop = 0.72f
@Composable
private fun ChatFloatingHeader(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    header: HeaderState,
    onAction: (ChatAction) -> Unit,
    colorScheme: ColorScheme,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val locationManager = remember { com.bitchat.android.geohash.LocationChannelManager.getInstance(context) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            // Fully opaque where it meets the system status bar, fading to translucent at its
            // lower edge. The status bar itself is transparent, so anything less than opaque at
            // the top would let the wallpaper or a light system-bar scrim bleed through and the
            // header would stop reading as part of the app.
            .background(
                Brush.verticalGradient(
                    0f to colorScheme.background,
                    HeaderOpaqueStop to colorScheme.background,
                    1f to colorScheme.background.copy(alpha = BarBackgroundAlpha)
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars) // Extend into status bar area
    ) {
        // No TopAppBar: it silently injects a 4.dp horizontal pad plus a 12.dp title inset and
        // applies its own minimum heights, which made the header's spacing impossible to specify
        // exactly. Height and edge insets belong to each header variant, so that a conversation
        // header rendered here and one rendered in a sheet are laid out identically.
        ChatHeaderContent(
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            header = header,
            // The main screen never shows a private chat's header, so the only
            // way back from here is out of a channel.
            onBackClick = { if (currentChannel != null) onAction(ChatAction.ExitChannel) },
            onSidebarClick = onSidebarToggle,
            onTripleClick = onPanicClear,
            onShowAppInfo = onShowAppInfo,
            onLocationChannelsClick = onLocationChannelsClick,
            onLocationNotesClick = {
                // Ensure location is loaded before showing sheet
                locationManager.refreshChannels()
                onLocationNotesClick()
            },
            onNicknameChange = { onAction(ChatAction.SetNickname(it)) },
            onLeaveChannel = { onAction(ChatAction.LeaveChannel(it)) },
            onOpenLatestUnreadPrivateChat = { onAction(ChatAction.OpenLatestUnreadPrivateChat) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDialogs(
    showPasswordDialog: Boolean,
    passwordPromptChannel: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirm: () -> Unit,
    onPasswordDismiss: () -> Unit,
) {
    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )
}
