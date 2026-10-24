package com.bitchat.android.di

import com.bitchat.android.AppStartupGate
import com.bitchat.android.onboarding.OnboardingHost
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The coroutine scope of [AppStartupGate]: retained across configuration changes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppStartupScope

/** The app gate, and the port onboarding reports to it through. */
@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class AppStartupModule {

    @Binds
    abstract fun bindOnboardingHost(gate: AppStartupGate): OnboardingHost

    companion object {
        /** A supervisor on the main thread, cancelled when the retained component is cleared. */
        @Provides
        @ActivityRetainedScoped
        @AppStartupScope
        fun provideAppStartupScope(lifecycle: ActivityRetainedLifecycle): CoroutineScope {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            lifecycle.addOnClearedListener { scope.cancel() }
            return scope
        }
    }
}
