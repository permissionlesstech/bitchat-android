package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.text.AnnotatedClickableText
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.media.FileMessageItem
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.ChatVisualTokens
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.MessageBodyTextStyle
import com.bitchat.android.ui.theme.MessageSenderTextStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale


// VoiceNotePlayer moved to com.bitchat.android.ui.media.VoiceNotePlayer

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

/** How far a newly arrived message travels up into place. */
private val MessageEntrySlide = 14.dp

/**
 * Entry motion for a new message: quick, with just enough damping to settle rather than snap.
 * Runs entirely on a graphics layer, so it costs a transform and nothing else.
 */
private val MessageEntrySpec: AnimationSpec<Float> =
    spring(dampingRatio = 0.85f, stiffness = 1200f)

/**
 * Motion for messages being pushed out of the way by an arrival. Softer than the entry so the
 * conversation glides up while the new message itself lands crisply.
 */
private val MessagePlacementSpec: FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

/** Removals are not worth dwelling on. */
private val MessageFadeOutSpec: FiniteAnimationSpec<Float> = tween(BitchatMotion.QUICK_MS)

/**
 * How long placement animation stays armed after the list gains or loses a message.
 *
 * Comfortably longer than [MessagePlacementSpec] takes to settle, so an arrival's push is never cut
 * short.
 */
private const val PlacementArmWindowMs = 600L

/**
 * Above this many simultaneous arrivals, entry animations are skipped.
 *
 * A history sync or a channel switch can append hundreds of messages in one frame. Animating each
 * would spend the entire frame budget on motion nobody asked to see, so a burst is adopted
 * silently and only conversational-pace arrivals animate.
 */
internal const val MaxAnimatedArrivals = 6

/**
 * Remembers which message ids have already been seen, so genuine arrivals can be told apart from
 * items merely scrolling back into view.
 *
 * This distinction is the whole reason the entry animation is usable: `LazyColumn` composes items
 * on demand, so animating on first composition would replay the animation for every old message
 * the user scrolled back to.
 */
internal class MessageArrivalTracker {
    val known = HashSet<String>()
    var seeded = false
}

/**
 * Ids that should animate in on this composition pass.
 *
 * Deliberately computed during composition rather than in a `LaunchedEffect`: effects run *after*
 * the frame's composition, by which point a new message's item has already composed and would
 * have missed its cue.
 */
internal fun MessageArrivalTracker.arrivals(messages: List<BitchatMessage>): Set<String> {
    if (!seeded) {
        // First load adopts everything silently. A whole screenful animating on open reads as a
        // glitch, not a flourish.
        messages.forEach { known.add(it.id) }
        seeded = true
        return emptySet()
    }

    // A list with nothing in common with the last one is a different conversation, not a burst of
    // arrivals — /clear, or a switch the caller did not give us a distinct key for. Adopt it
    // silently rather than sliding in every message at once.
    val isWholesaleReplacement =
        messages.isNotEmpty() && known.isNotEmpty() && messages.none { it.id in known }

    // `HashSet.add` reports whether the id was new, so this both diffs and updates in one pass.
    val added = messages.filter { known.add(it.id) }

    if (known.size > messages.size) {
        // Messages disappeared (/clear, channel switch). Drop the stale ids so the set cannot
        // grow without bound and so re-added messages animate again.
        known.retainAll(messages.mapTo(HashSet(messages.size)) { it.id })
    }

    return when {
        isWholesaleReplacement -> emptySet()
        added.isEmpty() || added.size > MaxAnimatedArrivals -> emptySet()
        else -> added.mapTo(HashSet(added.size)) { it.id }
    }
}

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: MeshService,
    modifier: Modifier = Modifier,
    mentionPeerIdentities: Map<String, PeerIdentity>? = null,
    /**
     * Extra inset on top of the list's own gutters.
     *
     * The chat screen's bars are translucent and the list scrolls underneath them, so the caller
     * has to reserve room for their heights here rather than by shrinking the viewport.
     */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * Identity of the conversation being shown — a channel, a geohash, a peer.
     *
     * Everything below that is per-conversation state is keyed on this. Without it, switching
     * channels reused the previous conversation's scroll offset, follow flag and seen-message set,
     * so the new channel opened at a stale position and then animated itself into place.
     */
    conversationKey: Any? = null,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val resolvedMentionPeerIdentities = remember(messages, mentionPeerIdentities) {
        mentionPeerIdentities ?: buildMentionPeerIdentityMap(messages)
    }

    // A fresh scroll position per conversation. Sharing one state meant a switch inherited the
    // previous channel's offset and then had to correct itself, which is what the jump was.
    //
    // Passing the key as an *input* rather than as `key =` is deliberate: it discards the saved
    // offset on every switch, so a conversation always opens on its newest message instead of
    // wherever the reader happened to be some time ago, with unseen messages below them.
    val listState = rememberSaveable(conversationKey, saver = LazyListState.Saver) {
        LazyListState()
    }

    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember(conversationKey) { mutableStateOf(false) }
    var followIncomingMessages by remember(conversationKey) { mutableStateOf(true) }
    
    // Smart scroll: auto-scroll to bottom for initial load, then follow unless user scrolls away
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val isFirstLoad = !hasScrolledToInitialPosition
            if (isFirstLoad || followIncomingMessages) {
                listState.scrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }
    
    // Track whether user has scrolled away from the latest messages
    val isAtLatest by remember(listState) {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        followIncomingMessages = isAtLatest
        onScrolledUpChanged?.invoke(!isAtLatest)
    }
    
    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            // With reverseLayout=true and reversed data, latest is at index 0
            followIncomingMessages = true
            listState.scrollToItem(0)
        }
    }
    
    // Recomputed only when the list actually gains or loses a message, and synchronously, so the
    // arriving item can read its cue during the same composition pass in which it first appears.
    // Reset per conversation, so a switch adopts the incoming messages silently instead of
    // treating a whole channel's backlog as brand-new arrivals and sliding each one in.
    val arrivalTracker = remember(conversationKey) { MessageArrivalTracker() }
    val enteringIds = remember(conversationKey, messages.size, messages.lastOrNull()?.id) {
        arrivalTracker.arrivals(messages)
    }

    // Placement animation exists to soften insertions and removals. But *any* relayout moves every
    // item — the keyboard opening behind a bottom sheet, that sheet closing again, the composer
    // growing a line — and animating those made the whole conversation lurch. So it is armed only
    // briefly around a genuine change to the list, and is otherwise off, letting items track the
    // viewport exactly.
    var placementArmed by remember(conversationKey) { mutableStateOf(false) }
    var previousMessageCount by remember(conversationKey) { mutableStateOf<Int?>(null) }
    LaunchedEffect(conversationKey, messages.size) {
        val previous = previousMessageCount
        previousMessageCount = messages.size
        // Skip the first composition: the list settling into its initial padding is not a change
        // worth animating.
        if (previous == null || previous == messages.size) return@LaunchedEffect
        placementArmed = true
        delay(PlacementArmWindowMs)
        placementArmed = false
    }

    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        state = listState,
        // Wider side gutters than the old 12.dp: the redesign trades a little line length for
        // a much calmer edge, and long monospace lines were running into the screen bezel.
        contentPadding = PaddingValues(
            start = 16.dp + contentPadding.calculateStartPadding(layoutDirection),
            end = 16.dp + contentPadding.calculateEndPadding(layoutDirection),
            top = 8.dp + contentPadding.calculateTopPadding(),
            bottom = 12.dp + contentPadding.calculateBottomPadding()
        ),
        // Spacing is owned by each item. The exported transcript uses a consistent 8.dp rhythm;
        // a new speaker gets additional separation from the visible sender row's top inset.
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = modifier,
        reverseLayout = true
    ) {
        val reversed = messages.asReversed()
        itemsIndexed(
            items = reversed,
            key = { _, message -> message.id }
        ) { reversedIndex, message ->
            // reverseLayout renders index 0 at the bottom, so the chronological predecessor of
            // this row lives at a *higher* original index offset. Resolve against the original
            // list rather than the reversed view to keep the grouping logic readable.
            val originalIndex = messages.lastIndex - reversedIndex
            val previous = messages.getOrNull(originalIndex - 1)
            val isGrouped = MessageGrouping.shouldGroup(previous, message)

            // Decided once per item instance, so an item recycling back into view during a scroll
            // never re-animates. Items that are not arriving skip the animation machinery
            // entirely: no Animatable, no coroutine, and no extra render layer per row.
            val isArriving = remember(message.id) { message.id in enteringIds }
            val entryModifier = if (isArriving) {
                val entry = remember(message.id) { Animatable(0f) }
                LaunchedEffect(message.id) { entry.animateTo(1f, MessageEntrySpec) }
                // A draw-time transform only: no measure, no layout, and no recomposition of the
                // message content on any frame of the animation.
                Modifier.graphicsLayer {
                    val progress = entry.value
                    alpha = progress
                    translationY = (1f - progress) * MessageEntrySlide.toPx()
                }
            } else {
                Modifier
            }

            MessageItem(
                message = message,
                messages = messages,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                mentionPeerIdentities = resolvedMentionPeerIdentities,
                showSender = !isGrouped,
                topSpacing = MessageGrouping.topSpacingFor(
                    isGrouped = isGrouped,
                    isFirstInList = originalIndex == 0
                ),
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                onImageClick = onImageClick,
                modifier = Modifier
                    // Animates the shift when a neighbour is inserted or removed: this is what
                    // makes the conversation glide up instead of jumping.
                    .animateItem(
                        // Entry fade is handled by entryModifier, together with the slide, so the
                        // two cannot drift out of step.
                        fadeInSpec = null,
                        placementSpec = if (placementArmed) MessagePlacementSpec else null,
                        fadeOutSpec = MessageFadeOutSpec
                    )
                    .then(entryModifier)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    messages: List<BitchatMessage> = emptyList(),
    mentionPeerIdentities: Map<String, PeerIdentity> = emptyMap(),
    showSender: Boolean = true,
    topSpacing: Dp = 0.dp,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat(CHAT_TIMESTAMP_PATTERN, Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topSpacing),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                // Provide a small end padding for own private messages so overlay doesn't cover text
                val endPad = if (message.isPrivate && message.sender == currentUserNickname) 16.dp else 0.dp
                // Create a custom layout that combines selectable text with clickable nickname areas
                MessageTextWithClickableNicknames(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    mentionPeerIdentities = mentionPeerIdentities,
                    colorScheme = colorScheme,
                    timeFormatter = timeFormatter,
                    showSender = showSender,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = endPad)
                )
            }

            // Delivery status for private messages (overlay, non-displacing)
            if (message.isPrivate && message.sender == currentUserNickname) {
                message.deliveryStatus?.let { status ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp)
                    ) {
                        DeliveryStatusIcon(status = status)
                    }
                }
            }
        }
        
        // Link previews removed; links are now highlighted inline and clickable within the message text
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
    private fun MessageTextWithClickableNicknames(
        message: BitchatMessage,
        messages: List<BitchatMessage>,
        currentUserNickname: String,
        meshService: MeshService,
        mentionPeerIdentities: Map<String, PeerIdentity>,
        colorScheme: ColorScheme,
        timeFormatter: SimpleDateFormat,
        showSender: Boolean,
        onNicknameClick: ((String) -> Unit)?,
        onMessageLongPress: ((BitchatMessage) -> Unit)?,
        onCancelTransfer: ((BitchatMessage) -> Unit)?,
        onImageClick: ((String, List<String>, Int) -> Unit)?,
        modifier: Modifier = Modifier
    ) {
    val palette = LocalBitchatPalette.current

    // Image special rendering
    if (message.type == BitchatMessageType.Image) {
        com.bitchat.android.ui.media.ImageMessageItem(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            showSender = showSender,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            onImageClick = onImageClick,
            modifier = modifier
        )
        return
    }

    // Voice note special rendering
    if (message.type == BitchatMessageType.Audio) {
        com.bitchat.android.ui.media.AudioMessageItem(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            showSender = showSender,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            modifier = modifier
        )
        return
    }

    // File special rendering
    if (message.type == BitchatMessageType.File) {
        val path = message.content.trim()
        // Derive sending progress if applicable
        val (overrideProgress, _) = when (val st = message.deliveryStatus) {
            is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered -> {
                if (st.total > 0 && st.reached < st.total) {
                    (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5) // blue while sending
                } else null to null
            }
            else -> null to null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // Header: nickname + timestamp line above the file, identical styling to text messages
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                myPeerID = meshService.myPeerID,
                palette = palette,
                contentColor = colorScheme.onSurface,
                timeFormatter = timeFormatter,
                includeSender = showSender
            )
            val haptic = LocalHapticFeedback.current
            AnnotatedClickableText(
                text = headerText,
                annotationTags = listOf("nickname_click"),
                onAnnotationClick = { tag, item ->
                    if (tag == "nickname_click" && onNicknameClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(item)
                        true
                    } else {
                        false
                    }
                },
                onLongPress = { onMessageLongPress?.invoke(message) },
                fontFamily = BitchatFontFamily,
                color = colorScheme.onSurface,
            )

            // Try to load the file packet from the path
            val packet = try {
                val file = java.io.File(path)
                if (file.exists()) {
                    // Create a temporary BitchatFilePacket for display
                    // In a real implementation, this would be stored with the packet metadata
                    com.bitchat.android.model.BitchatFilePacket(
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                        content = file.readBytes()
                    )
                } else null
            } catch (e: Exception) {
                null
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (packet != null) {
                        if (overrideProgress != null) {
                            // Show sending animation while in-flight
                            com.bitchat.android.ui.media.FileSendingAnimation(
                                fileName = packet.fileName,
                                progress = overrideProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // Static file display with open/save dialog
                            FileMessageItem(
                                packet = packet,
                                onFileClick = {
                                    // handled inside FileMessageItem via dialog
                                }
                            )
                        }

                        // Cancel button overlay during sending
                        val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
                        if (showCancel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(colorScheme.surfaceVariant.copy(alpha = 0.85f), CircleShape)
                                    .clickable { onCancelTransfer?.invoke(message) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_cancel),
                                    tint = colorScheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.file_unavailable),
                            fontFamily = BitchatFontFamily,
                            color = palette.textTertiary
                        )
                    }
                }
            }
        }
        return
    }

    if (message.sender == "system") {
        // Background narration: `// Tor started. Routing all chats…`
        val annotatedText = remember(message, colorScheme.onSurface) {
            formatSystemMessage(
                message = message,
                contentColor = colorScheme.onSurface,
                timeFormatter = timeFormatter
            )
        }

        val haptic = LocalHapticFeedback.current
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = BitchatFontFamily,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = ChatVisualTokens.SystemActionStyle.copy(
                color = colorScheme.onSurface.copy(alpha = ChatVisualTokens.MutedTextAlpha),
            )
        )
    } else {
        TextMessageLayout(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            mentionPeerIdentities = mentionPeerIdentities,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            showSender = showSender,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            modifier = modifier,
        )
    }
}

@Composable
internal fun TextMessageLayout(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    mentionPeerIdentities: Map<String, PeerIdentity> = emptyMap(),
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
    showSender: Boolean = true,
    bodyContent: String = message.content,
) {
    val palette = LocalBitchatPalette.current
    val myPeerId = meshService.myPeerID
    val displayMessage = remember(message, bodyContent) {
        if (bodyContent == message.content) message else message.copy(content = bodyContent)
    }
    val senderText = remember(message, currentUserNickname, myPeerId, palette) {
        formatTextMessageSender(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = myPeerId,
            palette = palette,
        )
    }
    // The timestamp trails the body rather than occupying its own column, so a short message
    // no longer reserves a full-width row for eight grey characters.
    val bodyText = remember(
        displayMessage,
        currentUserNickname,
        palette,
        colorScheme.onSurface,
        colorScheme.secondary,
        mentionPeerIdentities,
        timeFormatter
    ) {
        formatTextMessageBody(
            message = displayMessage,
            currentUserNickname = currentUserNickname,
            palette = palette,
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
            mentionPeerIdentities = mentionPeerIdentities,
            timeFormatter = timeFormatter,
        )
    }
    val isSelf = message.isFromSelf(currentUserNickname, myPeerId)
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val handleLongPress: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onMessageLongPress?.invoke(message)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MessageGrouping.SENDER_TO_BODY_SPACING),
    ) {
        if (showSender) {
            AnnotatedClickableText(
                text = senderText,
                annotationTags = listOf("nickname_click"),
                onAnnotationClick = { tag, item ->
                    if (tag == "nickname_click" && !isSelf && onNicknameClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(item)
                        true
                    } else {
                        false
                    }
                },
                onLongPress = handleLongPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MessageGrouping.SENDER_TOP_PADDING),
                fontFamily = BitchatFontFamily,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MessageSenderTextStyle,
            )
        }

        AnnotatedClickableText(
            text = bodyText,
            annotationTags = listOf("geohash_click", "url_click"),
            onAnnotationClick = { tag, item ->
                when (tag) {
                    "geohash_click" -> {
                        navigateToGeohash(context, item)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        true
                    }

                    "url_click" -> {
                        openMessageUrl(context, item)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        true
                    }

                    else -> false
                }
            },
            onLongPress = handleLongPress,
            fontFamily = BitchatFontFamily,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = MessageBodyTextStyle.copy(color = colorScheme.onSurface),
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme

    // Status advances on its own as acks come back, so a hard glyph swap reads as a flicker.
    // Keyed on the status *type* rather than the instance, because Delivered/Read carry a
    // timestamp that would otherwise retrigger the transition on every identical update.
    AnimatedContent(
        targetState = status::class,
        transitionSpec = {
            fadeIn(tween(BitchatMotion.STANDARD_MS)) togetherWith
                fadeOut(tween(BitchatMotion.QUICK_MS))
        },
        label = "deliveryStatus"
    ) { statusClass ->
        val (text, color, weight) = when (statusClass) {
            DeliveryStatus.Sending::class ->
                Triple(R.string.status_sending, colorScheme.primary.copy(alpha = 0.6f), FontWeight.Normal)
            // Subtle hollow marker for Sent; a single check is reserved for Delivered (iOS parity).
            DeliveryStatus.Sent::class ->
                Triple(R.string.status_pending, colorScheme.primary.copy(alpha = 0.6f), FontWeight.Normal)
            DeliveryStatus.Delivered::class ->
                Triple(R.string.status_sent, colorScheme.primary.copy(alpha = 0.8f), FontWeight.Normal)
            DeliveryStatus.Read::class ->
                Triple(R.string.status_delivered, colorScheme.secondary, FontWeight.Bold)
            DeliveryStatus.Failed::class ->
                Triple(R.string.status_failed, colorScheme.error, FontWeight.Normal)
            // A single subdued check, without the numeric label.
            else ->
                Triple(R.string.status_sent, colorScheme.primary.copy(alpha = 0.6f), FontWeight.Normal)
        }

        Text(
            text = stringResource(text),
            fontSize = 10.sp,
            color = color,
            fontWeight = weight
        )
    }
}
