package com.bitchat.watch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.watch.ui.media.WaveformBars
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

/**
 * Native Wear bottom action bar (designed for the ScreenScaffold `edgeButton` slot): a keyboard
 * button that opens the text input screen, and a push-to-talk mic button — press and hold to
 * record, release to send. Recording state lives in [VoiceNoteController], hoisted at screen
 * level so [VoiceRecordOverlay] can render full-screen outside this slot.
 */
@Composable
fun ChatActionBar(
    onKeyboard: () -> Unit,
    voice: VoiceNoteController,
    busyTalker: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = LocalBitchatPalette.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) voice.start() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.inputButton)
                .clickable { onKeyboard() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = "type message",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    when {
                        voice.recording -> MaterialTheme.colorScheme.primary
                        busyTalker != null -> androidx.compose.ui.graphics.Color(0xFFFFB300).copy(alpha = 0.28f)
                        else -> palette.inputButton
                    }
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                voice.start()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            // Only a clean release stops here. If the scroll parent steals
                            // the pointer mid-drag (cancel), keep recording — the
                            // screen-level release watcher in ChatScaffold stops when the
                            // finger actually lifts, anywhere on the screen.
                            if (tryAwaitRelease()) {
                                voice.stop(send = true)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "push to talk",
                tint = when {
                    voice.recording -> MaterialTheme.colorScheme.onPrimary
                    busyTalker != null -> androidx.compose.ui.graphics.Color(0xFFFFB300)
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Full-screen push-to-talk overlay: fades in over the chat with a live waveform, elapsed time,
 * and a release hint. Rendered as a sibling of the screen content (NOT inside the edgeButton
 * slot, which would clip it to the slot bounds).
 *
 * The big mic button doubles as the slide-to-cancel target: when the user's finger approaches
 * it ([hoveringCancel]), it snaps into a red cancel button with a bouncy spring; lifting the
 * finger there cancels the recording, dragging back out returns to send mode.
 */
@Composable
fun VoiceRecordOverlay(
    voice: VoiceNoteController,
    hoveringCancel: Boolean,
    proximity: Float,
    magnetPull: Offset,
    onCancelBounds: (androidx.compose.ui.geometry.Rect) -> Unit
) {
    val palette = LocalBitchatPalette.current
    // The cancel morph, choreographed for feel:
    // - color flows green→red CONTINUOUSLY as the finger approaches (finger-driven, so it
    //   is perfectly fluid), completing to full red on activation
    // - the button leans toward the approaching finger (magnetic pull), chasing it with a
    //   smooth spring so it lags and settles naturally
    // - scale blooms with a soft bounce on activation — no rotation, no wobble
    val cancelScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hoveringCancel) 1.32f else 1f + 0.1f * proximity,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "cancelSnap"
    )
    val pull by androidx.compose.animation.core.animateOffsetAsState(
        targetValue = magnetPull,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "magnetPull"
    )
    val cancelColor = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.error,
        if (hoveringCancel) 1f else proximity * 0.85f
    )
    AnimatedVisibility(
        visible = voice.recording,
        enter = fadeIn(tween(BitchatMotion.EMPHASIZED_MS)),
        exit = fadeOut(tween(BitchatMotion.EMPHASIZED_MS))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        onCancelBounds(
                            androidx.compose.ui.geometry.Rect(
                                coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero),
                                coords.size.toSize()
                            )
                        )
                    }
                    .size(52.dp)
                    .graphicsLayer {
                        translationX = pull.x
                        translationY = pull.y
                        scaleX = cancelScale
                        scaleY = cancelScale
                    }
                    .clip(CircleShape)
                    .background(cancelColor),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = hoveringCancel,
                    animationSpec = tween(BitchatMotion.STANDARD_MS),
                    label = "cancelIcon"
                ) { cancel ->
                    Icon(
                        imageVector = if (cancel) Icons.Filled.Close else Icons.Filled.Mic,
                        contentDescription = if (cancel) "cancel recording" else null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            WaveformBars(
                samples = voice.liveSamples,
                progress = 1f,
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(44.dp)
            )
            Text(
                text = (if (voice.isLive) "LIVE · " else "") + "%d:%02d".format(
                    voice.elapsedMs / 1000 / 60,
                    voice.elapsedMs / 1000 % 60
                ) + " / 0:10",
                style = ChatVisualTokens.SenderStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                text = if (hoveringCancel) "Release to cancel" else "Lift finger to send",
                style = ChatVisualTokens.SystemActionStyle,
                color = if (hoveringCancel) MaterialTheme.colorScheme.error
                else palette.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
