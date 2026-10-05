package com.bitchat.android.ui

import java.text.BreakIterator

/**
 * UI constants/utilities for nickname rendering.
 */
fun truncateNickname(name: String, maxLen: Int = com.bitchat.android.util.AppConstants.UI.MAX_NICKNAME_LENGTH): String {
    if (name.length <= maxLen) return name

    // The limit counts UTF-16 code units, so cutting at it can land inside a
    // surrogate pair and leave a lone surrogate that renders as a tofu box, or
    // split a ZWJ sequence into its parts. Step back to the last grapheme
    // boundary at or before the limit so what is shown is always whole.
    val boundaries = BreakIterator.getCharacterInstance()
    boundaries.setText(name)
    val end = boundaries.preceding(maxLen + 1)
    return if (end == BreakIterator.DONE) "" else name.substring(0, end)
}
