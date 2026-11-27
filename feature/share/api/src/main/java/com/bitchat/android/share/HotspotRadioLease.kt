package com.bitchat.android.share

/**
 * Claims the Wi-Fi radio for the APK-sharing hotspot. Closing the returned
 * lease hands the radio back; closing it more than once has no further effect.
 */
fun interface HotspotRadioLease {
    fun acquire(): AutoCloseable
}
