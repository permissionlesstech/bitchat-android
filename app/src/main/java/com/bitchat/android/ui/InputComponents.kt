package com.bitchat.android.ui
// [Goose] TODO: Replace inline file attachment stub with FilePickerButton abstraction that dispatches via FileShareDispatcher


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
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
import androidx.compose.ui.draw.clip
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





/** Minimum height of the composer pill: a single line of 15.sp text plus 12.dp of padding. */
private val ComposerMinHeight = 44.dp

/**
 * Composer corner radius.
 *
 * Fixed rather than "50%": at [ComposerMinHeight] this yields a true capsule, and when the field
 * grows to multiple lines it stays a generously rounded rectangle instead of degenerating into
 * the stadium shape a percentage radius would produce.
 */
private val ComposerShape = RoundedCornerShape(22.dp)

/** Diameter of the circular send affordance nested inside the pill. */
private val SendButtonSize = 36.dp

/** Icon size shared by the composer's glyphs, matching the top bar. */
internal val ComposerIconSize = 22.dp

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
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val isFocused = remember { mutableStateOf(false) }
    val hasText = value.text.isNotBlank() // Check if there's text for send button state
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var amplitude by remember { mutableStateOf(0) }

    // Recording turns the pill's outline red; a cross-fade keeps that from flashing.
    val borderColor by animateColorAsState(
        targetValue = when {
            isRecording -> palette.accentRed.copy(alpha = 0.6f)
            isFocused.value -> palette.outline
            else -> palette.outlineVariant
        },
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "composerBorder"
    )

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        // Bottom-aligned so that when the field grows to several lines the send button and the
        // media buttons stay pinned next to the last line, as in the design's long-text state.
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // MARK: - The pill. Holds the field and the send button as one visual object.
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ComposerMinHeight)
                .background(palette.surfaceVariant, ComposerShape)
                .border(1.dp, borderColor, ComposerShape),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            ) {
                // Always keep the text field mounted to retain focus and avoid IME collapse
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(if (isRecording) Color.Transparent else colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (hasText) onSend() // Only send if there's text
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

                // Show placeholder when there's no text and not recording
                if (value.text.isEmpty() && !isRecording) {
                    Text(
                        text = stringResource(R.string.type_a_message_placeholder),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = palette.textTertiary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Overlay the real-time scrolling waveform while recording
                if (isRecording) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RealtimeScrollingWaveform(
                            modifier = Modifier.weight(1f).height(24.dp),
                            amplitudeNorm = normalizeAmplitudeSample(amplitude)
                        )
                        Spacer(Modifier.width(12.dp))
                        val secs = (elapsedMs / 1000).toInt()
                        val mm = secs / 60
                        val ss = secs % 60
                        val maxSecs = 10 // 10 second max recording time
                        val maxMm = maxSecs / 60
                        val maxSs = maxSecs % 60
                        Text(
                            text = String.format("%02d:%02d / %02d:%02d", mm, ss, maxMm, maxSs),
                            fontFamily = FontFamily.Monospace,
                            color = palette.accentRed,
                            fontSize = (BASE_FONT_SIZE - 4).sp
                        )
                    }
                }
            }

            SendButton(
                hasText = hasText,
                isAccented = selectedPrivatePeer != null || currentChannel != null,
                onSend = onSend,
                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
            )
        }

        // MARK: - Media affordances, outside the pill, only while the field is empty.
        if (value.text.isEmpty() && showMediaButtons) {
            // Ensure latest values are used when finishing recording
            val latestSelectedPeer = rememberUpdatedState(selectedPrivatePeer)
            val latestChannel = rememberUpdatedState(currentChannel)
            val latestOnSendVoiceNote = rememberUpdatedState(onSendVoiceNote)

            // Image button (image picker) - hide during recording
            if (!isRecording) {
                ImagePickerButton(
                    onImageReady = { outPath ->
                        onSendImageNote(latestSelectedPeer.value, latestChannel.value, outPath)
                    }
                )
            }

            VoiceRecordButton(
                backgroundColor = colorScheme.primary,
                onStart = {
                    isRecording = true
                    elapsedMs = 0L
                    // Keep existing focus to avoid IME collapse, but do not force-show keyboard
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
                    // Extract and cache waveform from the actual audio file to match receiver rendering
                    AudioWaveformExtractor.extractAsync(path, sampleCount = 120) { arr ->
                        if (arr != null) {
                            try { com.bitchat.android.features.voice.VoiceWaveformCache.put(path, arr) } catch (_: Exception) {}
                        }
                    }
                    // BLE path (private or public) — use latest values to avoid stale captures
                    latestOnSendVoiceNote.value(
                        latestSelectedPeer.value,
                        latestChannel.value,
                        path
                    )
                }
            )
        }
    }

    // Auto-stop handled inside VoiceRecordButton
}

/**
 * Circular send affordance nested in the bottom-right of the composer pill.
 *
 * Goes from a flat grey disc to a solid accent the moment there is something to send, which is
 * the clearest possible signal that the return key will do something.
 */
@Composable
private fun SendButton(
    hasText: Boolean,
    isAccented: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    val targetBackground = when {
        !hasText -> palette.outline
        isAccented -> palette.accentOrange
        else -> colorScheme.primary
    }
    val targetTint = when {
        !hasText -> palette.textTertiary
        // Both accents are bright enough that black is the only legible arrow colour.
        else -> Color.Black
    }

    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing),
        label = "sendButtonBackground"
    )
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing),
        label = "sendButtonTint"
    )

    Box(
        modifier = modifier
            .size(SendButtonSize)
            .background(background, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = hasText) { onSend() },
        contentAlignment = Alignment.Center
    ) {
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
