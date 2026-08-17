package com.bitchat.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

/**
 * Nickname entry, used both for first-run onboarding and for renaming later. The IME's
 * Done action only closes the keyboard so the user can review the name; the confirm
 * button is the single commit path.
 */
@Composable
fun NicknameSetupScreen(
    initialNickname: String,
    title: String = "bitchat",
    subtitle: String = "Pick a nickname",
    confirmLabel: String = "Join the mesh",
    onConfirm: (String) -> Unit
) {
    val palette = LocalBitchatPalette.current
    // Pre-fill with the cursor at the end of the existing name, not the start.
    var name by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialNickname,
                selection = TextRange(initialNickname.length)
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        BasicTextField(
            value = name,
            onValueChange = { newValue ->
                val trimmed = newValue.text.trim().take(24)
                name = if (trimmed == newValue.text) {
                    newValue
                } else {
                    newValue.copy(text = trimmed, selection = TextRange(trimmed.length))
                }
            },
            singleLine = true,
            textStyle = ChatVisualTokens.MessageBodyStyle.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboardController?.hide()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(18.dp))
                .background(palette.inputSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.Center) {
                    if (name.text.isEmpty()) {
                        Text(
                            text = "Nickname",
                            style = ChatVisualTokens.MessageBodyStyle,
                            color = palette.textTertiary
                        )
                    }
                    innerTextField()
                }
            }
        )
        Button(
            onClick = { if (name.text.isNotBlank()) onConfirm(name.text.trim()) },
            enabled = name.text.isNotBlank(),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text(confirmLabel)
        }
    }
}
