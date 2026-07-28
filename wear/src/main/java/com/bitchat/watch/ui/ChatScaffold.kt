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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val haptics = LocalHapticFeedback.current
    val columnState = rememberTransformingLazyColumnState()

    // Haptics on incoming messages.
    var previousCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousCount) {
            val last = messages.lastOrNull()
            if (last != null && last.senderPeerID != myPeerID) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
    LaunchedEffect(columnState, messages.size) {
        if (messages.isNotEmpty() && atNewest) {
            // scrollBy to the end of the range: animateScrollToItem stops as soon as the
            // item is partially visible, which left the last message cropped.
            columnState.scroll { scrollBy(Float.MAX_VALUE) }
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
    val transformationSpec = rememberTransformationSpec()
    Box(modifier = modifier.fillMaxSize()) {
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

        VoiceRecordOverlay(voice)
    }
}
