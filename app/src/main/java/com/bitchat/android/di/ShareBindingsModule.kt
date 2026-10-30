package com.bitchat.android.di

import com.bitchat.android.BuildConfig
import com.bitchat.android.share.AppBuildInfo
import com.bitchat.android.share.HotspotRadioLease
import com.bitchat.android.wifiaware.WifiAwareController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * This build's facts for APK sharing, read from the app's BuildConfig: the only
 * source of the release-certificate pin.
 */
val AppBuildFacts = AppBuildInfo(
    versionName = BuildConfig.VERSION_NAME,
    isDebug = BuildConfig.DEBUG,
    githubReleaseCertSha256 = BuildConfig.GITHUB_RELEASE_CERT_SHA256,
)

/** What :feature:share needs from the app: its build facts and the hotspot radio lease. */
@Module
@InstallIn(SingletonComponent::class)
object ShareBindingsModule {
    @Provides
    fun provideAppBuildInfo(): AppBuildInfo = AppBuildFacts

    // Wi-Fi Aware holds the NAN interface the hotspot's P2P group needs.
    @Provides
    fun provideHotspotRadioLease(wifiAwareController: WifiAwareController): HotspotRadioLease =
        HotspotRadioLease { wifiAwareController.acquireHotspotLease() }
}
