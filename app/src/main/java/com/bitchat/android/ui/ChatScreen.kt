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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.theme.BitchatMotion

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - AboutSheet: App info and password prompts
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val showCommandSuggestions by viewModel.showCommandSuggestions.collectAsStateWithLifecycle()
    val commandSuggestions by viewModel.commandSuggestions.collectAsStateWithLifecycle()
    val showMentionSuggestions by viewModel.showMentionSuggestions.collectAsStateWithLifecycle()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val showAppInfo by viewModel.showAppInfo.collectAsStateWithLifecycle()
    val showMeshPeerListSheet by viewModel.showMeshPeerList.collectAsStateWithLifecycle()
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()
    val showVerificationSheet by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerificationSheet by viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()
    val legacyPrivateMediaConsent by viewModel.legacyPrivateMediaConsent.collectAsStateWithLifecycle()

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }
    var showLocationNotesSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPrivatePeer) {
        messageText = TextFieldValue(
            selectedPrivatePeer
                ?.let(viewModel::conversationDraft)
                .orEmpty()
        )
    }

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val passwordPromptChannel by viewModel.passwordPromptChannel.collectAsStateWithLifecycle()

    // Get location channel info for timeline switching
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
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
    val isMeshTimeline =
        currentChannel == null &&
            selectedLocationChannel is ChannelID.Mesh &&
            selectedPrivatePeer == null &&
            privateChatSheetPeer == null

    val processLifecycleOwner = remember { ProcessLifecycleOwner.get() }
    DisposableEffect(processLifecycleOwner, nearbyNotesController) {
        val lifecycle = processLifecycleOwner.lifecycle
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                nearbyNotesController.updateAppForeground(true)
                liveVoiceManager.setAppForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                nearbyNotesController.updateAppForeground(false)
                liveVoiceManager.setAppForeground(false)
            }
        }

        lifecycle.addObserver(observer)
        nearbyNotesController.updateAppForeground(
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )

        onDispose {
            lifecycle.removeObserver(observer)
            nearbyNotesController.updateAppForeground(false)
            liveVoiceManager.setAppForeground(false)
        }
    }

    LaunchedEffect(isMeshTimeline, privateChatSheetPeer, selectedPrivatePeer) {
        when {
            privateChatSheetPeer != null -> liveVoiceManager.showDirectMessage(privateChatSheetPeer!!)
            selectedPrivatePeer != null -> liveVoiceManager.showDirectMessage(selectedPrivatePeer!!)
            isMeshTimeline -> liveVoiceManager.showPublicMesh()
            else -> liveVoiceManager.clearVisibleConversation()
        }
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

    // Determine what messages to show based on current context (unified timelines)
    // Legacy private chat timeline removed - private chats now exclusively use PrivateChatSheet
    val displayMessages = when {
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> {
            val locationChannel = selectedLocationChannel
            if (locationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                val geokey = "geo:${locationChannel.channel.geohash}"
                channelMessages[geokey] ?: emptyList()
            } else {
                messages // Mesh timeline
            }
        }
    }

    // Identity of the timeline on screen, derived exactly like displayMessages above. Drives the
    // per-conversation scroll position and animation state in MessagesList.
    val conversationKey = when {
        currentChannel != null -> "channel:$currentChannel"
        else -> {
            val locationChannel = selectedLocationChannel
            if (locationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                "geo:${locationChannel.channel.geohash}"
            } else {
                "mesh"
            }
        }
    }

    val mentionPeerIdentities = remember(
        displayMessages,
        currentChannel,
        selectedLocationChannel,
        connectedPeers,
        peerNicknames,
        geohashPeople,
    ) {
        val knownPeers = if (
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
        buildMentionPeerIdentityMap(displayMessages, knownPeers)
    }

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
                meshService = viewModel.meshServiceFacade,
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
                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
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
                    selectedUserForSheet = baseName
                    selectedMessageForSheet = message
                    showUserSheet = true
                },
                onCancelTransfer = { msg ->
                    viewModel.cancelMediaSend(msg.id)
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
                    onClick = { showLocationNotesSheet = true },
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
            viewModel.sendFileNote(peer, channel, path)
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
            viewModel.setConversationDraft(selectedPrivatePeer, newText.text)
            viewModel.updateCommandSuggestions(newText.text)
            viewModel.updateMentionSuggestions(newText.text)
        },
        onSend = {
            if (messageText.text.trim().isNotEmpty()) {
                viewModel.sendMessage(messageText.text.trim()) { accepted ->
                    if (accepted) {
                        messageText = TextFieldValue("")
                        viewModel.setConversationDraft(selectedPrivatePeer, "")
                        // Clearing the field in code does not run onMessageTextChange,
                        // so the popups have to be dismissed here.
                        viewModel.clearSuggestions()
                        forceScrollToBottom = !forceScrollToBottom
                    }
                }
            }
        },
        onSendVoiceNote = { peer, onionOrChannel, path ->
            viewModel.sendVoiceNote(peer, onionOrChannel, path)
        },
        onSendImageNote = { peer, onionOrChannel, path ->
            viewModel.sendImageNote(peer, onionOrChannel, path)
        },
        onSendFileNote = { peer, onionOrChannel, path ->
            viewModel.sendFileNote(peer, onionOrChannel, path)
        },
        recorderFactory = viewModel::createVoiceRecorder,
        
        showCommandSuggestions = showCommandSuggestions,
        commandSuggestions = commandSuggestions,
        showMentionSuggestions = showMentionSuggestions,
        mentionSuggestions = mentionSuggestions,
        mentionPeerIdentities = mentionPeerIdentities,
        onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                    val commandText = viewModel.selectCommandSuggestion(suggestion)
                    messageText = TextFieldValue(
                        text = commandText,
                        selection = TextRange(commandText.length)
                    )
                },
                onMentionSuggestionClick = { mention: String ->
                    val mentionText = viewModel.selectMentionSuggestion(mention, messageText.text)
                    messageText = TextFieldValue(
                        text = mentionText,
                        selection = TextRange(mentionText.length)
                    )
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
            viewModel = viewModel,
            colorScheme = colorScheme,
            onSidebarToggle = { viewModel.showMeshPeerList() },
            onShowAppInfo = { viewModel.showAppInfo() },
            onPanicClear = { viewModel.panicClearAllData() },
            onLocationChannelsClick = { showLocationChannelsSheet = true },
            onLocationNotesClick = {
                nearbyNotesController.reveal()
                showLocationNotesSheet = true
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
                        contentDescription = stringResource(com.bitchat.android.R.string.cd_scroll_to_bottom),
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
        showPasswordDialog = showPasswordDialog,
        passwordPromptChannel = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            if (passwordInput.isNotEmpty()) {
                val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                if (success) {
                    showPasswordDialog = false
                    passwordInput = ""
                }
            }
        },
        onPasswordDismiss = {
            showPasswordDialog = false
            passwordInput = ""
        },
        showAppInfo = showAppInfo,
        onAppInfoDismiss = { viewModel.hideAppInfo() },
        showLocationChannelsSheet = showLocationChannelsSheet,
        onLocationChannelsSheetDismiss = { showLocationChannelsSheet = false },
        onLocationNotesFromChannelsClick = {
            showLocationChannelsSheet = false
            showLocationNotesSheet = true
        },
        showLocationNotesSheet = showLocationNotesSheet,
        onLocationNotesSheetDismiss = { showLocationNotesSheet = false },
        showUserSheet = showUserSheet,
        onUserSheetDismiss = { 
            showUserSheet = false
            selectedMessageForSheet = null // Reset message when dismissing
        },
        selectedUserForSheet = selectedUserForSheet,
        selectedMessageForSheet = selectedMessageForSheet,
        viewModel = viewModel,
        showVerificationSheet = showVerificationSheet,
        onVerificationSheetDismiss = viewModel::hideVerificationSheet,
        showSecurityVerificationSheet = showSecurityVerificationSheet,
        onSecurityVerificationSheetDismiss = viewModel::hideSecurityVerificationSheet,
        showMeshPeerListSheet = showMeshPeerListSheet,
        onMeshPeerListDismiss = viewModel::hideMeshPeerList,
    )

    legacyPrivateMediaConsent?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelLegacyPrivateMedia(request.requestId) },
            title = { Text(stringResource(com.bitchat.android.R.string.private_media_legacy_title)) },
            text = {
                Text(
                    stringResource(
                        com.bitchat.android.R.string.private_media_legacy_body,
                        request.fileName,
                        request.recipientNickname,
                        request.warning
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.approveLegacyPrivateMedia(request.requestId) }) {
                    Text(stringResource(com.bitchat.android.R.string.private_media_legacy_send_once))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelLegacyPrivateMedia(request.requestId) }) {
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
    viewModel: ChatViewModel,
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
            viewModel = viewModel,
            onBackClick = {
                when {
                    selectedPrivatePeer != null -> viewModel.endPrivateChat()
                    currentChannel != null -> viewModel.switchToChannel(null)
                }
            },
            onSidebarClick = onSidebarToggle,
            onTripleClick = onPanicClear,
            onShowAppInfo = onShowAppInfo,
            onLocationChannelsClick = onLocationChannelsClick,
            onLocationNotesClick = {
                // Ensure location is loaded before showing sheet
                locationManager.refreshChannels()
                onLocationNotesClick()
            }
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
    showAppInfo: Boolean,
    onAppInfoDismiss: () -> Unit,
    showLocationChannelsSheet: Boolean,
    onLocationChannelsSheetDismiss: () -> Unit,
    onLocationNotesFromChannelsClick: () -> Unit,
    showLocationNotesSheet: Boolean,
    onLocationNotesSheetDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel,
    showVerificationSheet: Boolean,
    onVerificationSheetDismiss: () -> Unit,
    showSecurityVerificationSheet: Boolean,
    onSecurityVerificationSheetDismiss: () -> Unit,
    showMeshPeerListSheet: Boolean,
    onMeshPeerListDismiss: () -> Unit,
) {
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()

    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )

    // About sheet
    var showDebugSheet by remember { mutableStateOf(false) }
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = onAppInfoDismiss,
        onShowDebug = { showDebugSheet = true }
    )
    if (showDebugSheet) {
        com.bitchat.android.ui.debug.DebugSettingsSheet(
            isPresented = showDebugSheet,
            onDismiss = { showDebugSheet = false },
            meshService = viewModel.meshService
        )
    }
    
    // Location channels sheet
    if (showLocationChannelsSheet) {
        LocationChannelsSheet(
            isPresented = showLocationChannelsSheet,
            onDismiss = onLocationChannelsSheetDismiss,
            onLocationNotesClick = onLocationNotesFromChannelsClick,
            viewModel = viewModel
        )
    }
    
    // Location notes sheet (extracted to separate presenter)
    if (showLocationNotesSheet) {
        LocationNotesSheetPresenter(
            viewModel = viewModel,
            onDismiss = onLocationNotesSheetDismiss
        )
    }
    
    // User action sheet
    if (showUserSheet) {
        ChatUserSheet(
            isPresented = showUserSheet,
            onDismiss = onUserSheetDismiss,
            targetNickname = selectedUserForSheet,
            selectedMessage = selectedMessageForSheet,
            viewModel = viewModel
        )
    }
    // MeshPeerList sheet (network view)
    if (showMeshPeerListSheet){
        MeshPeerListSheet(
            isPresented = showMeshPeerListSheet,
            viewModel = viewModel,
            onDismiss = onMeshPeerListDismiss,
            onShowVerification = {
                onMeshPeerListDismiss()
                viewModel.showVerificationSheet(fromSidebar = true)
            }
        )
    }

    if (showVerificationSheet) {
        VerificationSheet(
            isPresented = showVerificationSheet,
            onDismiss = onVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (showSecurityVerificationSheet) {
        SecurityVerificationSheet(
            isPresented = showSecurityVerificationSheet,
            onDismiss = onSecurityVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (privateChatSheetPeer != null) {
        PrivateChatSheet(
            isPresented = true,
            peerID = privateChatSheetPeer!!,
            viewModel = viewModel,
            onDismiss = {
                viewModel.hidePrivateChatSheet()
                viewModel.endPrivateChat()
            }
        )
    }
}
