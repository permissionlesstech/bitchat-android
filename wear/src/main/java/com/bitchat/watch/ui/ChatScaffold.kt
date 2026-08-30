package com.bitchat.watch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.bitchat.android.model.BitchatMessage
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.math.sign

/**
 * The shared chat body for global chat and DM threads, following the classic messenger
 * pattern: a TransformingLazyColumn message list (native Wear center-scaling/fade, rotary,
 * scrollbar) with the header and action bar as floating overlays that get out of the way
 * while scrolling up into history and return on any downward scroll; at the newest message
 * they are always visible.
 *
 * The list's contentPadding is CONSTANT and both overlays are layout-neutral, so showing or
 * hiding them never changes the scroll geometry. Earlier revisions animated the bottom
 * clearance and resized the header in the layout path, which shifted content under the
 * user's finger mid-gesture (felt as "resistance") and fed back into the dock detection.
 */
@Composable
fun ChatScaffold(
    messages: List<BitchatMessage>,
    myPeerID: String,
    emptyText: String,
    voice: VoiceNoteController,
    onOpenImage: (String) -> Unit,
    header: @Composable (expanded: Boolean) -> Unit,
    actionBar: @Composable () -> Unit
) {
    val columnState = rememberTransformingLazyColumnState()

    // Haptics on incoming messages.
    val context = LocalContext.current
    var previousCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousCount) {
            val last = messages.lastOrNull()
            if (last != null && last.senderPeerID != myPeerID) {
                WearHaptics.knock(context)
            }
        }
        previousCount = messages.size
    }

    // Follow intent is changed only by an actual user scroll away from the newest item or by
    // reaching the end again. A new item temporarily makes canScrollForward true before layout;
    // treating that transient range change as user intent breaks automatic following.
    var followNewest by remember { mutableStateOf(true) }
    val controlsVisible = remember { mutableStateOf(true) }
    LaunchedEffect(columnState) {
        var lastPosition = -1
        var scrollIntent = ChatScrollIntentState()
        snapshotFlow {
            val first = columnState.layoutInfo.visibleItems.firstOrNull()
            ChatScrollSnapshot(
                canScrollForward = columnState.canScrollForward,
                isScrollInProgress = columnState.isScrollInProgress,
                position = (first?.index ?: 0) * 100_000 + (first?.offset ?: 0)
            )
        }.collect { snapshot ->
            scrollIntent = updatedChatScrollIntent(
                current = scrollIntent,
                snapshot = snapshot,
                previousPosition = lastPosition
            )
            followNewest = scrollIntent.followsNewest
            controlsVisible.value = scrollIntent.controlsVisible
            lastPosition = snapshot.position
        }
    }

    // Stick to bottom when the user has not intentionally moved into history.
    LaunchedEffect(columnState, messages.size) {
        if (messages.isNotEmpty() && followNewest) {
            val expectedSingleMessageKey = messages.singleOrNull()?.id
            scrollToNewestAfterItemsMeasured(
                expectedItemCount = messages.size,
                expectedSingleMessageKey = expectedSingleMessageKey,
                measuredLayouts = snapshotFlow {
                    val layoutInfo = columnState.layoutInfo
                    MeasuredChatLayout(
                        itemCount = layoutInfo.totalItemsCount,
                        singleVisibleItemKey = if (expectedSingleMessageKey != null) {
                            layoutInfo.visibleItems.singleOrNull()?.key
                        } else {
                            null
                        }
                    )
                }
            ) {
                // scrollBy to the end of the range: animateScrollToItem stops as soon as the
                // item is partially visible, which left the last message cropped.
                columnState.scroll { scrollBy(Float.MAX_VALUE) }
            }
        }
    }

    ChatBody(
        messages = messages,
        myPeerID = myPeerID,
        emptyText = emptyText,
        voice = voice,
        onOpenImage = onOpenImage,
        columnState = columnState,
        controlsVisible = controlsVisible.value,
        header = header,
        actionBar = actionBar,
        modifier = Modifier.fillMaxSize()
    )
}

internal data class MeasuredChatLayout(
    val itemCount: Int,
    val singleVisibleItemKey: Any?
)

internal data class ChatScrollSnapshot(
    val canScrollForward: Boolean,
    val isScrollInProgress: Boolean,
    val position: Int
)

internal data class ChatScrollIntentState(
    val followsNewest: Boolean = true,
    val controlsVisible: Boolean = true,
    val accumulatedDeltaPx: Int = 0
)

internal fun updatedChatScrollIntent(
    current: ChatScrollIntentState,
    snapshot: ChatScrollSnapshot,
    previousPosition: Int
): ChatScrollIntentState {
    if (!snapshot.canScrollForward) return ChatScrollIntentState()
    if (!snapshot.isScrollInProgress || previousPosition < 0) return current

    val delta = snapshot.position - previousPosition
    val accumulatedDelta = when {
        delta == 0 -> current.accumulatedDeltaPx
        current.accumulatedDeltaPx == 0 ||
            current.accumulatedDeltaPx.sign == delta.sign ->
            current.accumulatedDeltaPx + delta
        else -> delta
    }
    val movedAway = accumulatedDelta <= -CHAT_SCROLL_DIRECTION_THRESHOLD_PX
    val movedTowardNewest = accumulatedDelta >= CHAT_SCROLL_DIRECTION_THRESHOLD_PX

    return current.copy(
        followsNewest = current.followsNewest && !movedAway,
        controlsVisible = when {
            movedAway -> false
            movedTowardNewest -> true
            else -> current.controlsVisible
        },
        // Keep sub-threshold movement across discrete rotary events. Once intent is clear,
        // start a fresh accumulator so reversing direction gets the same threshold treatment.
        accumulatedDeltaPx = if (movedAway || movedTowardNewest) 0 else accumulatedDelta
    )
}

internal suspend fun scrollToNewestAfterItemsMeasured(
    expectedItemCount: Int,
    expectedSingleMessageKey: Any?,
    measuredLayouts: Flow<MeasuredChatLayout>,
    scrollToEnd: suspend () -> Unit
) {
    measuredLayouts.first { layout ->
        layout.itemCount >= expectedItemCount &&
            (expectedSingleMessageKey == null ||
                layout.singleVisibleItemKey == expectedSingleMessageKey)
    }
    scrollToEnd()
}

@Composable
private fun ChatBody(
    messages: List<BitchatMessage>,
    myPeerID: String,
    emptyText: String,
    voice: VoiceNoteController,
    onOpenImage: (String) -> Unit,
    columnState: TransformingLazyColumnState,
    controlsVisible: Boolean,
    header: @Composable (expanded: Boolean) -> Unit,
    actionBar: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val context = LocalContext.current
    val transformationSpec = rememberTransformationSpec()
    val isScreenRound = LocalConfiguration.current.isScreenRound
    // Slide-to-cancel: while recording, the finger's position is tracked globally; the
    // overlay's mic button reports its bounds and becomes the cancel target when the
    // finger hovers it (with generous slack so the snap engages on approach).
    var cancelBounds by remember { mutableStateOf<Rect?>(null) }
    var fingerPos by remember { mutableStateOf(Offset.Zero) }
    var fingerActive by remember { mutableStateOf(false) }
    val hoveringCancel = fingerActive &&
        cancelBounds?.inflate(CANCEL_HOVER_SLANT_PX)?.contains(fingerPos) == true

    // Magnetic attraction: as the finger approaches the target (but is not on it yet), the
    // button leans toward the finger and blushes red in proportion to the closeness;
    // only actually entering the activation zone snaps it into full cancel mode.
    val cancelCenter = cancelBounds?.center
    val proximity: Float
    val magnetPull: Offset
    if (fingerActive && cancelCenter != null) {
        val toFinger = fingerPos - cancelCenter
        val dist = toFinger.getDistance()
        proximity = ((MAGNET_OUTER_PX - dist) / (MAGNET_OUTER_PX - MAGNET_INNER_PX))
            .coerceIn(0f, 1f)
        magnetPull = if (dist > 1f) toFinger * (proximity * MAGNET_PULL_PX / dist)
        else Offset.Zero
    } else {
        proximity = 0f
        magnetPull = Offset.Zero
    }

    // Tactile tick each time the finger enters or leaves the cancel target.
    var hoverHapticState by remember { mutableStateOf(false) }
    LaunchedEffect(hoveringCancel, voice.recording) {
        if (!voice.recording) {
            hoverHapticState = false
        } else if (hoveringCancel != hoverHapticState) {
            WearHaptics.tick(context)
            hoverHapticState = hoveringCancel
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Push-to-talk release is tracked globally: once recording, lifting the finger
            // ANYWHERE on the screen stops — sending, or cancelling when hovering the
            // cancel target. On a 1.4" round screen it is too easy to drift off the small
            // mic button (the scrollable parent steals the pointer mid-drag), so the
            // button alone must not own the release.
            .pointerInput(voice) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!voice.recording) continue
                        val change = event.changes.firstOrNull() ?: continue
                        fingerPos = change.position
                        fingerActive = true
                        if (event.changes.any { it.changedToUp() }) {
                            val cancel = cancelBounds
                                ?.inflate(CANCEL_HOVER_SLANT_PX)
                                ?.contains(change.position) == true
                            fingerActive = false
                            if (cancel) {
                                WearHaptics.reject(context)
                                voice.stop(send = false)
                            } else {
                                voice.stop(send = true)
                            }
                        }
                    }
                }
            }
    ) {
        ScreenScaffold(scrollState = columnState) { scaffoldPadding ->
            val layoutDirection = LocalLayoutDirection.current
            TransformingLazyColumn(
                state = columnState,
                // Keep the list full-screen and geometrically unclipped. Wear's transformation
                // spec curves rows along the round display; a soft destination-alpha mask then
                // makes them fully transparent at the physical edges instead of cutting glyphs.
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithCache {
                        val edgeMask = if (isScreenRound) {
                            // Fade toward the actual circular contour so glyphs become
                            // transparent before the panel can crop their left or right edge.
                            Brush.radialGradient(
                                0f to Color.Black,
                                CHAT_ROUND_EDGE_OPAQUE_STOP to Color.Black,
                                1f to Color.Transparent,
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.minDimension / 2f
                            )
                        } else {
                            val topOpaqueStop =
                                (CHAT_HEADER_EDGE_FADE.toPx() / size.height).coerceIn(0f, 1f)
                            val bottomOpaqueStop =
                                (1f - CHAT_ACTION_BAR_EDGE_FADE.toPx() / size.height)
                                    .coerceIn(topOpaqueStop, 1f)
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                topOpaqueStop to Color.Black,
                                bottomOpaqueStop to Color.Black,
                                1f to Color.Transparent
                            )
                        }
                        onDrawWithContent {
                            drawContent()
                            drawRect(
                                brush = edgeMask,
                                blendMode = BlendMode.DstIn
                            )
                        }
                    },
                // Arrangement.Bottom anchors short content to the bottom: the first message
                // starts just above the action bar and new messages push history upward.
                // The scroll range reserves resting space for the floating controls while the
                // full-screen viewport preserves autoscroll and the native transformation focal
                // point. Rows may travel behind the overlays only after they have begun the Wear
                // edge scale/fade treatment.
                verticalArrangement = Arrangement.Bottom,
                // Keep Wear Material's responsive horizontal inset while replacing its vertical
                // inset with the overlay clearances used before the shape fix. This avoids both
                // duplicated padding and a shortened list viewport.
                contentPadding = scaffoldPadding.withVerticalClearance(
                    layoutDirection = layoutDirection,
                    top = CHAT_HEADER_CONTENT_CLEARANCE,
                    bottom = CHAT_ACTION_BAR_CLEARANCE
                )
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = emptyText,
                            style = ChatVisualTokens.SystemActionStyle,
                            color = palette.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 48.dp)
                        )
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        myPeerID = myPeerID,
                        onOpenImage = onOpenImage,
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec)
                            .graphicsLayer {
                                with(transformationSpec) {
                                    applyContainerTransformation(scrollProgress)
                                }
                            }
                    )
                }
            }
        }

        // The header stays put and shrinks to its dense form instead of disappearing;
        // as an overlay its size animation never touches the list's scroll geometry.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            header(controlsVisible)
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(BitchatMotion.STANDARD_MS)
            ) + fadeIn(animationSpec = tween(BitchatMotion.STANDARD_MS)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(BitchatMotion.STANDARD_MS)
            ) + fadeOut(animationSpec = tween(BitchatMotion.STANDARD_MS))
        ) {
            Box(modifier = Modifier.padding(bottom = 10.dp)) {
                actionBar()
            }
        }

        VoiceRecordOverlay(
            voice = voice,
            hoveringCancel = hoveringCancel,
            proximity = proximity,
            magnetPull = magnetPull,
            onCancelBounds = { cancelBounds = it }
        )
    }
}

// Extra finger slack (px, ~28dp at watch density) around the cancel target so the snap
// engages as the finger approaches, not only on exact contact.
private const val CANCEL_HOVER_SLANT_PX = 56f
private const val CHAT_SCROLL_DIRECTION_THRESHOLD_PX = 24
private val CHAT_HEADER_CONTENT_CLEARANCE = 30.dp
private val CHAT_ACTION_BAR_CLEARANCE = 64.dp
private val CHAT_HEADER_EDGE_FADE = 36.dp
private val CHAT_ACTION_BAR_EDGE_FADE = 72.dp
private const val CHAT_ROUND_EDGE_OPAQUE_STOP = 0.78f
// Magnetic zone geometry (px at watch density): the button starts reacting at
// MAGNET_OUTER_PX from its center and fully blushes at MAGNET_INNER_PX (~the activation
// boundary); it leans toward the finger by up to MAGNET_PULL_PX.
private const val MAGNET_OUTER_PX = 170f
private const val MAGNET_INNER_PX = 104f
private const val MAGNET_PULL_PX = 22f
