package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.onboarding.WifiAwareAvailability
import com.bitchat.android.wifiaware.WifiAwareSupport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * What :core:readiness needs from the app. The DeviceReadiness binding itself is
 * per build type (ReadinessBindingModule in src/debug and src/release), so only
 * debug builds can fake it for device tests.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReadinessBindingsModule {
    @Provides
    fun provideWifiAwareAvailability(@ApplicationContext context: Context): WifiAwareAvailability =
        WifiAwareAvailability { WifiAwareSupport.isSupported(context) }
}
