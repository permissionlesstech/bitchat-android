package com.bitchat.watch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * The shared chat body for global chat and DM threads: sticky collapsing header (via [header]),
 * TransformingLazyColumn message list (native Wear center-scaling/fade, rotary, scrollbar),
 * floating scroll-aware action bar, and the push-to-talk overlay.
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
    val palette = LocalBitchatPalette.current
    val haptics = LocalHapticFeedback.current
    val columnState = rememberTransformingLazyColumnState()

    // Distance in px from the viewport's bottom edge to the end of the last item;
    // Int.MAX_VALUE when the last message is not visible at all.
    fun bottomDist(): Int {
        if (messages.isEmpty()) return 0
        val info = columnState.layoutInfo
        val lastVisible = info.visibleItems.lastOrNull() ?: return Int.MAX_VALUE
        if (lastVisible.index < messages.size - 1) return Int.MAX_VALUE
        val viewportH = info.viewportSize.height
        return viewportH - (lastVisible.offset + lastVisible.transformedHeight)
    }

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

    // "Docked at newest" is the single source of truth for the chat's resting state:
    // bottom clearance expanded (newest message sits above the floating buttons), action
    // bar visible, header at full size. Scrolling into history collapses all three together;
    // returning to the bottom restores them. Thresholds 40/120 straddle the 48dp padding
    // delta, breaking the maxValue feedback loop, and give the header/buttons flicker-free
    // hysteresis.
    var dockedAtNewest by remember { mutableStateOf(true) }
    // Action bar hides while scrolling into history, returns toward the newest.
    val buttonsVisible = remember { mutableStateOf(true) }
    LaunchedEffect(columnState, messages.size) {
        var lastPosition = 0
        snapshotFlow {
            val first = columnState.layoutInfo.visibleItems.firstOrNull()
            (first?.index ?: 0) * 100_000 + (first?.offset ?: 0)
        }.collect { position ->
            val dist = bottomDist()
            if (dist < 40) {
                dockedAtNewest = true
                buttonsVisible.value = true
            } else if (dist in 121..10_000) {
                // Clearly reading history (MAX_VALUE = last item not laid out yet; transient
                // right after a new message arrives, so it must not undock the state).
                dockedAtNewest = false
            }
            when {
                dist >= 40 && position < lastPosition - 24 -> buttonsVisible.value = false
                position > lastPosition + 24 -> buttonsVisible.value = true
            }
            lastPosition = position
        }
    }

    // Stick to bottom: on new messages, follow to the last item while the user is docked at
    // the newest, and re-align whenever the bottom clearance expands (padding growth changes
    // the scroll range). Gating on dockedAtNewest (maintained by the layout collector above)
    // avoids the stale-layoutInfo race of computing the distance here directly.
    LaunchedEffect(columnState, messages.size, dockedAtNewest) {
        if (messages.isNotEmpty() && dockedAtNewest) {
            // scrollBy to the end of the range: animateScrollToItem stops as soon as the item
            // is partially visible, which left the last message cropped behind the buttons.
            columnState.scroll { scrollBy(Float.MAX_VALUE) }
        }
    }
    val listBottomPadding by animateDpAsState(
        targetValue = if (dockedAtNewest) 56.dp else 8.dp,
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "listBottomPad"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        header(dockedAtNewest)
        ChatBody(
            messages = messages,
            myPeerID = myPeerID,
            emptyText = emptyText,
            voice = voice,
            onOpenImage = onOpenImage,
            columnState = columnState,
            listBottomPadding = listBottomPadding,
            buttonsVisible = buttonsVisible.value,
            actionBar = actionBar,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ChatBody(
    messages: List<BitchatMessage>,
    myPeerID: String,
    emptyText: String,
    voice: VoiceNoteController,
    onOpenImage: (String) -> Unit,
    columnState: TransformingLazyColumnState,
    listBottomPadding: androidx.compose.ui.unit.Dp,
    buttonsVisible: Boolean,
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
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
                contentPadding = PaddingValues(bottom = listBottomPadding)
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

        AnimatedVisibility(
            visible = buttonsVisible,
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
