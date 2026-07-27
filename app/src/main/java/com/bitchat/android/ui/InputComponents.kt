package com.bitchat.android.ui
// [Goose] TODO: Replace inline file attachment stub with FilePickerButton abstraction that dispatches via FileShareDispatcher


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.withStyle
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.features.voice.normalizeAmplitudeSample
import com.bitchat.android.features.voice.AudioWaveformExtractor
import com.bitchat.android.ui.media.RealtimeScrollingWaveform
import com.bitchat.android.ui.media.ImagePickerButton
import com.bitchat.android.ui.media.FilePickerButton

/**
 * Input components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

/**
 * VisualTransformation that styles slash commands with background and color
 * while preserving cursor positioning and click handling
 */
class SlashCommandVisualTransformation(
    private val commandColor: Color,
    private val commandBackground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val slashCommandRegex = Regex("(/\\w+)(?=\\s|$)")
        val annotatedString = buildAnnotatedString {
            var lastIndex = 0

            slashCommandRegex.findAll(text.text).forEach { match ->
                // Add text before the match
                if (match.range.first > lastIndex) {
                    append(text.text.substring(lastIndex, match.range.first))
                }

                // Add the styled slash command
                withStyle(
                    style = SpanStyle(
                        color = commandColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        background = commandBackground
                    )
                ) {
                    append(match.value)
                }

                lastIndex = match.range.last + 1
            }

            // Add remaining text
            if (lastIndex < text.text.length) {
                append(text.text.substring(lastIndex))
            }
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }
}

/**
 * VisualTransformation that styles mentions with background and color
 * while preserving cursor positioning and click handling
 */
class MentionVisualTransformation(
    private val mentionColor: Color,
    private val mentionBackground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val mentionRegex = Regex("@([a-zA-Z0-9_]+)")
        val annotatedString = buildAnnotatedString {
            var lastIndex = 0
            
            mentionRegex.findAll(text.text).forEach { match ->
                // Add text before the match
                if (match.range.first > lastIndex) {
                    append(text.text.substring(lastIndex, match.range.first))
                }
                
                // Add the styled mention
                withStyle(
                    style = SpanStyle(
                        color = mentionColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        // Mirrors the mention chip used in rendered messages, so what you type
                        // looks like what everyone will see.
                        background = mentionBackground
                    )
                ) {
                    append(match.value)
                }
                
                lastIndex = match.range.last + 1
            }
            
            // Add remaining text
            if (lastIndex < text.text.length) {
                append(text.text.substring(lastIndex))
            }
        }
        
        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }
}

/**
 * VisualTransformation that combines multiple visual transformations
 */
class CombinedVisualTransformation(private val transformations: List<VisualTransformation>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        var resultText = text
        
        // Apply each transformation in order
        transformations.forEach { transformation ->
            resultText = transformation.filter(resultText).text
        }
        
        return TransformedText(
            text = resultText,
            offsetMapping = OffsetMapping.Identity
        )
    }
}





/**
 * Minimum height of the composer pill.
 *
 * Roomy on purpose. The composer is a primary target that gets hit constantly, and the previous
 * 44.dp felt cramped once the action buttons moved inside it.
 */
private val ComposerMinHeight = 52.dp

/**
 * Composer corner radius.
 *
 * Half of [ComposerMinHeight], so a single-line composer is a true capsule. Fixed rather than
 * percentage-based so that when the field grows to several lines it stays a generously rounded
 * rectangle instead of degenerating into a stadium.
 */
private val ComposerShape = RoundedCornerShape(ComposerMinHeight / 2)

/** Tap target for every button inside the pill. */
private val ComposerButtonSize = 40.dp

/** Diameter of the visible disc inside that tap target. */
private val ComposerButtonDisc = 36.dp

/** Icon size shared by the composer's glyphs. */
internal val ComposerIconSize = 20.dp

/**
 * The shared visual treatment for every button in the composer: camera, microphone, send.
 *
 * One style for all three, so the cluster reads as a set. At rest they are neutral grey discs;
 * "active" (send with something to send, microphone while recording) tints towards a soft green
 * rather than the terminal's full-brightness primary, which was far too loud sitting right next
 * to the text you are typing.
 *
 * The caller owns the gesture, because the three buttons need very different ones (click,
 * long-press for the camera, press-and-hold for the microphone). This composable only supplies
 * the geometry, the colours, and the press feedback.
 */
@Composable
internal fun ComposerActionSurface(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    isPressed: Boolean = false,
    /** Accent for the active state. Defaults to the soft green used by the microphone and send. */
    activeColor: Color = Color.Unspecified,
    contentDescription: String? = null,
    content: @Composable (tint: Color) -> Unit
) {
    val palette = LocalBitchatPalette.current
    val accent = if (activeColor == Color.Unspecified) palette.accentGreen else activeColor

    val container by animateColorAsState(
        // A tint rather than a fill. A solid accent disc next to the text you are typing was the
        // loudest thing on the screen; at 20% it still reads as "armed" without competing.
        targetValue = if (isActive) accent.copy(alpha = 0.20f) else palette.surfaceVariant,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "composerButtonContainer"
    )
    val tint by animateColorAsState(
        targetValue = if (isActive) accent else palette.textSecondary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "composerButtonTint"
    )
    // A small dip on press. Spring rather than tween so the release overshoots very slightly and
    // the button feels physical instead of merely animated.
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "composerButtonScale"
    )

    Box(
        modifier = modifier.size(ComposerButtonSize),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(ComposerButtonDisc)
                .scale(scale)
                .background(container, CircleShape)
                .semantics { contentDescription?.let { this.contentDescription = it } },
            contentAlignment = Alignment.Center
        ) {
            content(tint)
        }
    }
}

@Composable
fun MessageInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    showMediaButtons: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val isFocused = remember { mutableStateOf(false) }
    val hasText = value.text.isNotBlank()
    val focusRequester = remember { FocusRequester() }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var amplitude by remember { mutableStateOf(0) }

    // Recording is the one state worth shouting about, so it overrides focus.
    val borderColor by animateColorAsState(
        targetValue = when {
            isRecording -> palette.accentRed.copy(alpha = 0.7f)
            isFocused.value -> palette.inputOutlineFocused
            else -> palette.inputOutline
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "composerBorder"
    )
    // A barely-there lift on focus. Enough to register, not enough to look like a different
    // component.
    val containerColor by animateColorAsState(
        targetValue = if (isFocused.value) palette.surfaceVariant else palette.surface,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "composerContainer"
    )

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // MARK: - The pill. Field and action buttons are one visual object.
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ComposerMinHeight)
                // Grow smoothly as the field wraps to more lines rather than jumping a line at
                // a time.
                .animateContentSize(
                    animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
                )
                .background(containerColor, ComposerShape)
                .border(1.dp, borderColor, ComposerShape),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 18.dp, end = 4.dp, top = 15.dp, bottom = 15.dp)
            ) {
                // Always keep the text field mounted to retain focus and avoid IME collapse
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    // Near-white, not terminal green: this is the one place in the app where the
                    // user is composing rather than reading, and green-on-black is tiring to
                    // type into.
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = palette.textPrimary,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(
                        if (isRecording) Color.Transparent else palette.textPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (hasText) onSend()
                    }),
                    // Cap the growth so a pasted wall of text cannot swallow the message list.
                    maxLines = 6,
                    visualTransformation = remember(palette) {
                        CombinedVisualTransformation(
                            listOf(
                                SlashCommandVisualTransformation(
                                    commandColor = palette.accentGreen,
                                    commandBackground = palette.accentGreen.copy(alpha = 0.14f),
                                ),
                                MentionVisualTransformation(
                                    mentionColor = palette.accentOrange,
                                    mentionBackground = palette.accentOrange.copy(alpha = MENTION_CHIP_ALPHA),
                                ),
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            isFocused.value = focusState.isFocused
                        }
                )

                // Placeholder fades rather than blinking, which matters because it reappears
                // every time a message is sent.
                val placeholderAlpha by animateFloatAsState(
                    targetValue = if (value.text.isEmpty() && !isRecording) 1f else 0f,
                    animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
                    label = "placeholderAlpha"
                )
                if (placeholderAlpha > 0f) {
                    Text(
                        text = stringResource(R.string.type_a_message_placeholder),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = palette.textTertiary,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(placeholderAlpha)
                    )
                }

                // Recording visualiser, layered over the (empty) field.
                val waveformAlpha by animateFloatAsState(
                    targetValue = if (isRecording) 1f else 0f,
                    animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
                    label = "waveformAlpha"
                )
                if (isRecording) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(waveformAlpha),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RealtimeScrollingWaveform(
                            modifier = Modifier.weight(1f).height(22.dp),
                            amplitudeNorm = normalizeAmplitudeSample(amplitude)
                        )
                        Spacer(Modifier.width(12.dp))
                        val secs = (elapsedMs / 1000).toInt()
                        val maxSecs = 10 // 10 second max recording time
                        Text(
                            text = String.format(
                                "%02d:%02d / %02d:%02d",
                                secs / 60, secs % 60, maxSecs / 60, maxSecs % 60
                            ),
                            fontFamily = FontFamily.Monospace,
                            color = palette.accentRed,
                            fontSize = (BASE_FONT_SIZE - 4).sp
                        )
                    }
                }
            }

            // MARK: - Action cluster, inside the pill.
            //
            // Swaps between the auxiliary buttons and send. AnimatedContent cross-fades and
            // scales between the two, and SizeTransform animates the width change, so typing the
            // first character morphs camera+mic into send instead of snapping.
            val latestSelectedPeer = rememberUpdatedState(selectedPrivatePeer)
            val latestChannel = rememberUpdatedState(currentChannel)
            val latestOnSendVoiceNote = rememberUpdatedState(onSendVoiceNote)

            AnimatedContent(
                targetState = hasText,
                transitionSpec = {
                    (
                        fadeIn(tween(BitchatMotion.STANDARD_MS)) +
                            scaleIn(
                                initialScale = 0.7f,
                                animationSpec = tween(
                                    BitchatMotion.STANDARD_MS,
                                    easing = FastOutSlowInEasing
                                )
                            )
                    ).togetherWith(
                        fadeOut(tween(BitchatMotion.QUICK_MS)) +
                            scaleOut(
                                targetScale = 0.7f,
                                animationSpec = tween(
                                    BitchatMotion.QUICK_MS,
                                    easing = FastOutSlowInEasing
                                )
                            )
                    ) using SizeTransform(clip = false) { _, _ ->
                        tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
                    }
                },
                modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                label = "composerActions"
            ) { showSend ->
                if (showSend) {
                    SendButton(
                        isAccented = latestSelectedPeer.value != null || latestChannel.value != null,
                        onSend = onSend
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showMediaButtons) {
                            // The camera steps aside while recording so the microphone is the
                            // only thing that can be released.
                            AnimatedVisibility(
                                visible = !isRecording,
                                enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
                                    expandHorizontally(
                                        tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
                                    ),
                                exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
                                    shrinkHorizontally(
                                        tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing)
                                    )
                            ) {
                                ImagePickerButton(
                                    onImageReady = { outPath ->
                                        onSendImageNote(
                                            latestSelectedPeer.value,
                                            latestChannel.value,
                                            outPath
                                        )
                                    }
                                )
                            }

                            VoiceRecordButton(
                                isRecording = isRecording,
                                onStart = {
                                    isRecording = true
                                    elapsedMs = 0L
                                    // Keep existing focus to avoid IME collapse, but do not
                                    // force-show the keyboard.
                                    if (isFocused.value) {
                                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                                    }
                                },
                                onAmplitude = { amp, ms ->
                                    amplitude = amp
                                    elapsedMs = ms
                                },
                                onFinish = { path ->
                                    isRecording = false
                                    // Extract and cache the waveform from the actual audio file
                                    // so it matches the receiver's rendering.
                                    AudioWaveformExtractor.extractAsync(path, sampleCount = 120) { arr ->
                                        if (arr != null) {
                                            try {
                                                com.bitchat.android.features.voice.VoiceWaveformCache.put(path, arr)
                                            } catch (_: Exception) {}
                                        }
                                    }
                                    latestOnSendVoiceNote.value(
                                        latestSelectedPeer.value,
                                        latestChannel.value,
                                        path
                                    )
                                }
                            )
                        } else {
                            // No media in this context, so keep an inert send button rather than
                            // leaving a hole where the action cluster should be.
                            SendButton(isAccented = false, onSend = {}, enabled = false)
                        }
                    }
                }
            }
        }
    }

    // Auto-stop handled inside VoiceRecordButton
}

/**
 * Send affordance. Only rendered when there is something to send, so its mere presence is the
 * signal; it does not need to shout in the terminal's full-brightness green as well.
 */
@Composable
private fun SendButton(
    isAccented: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = LocalBitchatPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    ComposerActionSurface(
        isActive = enabled,
        isPressed = isPressed,
        // Private chats and channels keep their orange identity, disc and glyph together.
        activeColor = if (isAccented) palette.accentOrange else palette.accentGreen,
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled
        ) { onSend() }
    ) { tint ->
        Icon(
            imageVector = Icons.Filled.ArrowUpward,
            contentDescription = stringResource(id = R.string.send_message),
            modifier = Modifier.size(ComposerIconSize),
            tint = tint
        )
    }
}

@Composable
fun CommandSuggestionsBox(
    suggestions: List<CommandSuggestion>,
    onSuggestionClick: (CommandSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .background(palette.surface)
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp)
    ) {
        suggestions.forEach { suggestion: CommandSuggestion ->
            CommandSuggestionItem(
                suggestion = suggestion,
                onClick = { onSuggestionClick(suggestion) }
            )
        }
    }
}

@Composable
fun CommandSuggestionItem(
    suggestion: CommandSuggestion,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            // Roomier rows: at 3.dp vertical these were below a comfortable tap height.
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Show all aliases together
        val allCommands = if (suggestion.aliases.isNotEmpty()) {
            listOf(suggestion.command) + suggestion.aliases
        } else {
            listOf(suggestion.command)
        }

        Text(
            text = allCommands.joinToString(", "),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = colorScheme.primary,
            fontSize = (BASE_FONT_SIZE - 2).sp
        )

        // Show syntax if any
        suggestion.syntax?.let { syntax ->
            Text(
                text = syntax,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = palette.textSecondary,
                fontSize = (BASE_FONT_SIZE - 4).sp
            )
        }

        // Show description
        Text(
            text = suggestion.description,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = palette.textTertiary,
            fontSize = (BASE_FONT_SIZE - 4).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MentionSuggestionsBox(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current

    Column(
        modifier = modifier
            .background(palette.surface)
            .border(1.dp, palette.outlineVariant, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp)
    ) {
        suggestions.forEach { suggestion: String ->
            MentionSuggestionItem(
                suggestion = suggestion,
                onClick = { onSuggestionClick(suggestion) }
            )
        }
    }
}

@Composable
fun MentionSuggestionItem(
    suggestion: String,
    onClick: () -> Unit
) {
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.mention_suggestion_at, suggestion),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = palette.accentOrange,
            fontSize = (BASE_FONT_SIZE - 2).sp
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = stringResource(R.string.mention),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = palette.textTertiary,
            fontSize = (BASE_FONT_SIZE - 4).sp
        )
    }
}
