package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.bitchat.android.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.launch

/**
 * A private conversation, the content of a sheet destination.
 *
 * [routeConversationID] is the id the route was opened with. The ViewModel
 * starts the chat and ends it when the entry leaves the stack, and follows
 * the conversation as it resolves to a contact.
 */
@Composable
fun PrivateChatScreen(
    routeConversationID: String,
    onShowSecurityVerification: (conversationID: String) -> Unit,
    onDismiss: () -> Unit
) {
    val viewModel = hiltViewModel<PrivateChatViewModel, PrivateChatViewModel.Factory>(
        creationCallback = { factory -> factory.create(routeConversationID) }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onAction = viewModel::onAction
    val colorScheme = MaterialTheme.colorScheme
    val peerID = uiState.conversationID
    val isNostrPeer = uiState.isNostrPeer
    val isNostrReachableFavorite = uiState.isNostrReachableFavorite
    val isFavorite = uiState.isFavorite
    val theyFavoritedUs = uiState.theyFavoritedUs

    // Celebrate being favorited: a springy wobble of the header star. Springs rather than
    // keyframed tweens, matching the app's press feedback, so the settle overshoots slightly.
    val starWobbleRotation = remember { Animatable(0f) }
    val starWobbleScale = remember { Animatable(1f) }
    var previousTheyFavoritedUs by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(theyFavoritedUs) {
        val wasFavoritedUs = previousTheyFavoritedUs
        previousTheyFavoritedUs = theyFavoritedUs
        if (theyFavoritedUs && wasFavoritedUs == false) {
            starWobbleRotation.snapTo(-16f)
            starWobbleScale.snapTo(1.35f)
            launch {
                starWobbleRotation.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessMedium)
                )
            }
            launch {
                starWobbleScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    )
                )
            }
        }
    }

    val palette = LocalBitchatPalette.current
    // Three-state star: grey outline (no relation), orange outline (they favorited us),
    // filled orange (we favorited them, mutual or not).
    val favoriteStarTint by animateColorAsState(
        targetValue = when {
            isFavorite || theyFavoritedUs -> palette.accentOrange
            else -> colorScheme.onSurfaceVariant
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "favoriteStarTint"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(modifier = Modifier.height(ChatHeaderHeight))

            HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)

            // Messages list
            var forceScrollToBottom by remember { mutableStateOf(false) }
            var isScrolledUp by remember { mutableStateOf(false) }

            // Input section state, declared here so a sent message can clear it.
            var messageText by remember(peerID) {
                mutableStateOf(
                    androidx.compose.ui.text.input.TextFieldValue(
                        viewModel.draft(peerID)
                    )
                )
            }

            ObserveAsEvents(viewModel.events) { event ->
                when (event) {
                    PrivateChatEvent.MessageSent -> {
                        messageText = androidx.compose.ui.text.input.TextFieldValue("")
                        forceScrollToBottom = !forceScrollToBottom
                    }
                }
            }

            MessagesList(
                messages = uiState.messages,
                currentUserNickname = uiState.nickname,
                meshService = viewModel.mesh,
                modifier = Modifier.weight(1f),
                conversationKey = "dm:$peerID",
                forceScrollToBottom = forceScrollToBottom,
                onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                onNicknameClick = { /* handle mention */ },
                onMessageLongPress = { /* handle long press */ },
                onCancelTransfer = { msg -> onAction(PrivateChatAction.CancelMediaSend(msg.id)) },
                onImageClick = { _, _, _ -> /* handle image click */ }
            )

            // Input section. No divider here: ChatInputSection draws its own fade and
            // hairline.
            ChatInputSection(
                messageText = messageText,
                onMessageTextChange = { newText ->
                    messageText = newText
                    onAction(PrivateChatAction.ComposerTextChanged(newText.text))
                    // Do not update the shared suggestion state here: this sheet
                    // renders its own popups as hidden, so an update only leaves
                    // a stale popup behind for the main composer.
                },
                onSend = {
                    if (messageText.text.trim().isNotEmpty()) {
                        onAction(PrivateChatAction.Send(messageText.text.trim()))
                    }
                },
                onSendVoiceNote = { peer, channel, path ->
                    onAction(PrivateChatAction.SendVoiceNote(peer, channel, path))
                },
                onSendImageNote = { peer, channel, path ->
                    onAction(PrivateChatAction.SendImageNote(peer, channel, path))
                },
                onSendFileNote = { peer, channel, path ->
                    onAction(PrivateChatAction.SendFileNote(peer, channel, path))
                },
                recorderFactory = viewModel::createVoiceRecorder,
                showCommandSuggestions = false,
                commandSuggestions = emptyList(),
                showMentionSuggestions = false,
                mentionSuggestions = emptyList(),
                onCommandSuggestionClick = { },
                onMentionSuggestionClick = { },
                selectedPrivatePeer = peerID,
                currentChannel = null,
                nickname = uiState.nickname,
                colorScheme = colorScheme,
                showMediaButtons = true
            )
        }

        // Header. Built from the same tokens as the main chat header rather than a
        // TopAppBar, so moving between the timeline and a conversation does not shift the
        // bar's height, insets or type.
        Surface(
            modifier = Modifier.align(Alignment.TopCenter),
            color = colorScheme.background
        ) {
            ConversationHeader(
                leadingIconRes = conversationTransportIcon(
                    isReachedOverInternet = isNostrPeer || isNostrReachableFavorite,
                    isWifiAware = uiState.isWifiAware,
                    isDirect = uiState.isDirect
                ),
                leadingIconTint = colorScheme.primary,
                leadingContentDescription = when {
                    isNostrPeer || isNostrReachableFavorite ->
                        stringResource(R.string.cd_nostr_reachable)
                    else -> null
                },
                title = uiState.titleText
            ) {
                ConversationHeaderAction(
                    onClick = { onAction(PrivateChatAction.ToggleFavorite) },
                    contentDescription = if (isFavorite) {
                        stringResource(R.string.cd_remove_favorite)
                    } else {
                        stringResource(R.string.cd_add_favorite)
                    }
                ) {
                    Icon(
                        painter = painterResource(
                            if (isFavorite) {
                                R.drawable.ic_spec_star_filled
                            } else {
                                R.drawable.ic_spec_star
                            }
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .size(HeaderIconSize)
                            .graphicsLayer {
                                rotationZ = starWobbleRotation.value
                                scaleX = starWobbleScale.value
                                scaleY = starWobbleScale.value
                            },
                        tint = favoriteStarTint
                    )
                }

                if (uiState.isVerified) {
                    ConversationHeaderStatus {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = stringResource(
                                R.string.fingerprint_verified_label
                            ),
                            modifier = Modifier.size(HeaderIconSize),
                            tint = colorScheme.primary
                        )
                    }
                }

                // Keep the lock nearest the close action: from right to left the security
                // cluster reads close, encryption, verification, then favorite.
                if (!isNostrPeer && !isNostrReachableFavorite) {
                    ConversationHeaderAction(
                        onClick = { onShowSecurityVerification(peerID) },
                        contentDescription = stringResource(R.string.verify_title)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            NoiseSessionIcon(
                                sessionState = uiState.sessionState,
                                modifier = Modifier.size(HeaderIconSize)
                            )
                        }
                    }
                }

                val dismiss = LocalSheetDismiss.current
                CloseButton(onClick = { dismiss?.invoke() ?: onDismiss() })
            }
        }
    }
}
