package com.bitchat.android.core.ui.utils

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Inserts a line break (`\n`) into the [TextFieldValue] at the current cursor position or replaces the selected text with a line break.
 *
 * This function modifies the text by:
 * 1. Taking the substring before the current selection start.
 * 2. Appending a newline character (`\n`).
 * 3. Appending the substring after the current selection end.
 *
 * The cursor is then moved to the position immediately after the inserted newline character.
 *
 * @param onValueChange A lambda function that will be invoked with the new [TextFieldValue]
 *                      after the line break has been inserted. This is typically used to update
 *                      the state of a `TextField`.
 */
fun TextFieldValue.lineBreak(
    onValueChange: (TextFieldValue) -> Unit
) {
    val newTextValue = TextFieldValue(
        text = this.text.substring(
            0,
            selection.start
        ) + '\n' + this.text.substring(selection.end),
        selection = TextRange(selection.start + 1)
    )
    onValueChange(newTextValue)
}