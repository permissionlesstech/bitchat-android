package com.bitchat.watch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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

    // One state drives both overlays: visible at the bottom or when scrolling toward it,
    // hidden when scrolling up into history. The 24px (~12dp) threshold is deliberately
    // small so the controls answer every flick immediately.
    var atNewest by remember { mutableStateOf(true) }
    val controlsVisible = remember { mutableStateOf(true) }
    LaunchedEffect(columnState) {
        var lastPosition = -1
        snapshotFlow {
            val first = columnState.layoutInfo.visibleItems.firstOrNull()
            Triple(columnState.canScrollForward, first?.index ?: 0, first?.offset ?: 0)
        }.collect { (canScrollForward, index, offset) ->
            val position = index * 100_000 + offset
            atNewest = !canScrollForward
            if (!canScrollForward) {
                controlsVisible.value = true
            } else if (lastPosition >= 0) {
                when {
                    position > lastPosition + 24 -> controlsVisible.value = true
                    position < lastPosition - 24 -> controlsVisible.value = false
                }
            }
            lastPosition = position
        }
    }

    // Stick to bottom: follow new messages while resting at the newest.
    // Capture this when the message count changes, before the new layout can temporarily make
    // canScrollForward true and report that the user is browsing history.
    val followNewest = remember(messages.size) { atNewest }
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
        ScreenScaffold(scrollState = columnState) {
            TransformingLazyColumn(
                state = columnState,
                modifier = Modifier.fillMaxSize(),
                // Arrangement.Bottom anchors short content to the bottom: the first message
                // starts just above the action bar and new messages push history upward.
                // The padding reserves permanent room for the floating header and action
                // bar; being constant, it never disturbs an in-flight scroll gesture.
                verticalArrangement = Arrangement.Bottom,
                contentPadding = PaddingValues(top = 30.dp, bottom = 64.dp)
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
        Box(modifier = Modifier.align(Alignment.TopCenter)) {
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
// Magnetic zone geometry (px at watch density): the button starts reacting at
// MAGNET_OUTER_PX from its center and fully blushes at MAGNET_INNER_PX (~the activation
// boundary); it leans toward the finger by up to MAGNET_PULL_PX.
private const val MAGNET_OUTER_PX = 170f
private const val MAGNET_INNER_PX = 104f
private const val MAGNET_PULL_PX = 22f
