package com.bitchat.android.navigation

import androidx.navigation3.runtime.NavKey

/**
 * Shows the private chat for [conversationID] over [over].
 *
 * Everything above [over] goes first, so opening one chat while another is
 * shown replaces it rather than stacking a second. The peer list passes itself,
 * so Back from the chat returns to the list; every other opener (chat user
 * sheet, geohash DM, unread shortcut) uses chat, so Back returns there. When
 * [over] is not on the stack the chat opens over chat instead.
 *
 * Does nothing while chat is not on the stack, as during onboarding.
 */
fun Navigator.openPrivateChat(conversationID: String, over: NavKey = ChatRoute) {
    if (!popTo(over) && !popTo(ChatRoute)) return
    goTo(PrivateChatRoute(conversationID))
}
