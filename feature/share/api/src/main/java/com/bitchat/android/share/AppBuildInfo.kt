package com.bitchat.android.share

/**
 * Build facts APK sharing needs from the app, which a library module cannot
 * read from the app's BuildConfig. The app provides one instance from its own
 * BuildConfig, so the release-certificate pin keeps a single source.
 *
 * [githubReleaseCertSha256] is the normalised SHA-256 fingerprint of the pinned
 * release certificate, or an empty string when no pin is configured.
 */
data class AppBuildInfo(
    val versionName: String,
    val isDebug: Boolean,
    val githubReleaseCertSha256: String
)
