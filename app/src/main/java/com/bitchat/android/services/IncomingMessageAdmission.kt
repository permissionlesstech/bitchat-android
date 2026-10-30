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
 *
 * Stateless: the state it writes and the filter it asks are the caller's, passed in.
 */
internal object IncomingMessageAdmission {
    fun admitToAppState(
        message: BitchatMessage,
        appStateStore: AppStateStore,
        spamFilter: SpamFilter,
    ): Boolean = try {
        // Read once into a local: BitchatMessage lives in :core:domain, and Kotlin
        // will not smart-cast a property declared in another module.
        val channel = message.channel
        when {
            message.isPrivate -> {
                val peerID = message.senderPeerID?.takeIf(String::isNotBlank)
                    ?: return false
                // Mesh transport callbacks run on their background service workers. Wait for the
                // serialized SQLite transaction so a notification can never advertise a message
                // that an immediate process death would lose.
                runBlocking {
                    appStateStore.addPrivateMessageDurably(peerID, message)
                }
            }

            isHiddenAsSpam(message, spamFilter) -> false

            channel != null -> {
                appStateStore.addChannelMessage(channel, message)
                true
            }

            else -> {
                appStateStore.addPublicMessage(message)
                true
            }
        }
    } catch (_: Exception) {
        // Preserve the pre-existing best-effort dispatch for public/channel messages, but never
        // bypass private-message admission when persistence or canonicalization fails.
        !message.isPrivate
    }

    private fun isHiddenAsSpam(message: BitchatMessage, spamFilter: SpamFilter): Boolean {
        if (SpamFilter.isLocalNotice(message)) return false
        return spamFilter.shouldHide(message, SpamFilter.meshSenderKey(message))
    }
}
