package com.bitchat.watch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

/**
 * Nickname entry, used both for first-run onboarding and for renaming later. The IME's
 * Done action only closes the keyboard so the user can review the name; the edge button is
 * the single commit path.
 *
 * [autoFocus] opens the keyboard immediately. Renaming asks for it; onboarding does not, so a
 * new user first sees what the field is for and can join with the suggested name in one tap.
 */
@Composable
fun NicknameSetupScreen(
    initialNickname: String,
    title: String = "bitchat",
    subtitle: String = "Pick a nickname",
    confirmLabel: String = "Join",
    autoFocus: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    val palette = LocalBitchatPalette.current
    // Pre-fill with the cursor at the end of the existing name, not the start.
    var name by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialNickname,
                selection = TextRange(initialNickname.length),
            )
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val outline by animateColorAsState(
        targetValue =
            if (focused) MaterialTheme.colorScheme.primary else palette.inputOutlineFocused,
        animationSpec = tween(BitchatMotion.STANDARD_MS),
        label = "nicknameOutline",
    )

    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }

    WearFormScreen(
        edgeButton = {
            FormEdgeButton(
                label = confirmLabel,
                enabled = name.text.isNotBlank(),
                onClick = { if (name.text.isNotBlank()) onConfirm(name.text.trim()) },
            )
        }
    ) {
        formHeader(title = title, body = subtitle)
        item {
            // Watch-sized fields have no use for a draggable cursor handle; it only covers text.
            CompositionLocalProvider(
                LocalTextSelectionColors provides HiddenHandleSelectionColors
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { newValue ->
                        val trimmed = newValue.text.trim().take(24)
                        name =
                            if (trimmed == newValue.text) {
                                newValue
                            } else {
                                newValue.copy(text = trimmed, selection = TextRange(trimmed.length))
                            }
                    },
                    singleLine = true,
                    textStyle =
                        ChatVisualTokens.MessageBodyStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    interactionSource = interactionSource,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(top = 6.dp)
                            .focusRequester(focusRequester)
                            .clip(FieldShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(1.dp, outline, FieldShape)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (name.text.isEmpty()) {
                                Text(
                                    text = "Nickname",
                                    style = ChatVisualTokens.MessageBodyStyle,
                                    color = palette.textTertiary,
                                )
                            }
                            Box(Modifier.padding(horizontal = 14.dp)) { innerTextField() }
                            // The pencil makes the pre-filled name read as editable, not a label.
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                tint = palette.textTertiary,
                                modifier = Modifier.align(Alignment.CenterEnd).size(12.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

internal val HiddenHandleSelectionColors = TextSelectionColors(
    handleColor = Color.Transparent,
    backgroundColor = Color(0x6632D74B),
)

private val FieldShape = RoundedCornerShape(18.dp)
