package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.spam.SpamFilter
import kotlinx.coroutines.runBlocking

/**
 * Reflects an incoming transport message into process-wide state before any downstream effects.
 *
 * Private-message admission is authoritative: a duplicate or a message rejected while panic mode
 * is wiping state must not continue to UI delegates, unread tracking, haptics, or notifications.
 * Public and channel messages retain their existing best-effort behavior if state reflection fails,
 * and are refused (no timeline row, no UI callback) when the spam filter hides them.
 */
internal object IncomingMessageAdmission {
    fun admitToAppState(message: BitchatMessage): Boolean = try {
        when {
            message.isPrivate -> {
                val peerID = message.senderPeerID?.takeIf(String::isNotBlank)
                    ?: return false
                // Mesh transport callbacks run on their background service workers. Wait for the
                // serialized SQLite transaction so a notification can never advertise a message
                // that an immediate process death would lose.
                runBlocking {
                    AppStateStore.addPrivateMessageDurably(peerID, message)
                }
            }

            isHiddenAsSpam(message) -> false

            message.channel != null -> {
                AppStateStore.addChannelMessage(message.channel, message)
                true
            }

            else -> {
                AppStateStore.addPublicMessage(message)
                true
            }
        }
    } catch (_: Exception) {
        // Preserve the pre-existing best-effort dispatch for public/channel messages, but never
        // bypass private-message admission when persistence or canonicalization fails.
        !message.isPrivate
    }

    private fun isHiddenAsSpam(message: BitchatMessage): Boolean {
        if (SpamFilter.isLocalNotice(message)) return false
        return SpamFilter.shared.shouldHide(message, SpamFilter.meshSenderKey(message))
    }
}
