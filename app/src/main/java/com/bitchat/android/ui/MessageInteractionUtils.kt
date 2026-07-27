package com.bitchat.android.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.model.BitchatMessage

internal fun BitchatMessage.isFromSelf(
    currentUserNickname: String,
    myPeerId: String,
): Boolean =
    senderPeerID == myPeerId ||
        sender == currentUserNickname ||
        sender.startsWith("$currentUserNickname#")

internal fun normalizeMessageUrl(rawUrl: String): String =
    if (
        rawUrl.startsWith("http://", ignoreCase = true) ||
        rawUrl.startsWith("https://", ignoreCase = true)
    ) {
        rawUrl
    } else {
        "https://$rawUrl"
    }

internal fun openMessageUrl(context: Context, rawUrl: String): Boolean =
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, normalizeMessageUrl(rawUrl).toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

internal fun channelForGeohash(geohash: String): GeohashChannel {
    val level = when (geohash.length) {
        in 0..2 -> GeohashChannelLevel.REGION
        in 3..4 -> GeohashChannelLevel.PROVINCE
        5 -> GeohashChannelLevel.CITY
        6 -> GeohashChannelLevel.NEIGHBORHOOD
        else -> GeohashChannelLevel.BLOCK
    }
    return GeohashChannel(level, geohash.lowercase())
}

internal fun navigateToGeohash(context: Context, geohash: String): Boolean =
    runCatching {
        val locationManager = LocationChannelManager.getInstance(context)
        locationManager.selectManual(channelForGeohash(geohash))
    }.isSuccess
