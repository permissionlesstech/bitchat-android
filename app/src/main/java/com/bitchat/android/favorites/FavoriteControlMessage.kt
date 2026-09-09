package com.bitchat.android.favorites

import com.bitchat.android.services.ContactIdentityResolver

data class FavoriteControlMessage(
    val isFavorite: Boolean,
    val npub: String?
) {
    companion object {
        private const val FAVORITED = "[FAVORITED]"
        private const val UNFAVORITED = "[UNFAVORITED]"

        fun parse(content: String): FavoriteControlMessage? {
            val trimmed = content.trim()
            val isFavorite = when {
                trimmed.startsWith(FAVORITED) -> true
                trimmed.startsWith(UNFAVORITED) -> false
                else -> return null
            }
            val encodedKey = trimmed.substringAfter(":", "").trim()
            val npub = encodedKey
                .takeIf { it.isNotEmpty() }
                ?.let { ContactIdentityResolver.nostrPubkeyHex(it) }
                ?.let { ContactIdentityResolver.npubFromHex(it) }
            return FavoriteControlMessage(isFavorite = isFavorite, npub = npub)
        }

        fun encode(isFavorite: Boolean, npub: String?): String {
            val prefix = if (isFavorite) FAVORITED else UNFAVORITED
            return "$prefix:${npub.orEmpty()}"
        }
    }
}
