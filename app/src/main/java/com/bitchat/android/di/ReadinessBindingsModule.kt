package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.onboarding.SystemDeviceReadiness
import com.bitchat.android.onboarding.WifiAwareAvailability
import com.bitchat.android.wifiaware.WifiAwareSupport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** What :core:readiness needs from the app, and the device readiness the app reads onboarding from. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReadinessBindingsModule {
    @Binds
    @Singleton
    abstract fun bindDeviceReadiness(impl: SystemDeviceReadiness): DeviceReadiness

    companion object {
        @Provides
        fun provideWifiAwareAvailability(@ApplicationContext context: Context): WifiAwareAvailability =
            WifiAwareAvailability { WifiAwareSupport.isSupported(context) }
    }
}
