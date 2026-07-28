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

    // "Docked at newest" is the single source of truth for the chat's resting state:
    // bottom clearance expanded (newest message sits above the floating buttons), action
    // bar visible, header at full size. It is driven by scroll intent, not layout geometry.
    var dockedAtNewest by remember { mutableStateOf(true) }
    // Action bar: ALWAYS visible while the list is scrolled to the bottom; hides quickly
    // when scrolling up into history and reappears as soon as the user scrolls back down,
    // so replying is one short flick away even from the very top. The bar is an overlay,
    // so its fast 12dp threshold cannot feed back into the scroll geometry.
    val buttonsVisible = remember { mutableStateOf(true) }
    // The docked state, by contrast, changes the geometry: collapsing the bottom clearance
    // shrinks the scroll range by the 48dp padding delta. Undocking near the bottom would
    // clamp the scroll position back to the end and instantly re-dock — the hide/show
    // flapping loop. So undocking requires scrolling well clear of the bottom (60dp > 48dp).
    val undockThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        60.dp.roundToPx()
    }
    LaunchedEffect(columnState) {
        var lastPosition = -1
        var bottomPosition = 0
        snapshotFlow {
            val first = columnState.layoutInfo.visibleItems.firstOrNull()
            Triple(columnState.canScrollForward, first?.index ?: 0, first?.offset ?: 0)
        }.collect { (canScrollForward, index, offset) ->
            val atBottom = !canScrollForward
            val position = index * 100_000 + offset
            if (atBottom) {
                bottomPosition = position
                dockedAtNewest = true
                buttonsVisible.value = true
            } else if (lastPosition >= 0) {
                when {
                    position > lastPosition + 24 -> buttonsVisible.value = true
                    position < lastPosition - 24 -> buttonsVisible.value = false
                }
                if (bottomPosition - position > undockThresholdPx) dockedAtNewest = false
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
