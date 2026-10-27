package com.bitchat.watch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.ripple
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

    // Someone else holding the public channel tints the mic amber; recording fills it green.
    val micContainer by animateColorAsState(
        targetValue = when {
            voice.recording -> MaterialTheme.colorScheme.primary
            busyTalker != null -> palette.accentOrange.copy(alpha = 0.28f)
            else -> palette.inputButton
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "micContainer"
    )
    val micTint by animateColorAsState(
        targetValue = when {
            voice.recording -> MaterialTheme.colorScheme.onPrimary
            busyTalker != null -> palette.accentOrange
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "micTint"
    )
    var micPressed by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundActionButton(onClick = onKeyboard, container = palette.inputButton) {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = "Type message",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ActionIconSize)
            )
        }
        Box(
            modifier = Modifier
                .size(ActionButtonSize)
                .pressScale(micPressed)
                .clip(CircleShape)
                .background(micContainer)
                .semantics { role = Role.Button }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            micPressed = true
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
                            val released = tryAwaitRelease()
                            micPressed = false
                            if (released) {
                                voice.stop(send = true)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Hold to talk",
                tint = micTint,
                modifier = Modifier.size(ActionIconSize)
            )
        }
    }
}

private const val AMPLITUDE_POLL_EASE_MS = 100
internal val ActionButtonSize = 48.dp
internal val ActionIconSize = 22.dp

/** Round 48dp action in the chat's visual language, with a soft press-down. */
@Composable
internal fun RoundActionButton(
    onClick: () -> Unit,
    container: Color,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ActionButtonSize)
            .pressScale(pressed)
            .clip(CircleShape)
            .background(container)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Buttons sink slightly under the finger and spring back on release. */
@Composable
internal fun Modifier.pressScale(pressed: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
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
    onCancelBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    val palette = LocalBitchatPalette.current
    // The cancel morph, choreographed for feel:
    // - color flows green→red CONTINUOUSLY as the finger approaches (finger-driven, so it
    //   is perfectly fluid), completing to full red on activation
    // - the button leans toward the approaching finger (magnetic pull), chasing it with a
    //   smooth spring so it lags and settles naturally
    // - scale blooms with a soft bounce on activation — no rotation, no wobble
    val cancelScale by
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (hoveringCancel) 1.32f else 1f + 0.1f * proximity,
            animationSpec =
                androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
            label = "cancelSnap",
        )
    val pull by
        androidx.compose.animation.core.animateOffsetAsState(
            targetValue = magnetPull,
            animationSpec =
                androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
            label = "magnetPull",
        )
    // Green and red blend through olive, so the fill switches cleanly on activation and the
    // approach is told by a red halo that thickens as the finger nears.
    val cancelColor by animateColorAsState(
        targetValue =
            if (hoveringCancel) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        animationSpec = tween(BitchatMotion.QUICK_MS),
        label = "cancelFill",
    )
    val haloColor = MaterialTheme.colorScheme.error.copy(alpha = proximity)
    // isLive drops as soon as recording stops; hold the label through the fade-out.
    var showLive by remember { mutableStateOf(false) }
    LaunchedEffect(voice.recording, voice.isLive) { if (voice.recording) showLive = voice.isLive }
    // The 10 s budget runs around the display edge, the Wear idiom for a countdown; it warms
    // to amber over the last seconds so the cap never arrives as a surprise.
    // Amplitude polls land every ~80 ms; easing between them keeps the sweep continuous.
    val budget by animateFloatAsState(
        targetValue = (voice.elapsedMs / MAX_VOICE_NOTE_MS.toFloat()).coerceIn(0f, 1f),
        animationSpec = tween(AMPLITUDE_POLL_EASE_MS, easing = LinearEasing),
        label = "voiceBudget",
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            hoveringCancel -> MaterialTheme.colorScheme.error
            budget >= 0.8f -> palette.accentOrange
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "voiceBudgetRing",
    )
    AnimatedVisibility(
        visible = voice.recording,
        enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
            scaleIn(tween(BitchatMotion.EMPHASIZED_MS), initialScale = 0.96f),
        exit = fadeOut(tween(BitchatMotion.STANDARD_MS)),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            val trackColor = palette.inputButton
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke),
                )
                if (budget > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * budget,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            val side =
                if (LocalConfiguration.current.isScreenRound) {
                    roundContentSide(maxWidth.value, maxHeight.value).dp
                } else {
                    minOf(maxWidth, maxHeight) - 24.dp
                }
            Column(
                modifier = Modifier.size(side),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Text gets its natural height first. The decorative waveform and cancel icon
                // share the remaining room instead of pushing instructions off the display.
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val iconSize = minOf(52.dp, maxHeight / 1.32f, maxWidth / 1.32f)
                    Box(
                        modifier =
                            Modifier.onGloballyPositioned { coords ->
                                    onCancelBounds(
                                        androidx.compose.ui.geometry.Rect(
                                            coords.localToRoot(
                                                androidx.compose.ui.geometry.Offset.Zero
                                            ),
                                            coords.size.toSize(),
                                        )
                                    )
                                }
                                .size(iconSize)
                                .graphicsLayer {
                                    translationX = pull.x
                                    translationY = pull.y
                                    scaleX = cancelScale
                                    scaleY = cancelScale
                                }
                                .border(
                                    width = (4f * proximity).dp,
                                    color = haloColor,
                                    shape = CircleShape,
                                )
                                .padding((4f * proximity).dp)
                                .clip(CircleShape)
                                .background(cancelColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.Crossfade(
                            targetState = hoveringCancel,
                            animationSpec = tween(BitchatMotion.STANDARD_MS),
                            label = "cancelIcon",
                        ) { cancel ->
                            Icon(
                                imageVector = if (cancel) Icons.Filled.Close else Icons.Filled.Mic,
                                contentDescription = if (cancel) "Cancel recording" else null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(iconSize / 2),
                            )
                        }
                    }
                }
                WaveformBars(
                    samples = voice.liveSamples,
                    progress = 1f,
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(0.8f).height(14.dp),
                )
                Text(
                    text =
                        (if (showLive) "LIVE " else "") +
                            "%d:%02d".format(
                                voice.elapsedMs / 1000 / 60,
                                voice.elapsedMs / 1000 % 60,
                            ),
                    style = ChatVisualTokens.SenderStyle,
                    textAlign = TextAlign.Center,
                    color =
                        if (showLive) palette.accentOrange
                        else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                Text(
                    text = if (hoveringCancel) "Lift to cancel" else "Lift to send",
                    style = ChatVisualTokens.SystemActionStyle,
                    color =
                        if (hoveringCancel) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Without this line the cancel gesture is invisible until discovered by luck.
                Text(
                    text = "Slide up to cancel",
                    style = ChatVisualTokens.TimestampStyle,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (hoveringCancel) 0f else 1f },
                )
            }
        }
    }
}
