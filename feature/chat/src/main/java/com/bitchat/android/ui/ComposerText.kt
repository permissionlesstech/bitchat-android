package com.bitchat.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.TextFieldValue
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences

/**
 * The composer's text for [conversation], a private peer or null for the public
 * chat.
 *
 * Saveable, so unsent text outlives chat leaving composition: a destination
 * pushed over chat replaces it rather than covering it. Changing conversation
 * loads that conversation's [storedDraft] instead.
 *
 * The saved text is filed under the conversation it was typed into, because a
 * restore does not check inputs. The selected peer is not saved and comes back
 * null after process death, so without the key a private draft would reappear
 * in the public composer, one tap from being sent to everyone.
 */
@Composable
internal fun rememberComposerText(
    conversation: String?,
    storedDraft: (String?) -> String,
): MutableState<TextFieldValue> = rememberSaveable(
    conversation,
    stateSaver = TextFieldValue.Saver,
    key = "composer:${conversation.orEmpty()}",
) {
    mutableStateOf(TextFieldValue(conversation?.let(storedDraft).orEmpty()))
}

/** The saved draft for a private conversation, by its canonical id; empty for none. */
internal fun ConversationListPreferences.composerDraft(conversationID: String?): String =
    conversationID
        ?.let(ContactDirectory::canonicalConversationId)
        ?.lowercase()
        ?.let(drafts.value::get)
        .orEmpty()

/** Saves a private conversation's draft. Public timelines keep none. */
internal fun ConversationListPreferences.saveComposerDraft(conversationID: String?, text: String) {
    if (conversationID.isNullOrBlank()) return
    setDraft(conversationID, text)
}
