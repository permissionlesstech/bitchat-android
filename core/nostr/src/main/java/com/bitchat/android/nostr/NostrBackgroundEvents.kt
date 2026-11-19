package com.bitchat.android.nostr

/**
 * What the process-owned relay subscriptions hand their events to when no UI
 * is attached. The app supplies it, since keeping the messages means writing
 * them into the chat session's stores.
 */
interface NostrBackgroundEvents {
    fun onAccountDm(event: NostrEvent, identity: NostrIdentity)
    fun onGeohashMessage(event: NostrEvent, geohash: String)
    fun onGeohashDm(event: NostrEvent, geohash: String, identity: NostrIdentity)
    fun conversationGeohash(conversationKey: String): String?
    fun displayNameForNostrPubkey(pubkeyHex: String): String
    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String
}
