package com.bitchat.android.ui

/**
 * UI constants/utilities for nickname rendering.
 */
const val MAX_NICKNAME_LENGTH: Int = 15

fun truncateNickname(name: String, maxLen: Int = MAX_NICKNAME_LENGTH): String {
    return if (name.length <= maxLen) name else name.take(maxLen)
}


