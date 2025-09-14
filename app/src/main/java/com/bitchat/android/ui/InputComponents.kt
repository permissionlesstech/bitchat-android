package com.bitchat.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.foundation.isSystemInDarkTheme
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.features.voice.CyberpunkVisualizer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.PermissionStatus
import android.Manifest

/**
 * Input components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

/**
 * VisualTransformation that styles slash commands with background and color
 * while preserving cursor positioning and click handling
 */
class SlashCommandVisualTransformation : VisualTransformation {
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
                        color = Color(0xFF00FF7F), // Bright green
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        background = Color(0xFF2D2D2D) // Dark gray background
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
class MentionVisualTransformation : VisualTransformation {
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
                        color = Color(0xFFFF9500), // Orange
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
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





@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MessageInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isFocused = remember { mutableStateOf(false) }
    val hasText = value.text.isNotBlank() // Check if there's text for send button state
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSessionId by remember { mutableStateOf(0) }
    val recorderHolder = remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordedFilePath by remember { mutableStateOf<String?>(null) }
    // BLE-only branch: no onion resolution
    
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    var recordingStart by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp), // Reduced padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Text input with placeholder OR visualizer when recording
        Box(
            modifier = Modifier.weight(1f)
        ) {
            if (isRecording) {
                // Visualizer in the input slot
                val amp = remember { mutableStateOf(0) }
                LaunchedEffect(recordingSessionId) {
                    while (isRecording) {
                        val ar = recorderHolder.value?.pollAmplitude() ?: 0
                        amp.value = ar
                        elapsedMs = (System.currentTimeMillis() - recordingStart).coerceAtLeast(0L)
                        kotlinx.coroutines.delay(80)
                    }
                }
                // Visualizer + elapsed timer
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CyberpunkVisualizer(amplitude = amp.value, color = Color(0xFF00FF7F))
                    val secs = (elapsedMs / 1000).toInt()
                    val mm = secs / 60
                    val ss = secs % 60
                    Text(text = String.format("%02d:%02d", mm, ss), fontFamily = FontFamily.Monospace, color = colorScheme.primary)
                }
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { 
                        if (hasText) onSend() // Only send if there's text
                    }),
                    visualTransformation = CombinedVisualTransformation(
                        listOf(SlashCommandVisualTransformation(), MentionVisualTransformation())
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            isFocused.value = focusState.isFocused
                        }
                )
                
                // Show placeholder when there's no text
    if (value.text.isEmpty()) {
                    Text(
                        text = "type a message...",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = colorScheme.onSurface.copy(alpha = 0.5f), // Muted grey
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(8.dp)) // Reduced spacing
        
        // Voice record button when no text (always visible for mesh + channels + private)
        if (value.text.isEmpty()) {
            // Hold-to-record microphone
            val bg = if (colorScheme.background == Color.Black) Color(0xFF00FF00).copy(alpha = 0.75f) else Color(0xFF008000).copy(alpha = 0.75f)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(bg, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (!isRecording) {
                                    if (micPermission.status !is PermissionStatus.Granted) {
                                        micPermission.launchPermissionRequest()
                                        return@detectTapGestures
                                    }
                                    val rec = VoiceRecorder(context)
                                    val f = rec.start()
                                    recorderHolder.value = rec
                                    isRecording = f != null
                                    recordedFilePath = f?.absolutePath
                                    recordingSessionId += 1
                                    recordingStart = System.currentTimeMillis()
                                    elapsedMs = 0L
                                }
                                try {
                                    awaitRelease()
                                } finally {
                                    val file = recorderHolder.value?.stop()
                                    isRecording = false
                                    recorderHolder.value = null
                                    val path = (file?.absolutePath ?: recordedFilePath)
                                    if (!path.isNullOrBlank()) {
                                        // BLE path (private or public)
                                        onSendVoiceNote(selectedPrivatePeer, currentChannel, path)
                                    }
                                    recordedFilePath = null
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Record voice note",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
            
        } else {
            // Send button with enabled/disabled state
            IconButton(
                onClick = { if (hasText) onSend() }, // Only execute if there's text
                enabled = hasText, // Enable only when there's text
                modifier = Modifier.size(32.dp)
            ) {
                // Update send button to match input field colors
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            color = if (!hasText) {
                                // Disabled state - muted grey
                                colorScheme.onSurface.copy(alpha = 0.3f)
                            } else if (selectedPrivatePeer != null || currentChannel != null) {
                                // Orange for both private messages and channels when enabled
                                Color(0xFFFF9500).copy(alpha = 0.75f)
                            } else if (colorScheme.background == Color.Black) {
                                Color(0xFF00FF00).copy(alpha = 0.75f) // Bright green for dark theme
                            } else {
                                Color(0xFF008000).copy(alpha = 0.75f) // Dark green for light theme
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(id = R.string.send_message),
                        modifier = Modifier.size(20.dp),
                        tint = if (!hasText) {
                            // Disabled state - muted grey icon
                            colorScheme.onSurface.copy(alpha = 0.5f)
                        } else if (selectedPrivatePeer != null || currentChannel != null) {
                            // Black arrow on orange for both private and channel modes
                            Color.Black
                        } else if (colorScheme.background == Color.Black) {
                            Color.Black // Black arrow on bright green in dark theme
                        } else {
                            Color.White // White arrow on dark green in light theme
                        }
                    )
                }
            }
        }
    }

    // Auto-stop after 10 seconds
    LaunchedEffect(isRecording, recordingSessionId) {
        if (isRecording) {
            kotlinx.coroutines.delay(10_000)
            if (isRecording) {
                val file = recorderHolder.value?.stop()
                isRecording = false
                recorderHolder.value = null
                val path = (file?.absolutePath ?: recordedFilePath)
                if (!path.isNullOrBlank()) {
                    onSendVoiceNote(selectedPrivatePeer, currentChannel, path)
                }
                recordedFilePath = null
            }
        }
    }
}

@Composable
fun CommandSuggestionsBox(
    suggestions: List<CommandSuggestion>,
    onSuggestionClick: (CommandSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(vertical = 8.dp)
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(Color.Gray.copy(alpha = 0.1f)),
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
            fontSize = (BASE_FONT_SIZE - 4).sp
        )

        // Show syntax if any
        suggestion.syntax?.let { syntax ->
            Text(
                text = syntax,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = (BASE_FONT_SIZE - 5).sp
            )
        }

        // Show description
        Text(
            text = suggestion.description,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = (BASE_FONT_SIZE - 5).sp,
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
    val colorScheme = MaterialTheme.colorScheme
    
    Column(
        modifier = modifier
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(vertical = 8.dp)
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
    val colorScheme = MaterialTheme.colorScheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(Color.Gray.copy(alpha = 0.1f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "@$suggestion",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            ),
            color = Color(0xFFFF9500), // Orange like mentions
            fontSize = (BASE_FONT_SIZE - 4).sp
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            text = "mention",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = (BASE_FONT_SIZE - 5).sp
        )
    }
}
