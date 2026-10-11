package com.bitchat.android.services

import java.net.URI

/** Parses explicit channel invitations; never requests or infers device location. */
object ChannelInvitation {
    private val geohash = Regex("[0123456789bcdefghjkmnpqrstuvwxyz]{1,12}")
    fun decode(link: String): String? = runCatching {
        val uri = URI(link)
        require(uri.scheme == "bitchat" && uri.host == "geohash" && uri.query == null && uri.fragment == null && uri.userInfo == null && uri.port == -1)
        val cell = uri.path.removePrefix("/").lowercase()
        cell.takeIf(geohash::matches)
    }.getOrNull()
    fun link(cell: String): String? = cell.lowercase().takeIf(geohash::matches)?.let { "bitchat://geohash/$it" }
}
