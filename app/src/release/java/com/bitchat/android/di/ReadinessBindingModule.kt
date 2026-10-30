package com.bitchat.android.di

import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.onboarding.SystemDeviceReadiness
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release builds read the device itself. Debug builds bind a readiness device tests can fake. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReadinessBindingModule {
    @Binds
    @Singleton
    abstract fun bindDeviceReadiness(impl: SystemDeviceReadiness): DeviceReadiness
}
