package com.bitchat.watch.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

/**
 * Full-screen text input: field auto-focused so the watch IME (with its built-in dictation)
 * opens immediately, plus a dedicated dictation button using the system speech recognizer.
 * The line above the field names the destination, so a message is never sent to the wrong
 * place; the two round buttons match the chat action bar.
 */
@Composable
fun TextInputScreen(
    recipient: String?,
    autoFocus: Boolean = true,
    onSend: (String) -> Unit,
) {
    val palette = LocalBitchatPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val dictationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                WearHaptics.tick(context)
                onSend(spoken.trim())
            }
        }
    }

    fun send() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            keyboardController?.hide()
            WearHaptics.tick(context)
            onSend(trimmed)
            text = ""
        }
    }

    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }

    val canSend = text.isNotBlank()
    val sendContainer by animateColorAsState(
        targetValue = if (canSend) MaterialTheme.colorScheme.primary else palette.inputButton,
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "sendContainer",
    )
    val sendIcon by animateColorAsState(
        targetValue = if (canSend) MaterialTheme.colorScheme.onPrimary else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "sendIcon",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = recipient?.let { "To $it" } ?: "To #mesh",
            style = ChatVisualTokens.SystemActionStyle,
            color = palette.textTertiary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        CompositionLocalProvider(LocalTextSelectionColors provides HiddenHandleSelectionColors) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = ChatVisualTokens.MessageBodyStyle.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .clip(FieldShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, palette.inputOutline, FieldShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "Message",
                                style = ChatVisualTokens.MessageBodyStyle,
                                color = palette.textTertiary
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundActionButton(
                onClick = {
                    dictationLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak")
                        }
                    )
                },
                container = palette.inputButton,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Dictate",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(ActionIconSize)
                )
            }
            RoundActionButton(
                onClick = { send() },
                enabled = canSend,
                container = sendContainer,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = sendIcon,
                    modifier = Modifier.size(ActionIconSize)
                )
            }
        }
    }
}

private val FieldShape = RoundedCornerShape(18.dp)
