package com.bitchat.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import com.bitchat.android.ui.theme.ChatUiModeManager
import com.bitchat.android.ui.theme.ChatVisualTokens
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.MessageBodyTextStyle
import com.bitchat.android.ui.theme.MessageSenderTextStyle
import com.bitchat.android.ui.theme.colorForPeer
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

    // Collected once here so individual rows never subscribe to the preference flow; a mode
    // switch simply recomposes the list against the new layout.
    val bubbles by ChatUiModeManager.modeFlow.collectAsState()

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
                bubbles = bubbles.isBubbles,
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
    bubbles: Boolean = false,
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
                // Provide a small end padding for own private messages so overlay doesn't cover text.
                // Bubble mode draws the status beneath the bubble instead, so no inset is needed.
                val endPad = if (!bubbles && message.isPrivate && message.sender == currentUserNickname) 16.dp else 0.dp
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
                    bubbles = bubbles,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = endPad)
                )
            }

            // Delivery status for private messages (overlay, non-displacing). Bubble mode aligns
            // own messages to the end edge where this overlay lives, so it renders below instead.
            if (!bubbles && message.isPrivate && message.sender == currentUserNickname) {
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

        // Bubble mode: text and media bubbles carry the marker inline, trailing the timestamp.
        // File rows have no bubble shell, so their marker stays beneath the end-aligned row.
        if (bubbles && message.type == BitchatMessageType.File &&
            message.isPrivate && message.sender == currentUserNickname
        ) {
            message.deliveryStatus?.let { status ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, end = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    DeliveryStatusIcon(status = status)
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
        bubbles: Boolean = false,
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
            bubbles = bubbles,
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
            bubbles = bubbles,
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
        Column(
            modifier = modifier.fillMaxWidth(),
            // Bubble mode aligns self-authored file rows to the end side, mirroring text bubbles.
            horizontalAlignment = if (bubbles && message.isFromSelf(currentUserNickname, meshService.myPeerID)) {
                Alignment.End
            } else {
                Alignment.Start
            },
        ) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (bubbles && message.isFromSelf(currentUserNickname, meshService.myPeerID)) {
                    Arrangement.End
                } else {
                    Arrangement.Start
                }
            ) {
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

    val cashuTokens = remember(message.content) {
        CashuTokenDecoder.extractTokens(message.content)
    }
    if (cashuTokens.isNotEmpty() && message.sender != "system") {
        CashuMessageContent(
            message = message,
            tokens = cashuTokens,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            bubbles = bubbles,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            modifier = modifier
        )
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
            bubbles = bubbles,
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
    bubbles: Boolean = false,
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
    val isSelf = message.isFromSelf(currentUserNickname, myPeerId)
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val handleLongPress: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onMessageLongPress?.invoke(message)
    }

    // The timestamp trails the body rather than occupying its own column, so a short message
    // no longer reserves a full-width row for eight grey characters. Self bubbles leave the
    // timestamp to their meta cluster (see BubbleTextMessageLayout).
    val bodyText = remember(
        displayMessage,
        currentUserNickname,
        palette,
        colorScheme.onSurface,
        colorScheme.secondary,
        mentionPeerIdentities,
        timeFormatter,
        bubbles,
        isSelf
    ) {
        formatTextMessageBody(
            message = displayMessage,
            currentUserNickname = currentUserNickname,
            palette = palette,
            contentColor = colorScheme.onSurface,
            linkColor = colorScheme.secondary,
            mentionPeerIdentities = mentionPeerIdentities,
            timeFormatter = timeFormatter,
            includeTimestamp = !bubbles || !isSelf,
        )
    }

    if (bubbles) {
        BubbleTextMessageLayout(
            message = message,
            senderText = senderText,
            bodyText = bodyText,
            isSelf = isSelf,
            showSender = showSender,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onLongPress = handleLongPress,
            modifier = modifier,
        )
        return
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

/**
 * Classic messenger rendering of a text message: a rounded bubble that hugs its content, own
 * messages on the right and everyone else on the left, with the corner on the speaker's side
 * tightened into a subtle tail.
 *
 * The bubble is washed with the author's stable peer colour — the same identity-derived colour
 * the `@name` label and mention chips already use — so the speaker stays identifiable at a
 * glance without touching any surface, background, or theme colour. Body text keeps the
 * standard `onSurface` tone; only the bubble shell carries the identity.
 */
@Composable
private fun BubbleTextMessageLayout(
    message: BitchatMessage,
    senderText: AnnotatedString,
    bodyText: AnnotatedString,
    isSelf: Boolean,
    showSender: Boolean,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBitchatPalette.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val authorColor = remember(message, isSelf, palette) {
        if (isSelf) palette.accentOrange else colorForPeer(peerIdentityForMessage(message), palette)
    }

    val corner = ChatVisualTokens.BubbleCornerRadius
    val tail = ChatVisualTokens.BubbleTailRadius
    val bubbleShape = if (isSelf) {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomEnd = tail, bottomStart = corner)
    } else {
        RoundedCornerShape(topStart = corner, topEnd = corner, bottomEnd = corner, bottomStart = tail)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
    ) {
        // Cap the bubble at a fraction of the row so long messages wrap instead of touching the
        // opposite edge, while short ones hug their content.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxBubbleWidth = maxWidth * ChatVisualTokens.BubbleMaxWidthFraction
            val density = LocalDensity.current
            val textCapPx = with(density) {
                (maxBubbleWidth - ChatVisualTokens.BubblePaddingHorizontal * 2 - 2.dp).toPx()
            }
            var bodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            var clusterSize by remember { mutableStateOf(IntSize.Zero) }

            // The meta cluster (timestamp, then delivery checks for own private messages) rides
            // flush-right on the body's last line when that line has room for it, and drops
            // below the text only when it does not. Placement is computed from the laid-out
            // text, so wrapping is never influenced by the cluster: no early wraps, no slack
            // carved out of the first lines, no minimum bubble width.
            val metaGapPx = with(density) { 8.dp.toPx() }
            val metaPlan = remember(bodyLayout, clusterSize, textCapPx) {
                val layout = bodyLayout ?: return@remember null
                if (layout.lineCount == 0 || clusterSize.width <= 0) return@remember null
                val lastLineRight = layout.getLineRight(layout.lineCount - 1)
                if (lastLineRight + metaGapPx + clusterSize.width <= textCapPx) {
                    BubbleMetaPlan(
                        widthPx = maxOf(layout.size.width.toFloat(), lastLineRight + metaGapPx + clusterSize.width),
                        reserveOwnLine = false,
                    )
                } else {
                    BubbleMetaPlan(
                        widthPx = maxOf(layout.size.width.toFloat(), clusterSize.width.toFloat()),
                        reserveOwnLine = true,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(if (isSelf) Alignment.CenterEnd else Alignment.CenterStart)
                    .widthIn(max = maxBubbleWidth)
                    .border(
                        width = 1.dp,
                        color = authorColor.copy(alpha = ChatVisualTokens.BubbleBorderAlpha),
                        shape = bubbleShape
                    )
                    .background(
                        color = authorColor.copy(alpha = ChatVisualTokens.BubbleBackgroundAlpha),
                        shape = bubbleShape
                    )
                    .padding(
                        horizontal = ChatVisualTokens.BubblePaddingHorizontal,
                        vertical = ChatVisualTokens.BubblePaddingVertical,
                    )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // The sender's name heads the first bubble of their run, like classic group
                    // messengers, instead of floating above it. Own bubbles never show a name —
                    // the end side is attribution enough. Continuation bubbles skip it too.
                    if (showSender && !isSelf) {
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
                            onLongPress = onLongPress,
                            fontFamily = BitchatFontFamily,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            style = MessageSenderTextStyle,
                        )
                    }

                    Box(
                        modifier = if (isSelf && metaPlan != null) {
                            Modifier.width(with(density) { metaPlan!!.widthPx.toDp() })
                        } else {
                            Modifier
                        }
                    ) {
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
                            onLongPress = onLongPress,
                            modifier = Modifier.padding(
                                bottom = if (metaPlan?.reserveOwnLine == true) {
                                    with(density) { clusterSize.height.toDp() }
                                } else {
                                    0.dp
                                }
                            ),
                            fontFamily = BitchatFontFamily,
                            softWrap = true,
                            overflow = TextOverflow.Visible,
                            style = MessageBodyTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                            onTextLayout = { bodyLayout = it },
                        )

                        if (isSelf) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .onSizeChanged { clusterSize = it }
                                    .graphicsLayer { alpha = if (metaPlan != null) 1f else 0f },
                            ) {
                                Text(
                                    text = formatTextMessageMetadata(message, timeFormatter),
                                    fontFamily = BitchatFontFamily,
                                )
                                if (message.isPrivate) {
                                    message.deliveryStatus?.let { status ->
                                        Spacer(Modifier.width(4.dp))
                                        DeliveryStatusIcon(status = status)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class BubbleMetaPlan(
    val widthPx: Float,
    val reserveOwnLine: Boolean,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CashuMessageContent(
    message: BitchatMessage,
    tokens: List<String>,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    bubbles: Boolean = false,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val remainingText = tokens.fold(message.content) { text, token ->
        text.replace("cashu://$token", "", ignoreCase = true)
            .replace("cashu:$token", "", ignoreCase = true)
            .replace(token, "")
    }.trim()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextMessageLayout(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            bubbles = bubbles,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            bodyContent = remainingText,
        )
        tokens.forEach { token -> CashuPaymentChip(token) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CashuPaymentChip(
    token: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showActions: Boolean = true,
) {
    val context = LocalContext.current
    val info = remember(token) { CashuTokenDecoder.decode(token) }
    val primaryLabel = listOfNotNull(info?.displayAmount, info?.mintHost)
        .joinToString(" · ")
        .ifEmpty { stringResource(R.string.cashu_pay_via) }
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = modifier
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .background(
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    RoundedCornerShape(12.dp)
                )
                .combinedClickable(
                    onClick = onClick ?: { redeemCashu(context, token, preferWallet = true) },
                    onLongClick = if (showActions) {
                        { showMenu = true }
                    } else {
                        null
                    }
                )
                .semantics {
                    contentDescription = buildString {
                        append(context.getString(R.string.cashu_payment_description))
                        append(": ")
                        append(primaryLabel)
                        info?.memo?.let { append(", $it") }
                    }
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🥜")
            Column {
                Text(
                    primaryLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                info?.memo?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        DropdownMenu(
            expanded = showActions && showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cashu_copy_token)) },
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cashu token", token))
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cashu_redeem_wallet)) },
                onClick = {
                    showMenu = false
                    redeemCashu(context, token, preferWallet = true)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cashu_redeem_web)) },
                onClick = {
                    showMenu = false
                    redeemCashu(context, token, preferWallet = false)
                }
            )
        }
    }
}

private fun redeemCashu(context: Context, token: String, preferWallet: Boolean) {
    val wallet = CashuTokenDecoder.walletUri(token)
    val web = CashuTokenDecoder.webRedeemUri(token) ?: return
    if (preferWallet && wallet != null) {
        val walletIntent = Intent(Intent.ACTION_VIEW, Uri.parse(wallet))
        try {
            context.startActivity(walletIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // No wallet registered for cashu:, so use the explicit web fallback.
        }
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web))) }
}

/**
 * Per-check target colours for the delivery marker.
 *
 * Both checks always render — grey (disabled) until an acknowledgement turns them on — so a
 * status change recolours in place and never reflows text around it. Read receipts use the
 * app's primary green rather than a separate accent. [status] == null yields the all-grey
 * baseline used while a message is still being sent.
 */
private fun deliveryCheckColors(status: DeliveryStatus?, colorScheme: ColorScheme): Pair<Color, Color> {
    val grey = colorScheme.onSurface.copy(alpha = 0.35f)
    val green = colorScheme.primary
    return when (status) {
        is DeliveryStatus.Read -> green to green
        is DeliveryStatus.Delivered -> green to grey
        is DeliveryStatus.PartiallyDelivered -> green to grey
        is DeliveryStatus.Failed -> colorScheme.error to colorScheme.error
        else -> grey to grey
    }
}

/** Acknowledgement progress ordering, used to fire the pop only when the state advances. */
private fun deliveryCheckRank(status: DeliveryStatus): Int = when (status) {
    is DeliveryStatus.Read -> 3
    is DeliveryStatus.Delivered -> 2
    is DeliveryStatus.PartiallyDelivered -> 2
    is DeliveryStatus.Failed -> 1
    else -> 0
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    val (firstTarget, secondTarget) = deliveryCheckColors(status, colorScheme)
    val first by animateColorAsState(
        targetValue = firstTarget,
        animationSpec = tween(BitchatMotion.QUICK_MS),
        label = "firstCheckColor",
    )
    val second by animateColorAsState(
        targetValue = secondTarget,
        animationSpec = tween(BitchatMotion.QUICK_MS),
        label = "secondCheckColor",
    )

    // Snappy micro pop when the state advances to (more) acknowledged. Keyed on the rank, not
    // the instance, because Delivered/Read carry timestamps that would retrigger it otherwise.
    val scale = remember { Animatable(1f) }
    LaunchedEffect(deliveryCheckRank(status)) {
        if (deliveryCheckRank(status) >= 2) {
            scale.snapTo(1.3f)
            scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 900f))
        }
    }

    val text = remember(first, second) {
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = first))
            append("✓")
            pop()
            pushStyle(androidx.compose.ui.text.SpanStyle(color = second))
            append("✓")
            pop()
        }
    }
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    )
}
