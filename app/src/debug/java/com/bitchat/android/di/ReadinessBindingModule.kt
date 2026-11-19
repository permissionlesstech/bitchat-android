package com.bitchat.android.di

import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.onboarding.SystemDeviceReadiness
import com.bitchat.android.testhook.OverridableDeviceReadiness
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Debug builds read the device through [OverridableDeviceReadiness], which
 * reads the device itself until a device test sets a ReadinessOverride. The
 * release source set binds [SystemDeviceReadiness] directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReadinessBindingModule {
    @Provides
    @Singleton
    fun provideDeviceReadiness(system: SystemDeviceReadiness): DeviceReadiness =
        OverridableDeviceReadiness(system)
}
