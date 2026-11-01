package com.bitchat.android.onboarding

/**
 * Whether this device has Wi-Fi Aware. The phone app answers it, since the
 * Wi-Fi Aware transport is the app's own.
 */
fun interface WifiAwareAvailability {
    fun isSupported(): Boolean
}
