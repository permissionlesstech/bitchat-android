package com.bitchat.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chat transcript presentation.
 *
 * [Matrix] is the established terminal-style transcript: a flat, left-aligned monochrome
 * stream where colour is reserved for `@names` and links.
 *
 * [Bubbles] is the classic messenger layout: messages hug their content inside rounded
 * bubbles, own messages on the right and everyone else on the left. Each bubble is tinted
 * with its author's stable peer colour, so the speaker stays identifiable without reading
 * the name. Colours, surfaces, and typography are untouched — only the message layout
 * changes.
 */
enum class ChatUiMode {
    Matrix,
    Bubbles;

    val isMatrix: Boolean get() = this == Matrix
    val isBubbles: Boolean get() = this == Bubbles
}

/**
 * Simple SharedPreferences-backed manager for the chat UI mode with a StateFlow.
 * Mirrors [ThemePreferenceManager].
 */
object ChatUiModeManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_CHAT_UI_MODE = "chat_ui_mode"

    private val _modeFlow = MutableStateFlow(ChatUiMode.Bubbles)
    val modeFlow: StateFlow<ChatUiMode> = _modeFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CHAT_UI_MODE, ChatUiMode.Bubbles.name)
        _modeFlow.value = runCatching { ChatUiMode.valueOf(saved!!) }.getOrDefault(ChatUiMode.Bubbles)
    }

    fun set(context: Context, mode: ChatUiMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CHAT_UI_MODE, mode.name).apply()
        _modeFlow.value = mode
    }
}
