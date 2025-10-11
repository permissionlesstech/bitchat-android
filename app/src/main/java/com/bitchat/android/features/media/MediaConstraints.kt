package com.bitchat.android.features.media

object MediaConstraints { // Change this value to adjust the max size for all media transfers
    const val MAX_MEDIA_BYTES: Long = 1L * 1024L * 1024L // 1 MB

    // Maximum voice recording duration
    // Centralizes the limit used for timer display and auto-stop logic
    const val MAX_RECORDING_SECONDS: Int = 30
    const val MAX_RECORDING_MS: Long = MAX_RECORDING_SECONDS * 1000L
}
