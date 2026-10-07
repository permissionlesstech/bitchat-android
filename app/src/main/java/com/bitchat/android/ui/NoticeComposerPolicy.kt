package com.bitchat.android.ui

internal object NoticeComposerPolicy {
    private val finiteExpiryDays = listOf(1, 3, 7)

    fun expiryOptions(isGeo: Boolean): List<Int> =
        if (isGeo) listOf(0) + finiteExpiryDays else finiteExpiryDays

    fun isPermanentRelayOnlyGeo(isGeo: Boolean, expiryDays: Int): Boolean =
        isGeo && expiryDays == 0
}
