package com.bitchat.android.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.bitchat.android.model.BitchatMessage

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
    val messages by viewModel.messages.observeAsState(emptyList())
    val connectedPeers by viewModel.connectedPeers.observeAsState(emptyList())
    val nickname by viewModel.nickname.observeAsState("")
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.observeAsState()
    val currentChannel by viewModel.currentChannel.observeAsState()
    val joinedChannels by viewModel.joinedChannels.observeAsState(emptySet())
    val hasUnreadChannels by viewModel.unreadChannelMessages.observeAsState(emptyMap())
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.observeAsState(emptySet())
    val privateChats by viewModel.privateChats.observeAsState(emptyMap())
    val channelMessages by viewModel.channelMessages.observeAsState(emptyMap())
    val showSidebar by viewModel.showSidebar.observeAsState(false)
    val showCommandSuggestions by viewModel.showCommandSuggestions.observeAsState(false)
    val commandSuggestions by viewModel.commandSuggestions.observeAsState(emptyList())
    val showMentionSuggestions by viewModel.showMentionSuggestions.observeAsState(false)
    val mentionSuggestions by viewModel.mentionSuggestions.observeAsState(emptyList())
    val showAppInfo by viewModel.showAppInfo.observeAsState(false)

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var forceScrollToBottom by remember { mutableStateOf(false) }

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    val isConnected by viewModel.isConnected.observeAsState(false)
    val passwordPromptChannel by viewModel.passwordPromptChannel.observeAsState(null)

    // Determine what messages to show
    val displayMessages = when {
        selectedPrivatePeer != null -> privateChats[selectedPrivatePeer] ?: emptyList()
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> messages
    }

    // Use WindowInsets to handle keyboard properly
    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            ChatFloatingHeader(
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                viewModel = viewModel,
                colorScheme = colorScheme,
                onSidebarToggle = { viewModel.showSidebar() },
                onShowAppInfo = { viewModel.showAppInfo() },
                onPanicClear = { viewModel.panicClearAllData() },
                onLocationChannelsClick = { showLocationChannelsSheet = true }
            )

            AnimatedVisibility(
                visible = showSidebar,
                enter = slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(300, easing = EaseOutCubic)
                ) + fadeIn(animationSpec = tween(300)),
                exit = slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(250, easing = EaseInCubic)
                ) + fadeOut(animationSpec = tween(250)),
                modifier = Modifier.zIndex(2f)
            ) {
                SidebarOverlay(
                    viewModel = viewModel,
                    onDismiss = { viewModel.hideSidebar() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        content = { innerPadding ->
            // Messages area - takes up available space, will compress when keyboard appears
            MessagesList(
                messages = displayMessages,
                currentUserNickname = nickname,
                meshService = viewModel.meshService,
                forceScrollToBottom = forceScrollToBottom,
                onNicknameClick = { fullSenderName ->
                    // Single click - mention user in text input
                    val currentText = messageText.text
                    
                    // Extract base nickname and hash suffix from full sender name
                    val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                    
                    // Check if we're in a geohash channel to include hash suffix
                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                    val mentionText = if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location && hashSuffix.isNotEmpty()) {
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
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .padding(innerPadding),
            )
            // Dialogs
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
            )

            ChatSheets(
                showAppInfo = showAppInfo,
                onAppInfoDismiss = { viewModel.hideAppInfo() },
                showLocationChannelsSheet = showLocationChannelsSheet,
                onLocationChannelsSheetDismiss = { showLocationChannelsSheet = false },
                showUserSheet = showUserSheet,
                onUserSheetDismiss = { showUserSheet = false },
                selectedUserForSheet = selectedUserForSheet,
                selectedMessageForSheet = selectedMessageForSheet,
                viewModel = viewModel
            )
        },
        bottomBar = {
            // Input area - stays at bottom
            ChatInputSection(
                messageText = messageText,
                onMessageTextChange = { newText: TextFieldValue ->
                    messageText = newText
                    viewModel.updateCommandSuggestions(newText.text)
                    viewModel.updateMentionSuggestions(newText.text)
                },
                onSend = {
                    if (messageText.text.trim().isNotEmpty()) {
                        viewModel.sendMessage(messageText.text.trim())
                        messageText = TextFieldValue("")
                        forceScrollToBottom = !forceScrollToBottom // Toggle to trigger scroll
                    }
                },
                showCommandSuggestions = showCommandSuggestions,
                commandSuggestions = commandSuggestions,
                showMentionSuggestions = showMentionSuggestions,
                mentionSuggestions = mentionSuggestions,
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
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme
            )
        }
    )
}

@Composable
private fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
        // Command suggestions box
        if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
            CommandSuggestionsBox(
                suggestions = commandSuggestions,
                onSuggestionClick = onCommandSuggestionClick,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
        }

        // Mention suggestions box
        if (showMentionSuggestions && mentionSuggestions.isNotEmpty()) {
            MentionSuggestionsBox(
                suggestions = mentionSuggestions,
                onSuggestionClick = onMentionSuggestionClick,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
        }

        MessageInput(
            value = messageText,
            onValueChange = onMessageTextChange,
            onSend = onSend,
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onLocationChannelsClick: () -> Unit
) {
    TopAppBar(
        title = {
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
                onLocationChannelsClick = onLocationChannelsClick
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorScheme.background.copy(alpha = 0.95f)
        )
    )
}

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

@Composable
private fun ChatSheets(
    showAppInfo: Boolean,
    onAppInfoDismiss: () -> Unit,
    showLocationChannelsSheet: Boolean,
    onLocationChannelsSheetDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel
) {

    // App info sheet
    AppInfoBottomSheet(
        show = showAppInfo,
        onDismiss = onAppInfoDismiss
    )
    
    // Location channels sheet
    if (showLocationChannelsSheet) {
        LocationChannelsSheet(
            isPresented = showLocationChannelsSheet,
            onDismiss = onLocationChannelsSheetDismiss,
            viewModel = viewModel
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
}
