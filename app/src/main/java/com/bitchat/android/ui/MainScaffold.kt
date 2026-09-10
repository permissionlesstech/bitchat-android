package com.bitchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.ui.theme.BitchatFontFamily

/**
 * The app's four destinations, in bar order.
 *
 * `Public` is the mesh/geohash timeline the app used to be entirely made of; the other three were
 * modal bottom sheets before this became a tabbed app.
 */
enum class AppTab(
    val labelRes: Int,
    val iconRes: Int? = null,
    val iconVector: ImageVector? = null
) {
    Public(R.string.tab_public, iconRes = R.drawable.ic_spec_globe),
    Chats(R.string.tab_chats, iconRes = R.drawable.ic_spec_envelope),
    People(R.string.people, iconRes = R.drawable.ic_spec_people),
    Settings(R.string.about_tab_settings, iconVector = Icons.Outlined.Settings)
}

/**
 * Tab shell hosting the whole app.
 *
 * A `when` over a saveable enum rather than a NavHost: four fixed destinations sharing one
 * ChatViewModel need no route DSL, no argument encoding and no graph. The one nested destination -
 * an open conversation - is a nullable peer id held by the Chats tab.
 */
@Composable
fun MainScaffold(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    // Public leads the bar and is the default: a first-run user has no conversations, and the mesh
    // timeline is the thing the app exists for.
    var tab by rememberSaveable { mutableStateOf(AppTab.Public) }

    // Which conversation is open is the ViewModel's business, not the shell's: notification taps,
    // deep links and alias re-resolution all set it from outside compose. The shell only reads it.
    val openConversation by viewModel.openPrivateChatPeer.collectAsStateWithLifecycle()

    val openConversationFor: (String) -> Unit = { peerID ->
        viewModel.openPrivateChat(peerID)
        tab = AppTab.Chats
    }

    // Closing a conversation is already handled by the activity's back callback via
    // ChatViewModel.handleBackPressed, so this only covers the remaining case: returning to the
    // default tab before back is allowed to leave the app.
    BackHandler(enabled = openConversation == null && tab != AppTab.Public) {
        tab = AppTab.Public
    }

    val inConversation = openConversation != null

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The screens below draw their own status-bar treatment (the chat header fades into it),
        // so the Scaffold must not pad the top. The bottom bar still reports its own height.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = !inConversation,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                BitchatNavigationBar(
                    viewModel = viewModel,
                    selected = tab,
                    onSelect = { tab = it }
                )
            }
        }
    ) { innerPadding ->
        // consumeWindowInsets is what keeps the bar from being counted twice: without it the
        // screens' own navigationBars/ime padding would stack on top of the space the Scaffold
        // already reserved for the bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            val peer = openConversation
            if (peer != null) {
                PrivateChatContent(
                    peerID = peer,
                    viewModel = viewModel,
                    onBack = viewModel::endPrivateChat
                )
            } else {
                when (tab) {
                    AppTab.Chats -> TabScreen(title = stringResource(R.string.conversations)) {
                        ChatsListContent(
                            viewModel = viewModel,
                            onOpenConversation = openConversationFor,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                        )
                    }

                    AppTab.Public -> ChatScreen(
                        viewModel = viewModel,
                        onOpenPeopleTab = { tab = AppTab.People }
                    )

                    AppTab.People -> TabScreen(
                        title = stringResource(R.string.your_network),
                        actions = {
                            IconButton(
                                onClick = { viewModel.showVerificationSheet() },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCode,
                                    contentDescription = stringResource(R.string.verify_title),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    ) {
                        PeopleListContent(
                            viewModel = viewModel,
                            onOpenConversation = openConversationFor,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                        )
                    }

                    AppTab.Settings -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        AboutContent(
                            initialTab = AboutTab.Settings,
                            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
                        )
                    }
                }
            }
        }

        AppDialogs(viewModel)
    }
}

/**
 * Surfaces that can be opened from more than one tab, hosted by the shell rather than by a tab.
 *
 * These used to live inside ChatScreen. Once ChatScreen became one tab among four, anything
 * declared there only existed while that tab was on screen - so tapping the QR button in People
 * flipped the ViewModel flag but showed nothing until you happened to navigate to Public, where
 * the sheet was waiting.
 */
@Composable
private fun AppDialogs(viewModel: ChatViewModel) {
    val showAppInfo by viewModel.showAppInfo.collectAsStateWithLifecycle()
    val showVerification by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerification by
        viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()
    val showPasswordPrompt by viewModel.showPasswordPrompt.collectAsStateWithLifecycle()
    val passwordPromptChannel by viewModel.passwordPromptChannel.collectAsStateWithLifecycle()

    var passwordInput by remember { mutableStateOf("") }
    var showDebugSheet by remember { mutableStateOf(false) }

    PasswordPromptDialog(
        show = showPasswordPrompt,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onConfirm = {
            val channel = passwordPromptChannel
            if (passwordInput.isNotEmpty() && channel != null) {
                if (viewModel.joinChannel(channel, passwordInput)) passwordInput = ""
            }
        },
        onDismiss = {
            passwordInput = ""
            viewModel.dismissPasswordPrompt()
        }
    )

    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = viewModel::hideAppInfo,
        onShowDebug = { showDebugSheet = true }
    )
    if (showDebugSheet) {
        com.bitchat.android.ui.debug.DebugSettingsSheet(
            isPresented = true,
            onDismiss = { showDebugSheet = false },
            meshService = viewModel.meshService
        )
    }

    if (showVerification) {
        VerificationSheet(
            isPresented = true,
            onDismiss = viewModel::hideVerificationSheet,
            viewModel = viewModel
        )
    }

    if (showSecurityVerification) {
        SecurityVerificationSheet(
            isPresented = true,
            onDismiss = viewModel::hideSecurityVerificationSheet,
            viewModel = viewModel
        )
    }
}

/**
 * Chrome for the list tabs: a title bar built from the same tokens as the chat header, so switching
 * tabs does not shift the bar's height, insets or type.
 */
@Composable
private fun TabScreen(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatHeaderHeight)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = BitchatFontFamily,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.primary
                )
                actions()
            }
            HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun BitchatNavigationBar(
    viewModel: ChatViewModel,
    selected: AppTab,
    onSelect: (AppTab) -> Unit
) {
    val hasUnreadPrivate by viewModel.hasUnreadPrivateMessages.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.hasUnreadChannels.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerCount = connectedPeers.count { it != viewModel.myPeerID }

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        AppTab.entries.forEach { entry ->
            val label = stringResource(entry.labelRes)
            NavigationBarItem(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                icon = {
                    BadgedBox(
                        badge = {
                            when (entry) {
                                AppTab.Chats -> if (hasUnreadPrivate) Badge()
                                AppTab.Public -> if (hasUnreadChannels) Badge()
                                AppTab.People -> if (peerCount > 0) {
                                    Badge { Text(peerCount.toString()) }
                                }
                                AppTab.Settings -> Unit
                            }
                        }
                    ) {
                        if (entry.iconVector != null) {
                            Icon(
                                imageVector = entry.iconVector,
                                contentDescription = label,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(entry.iconRes!!),
                                contentDescription = label,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                },
                label = {
                    Text(
                        text = label,
                        fontFamily = BitchatFontFamily,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
