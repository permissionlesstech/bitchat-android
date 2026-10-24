package com.bitchat.android.onboarding

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.OnboardingRoute
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet

/** The onboarding route, contributed to the host's navigation graph. */
@Module
@InstallIn(ActivityRetainedComponent::class)
object OnboardingEntriesModule {

    @Provides
    @IntoSet
    fun onboardingEntries(): EntryProviderInstaller = {
        entry<OnboardingRoute> {
            // Only onboarding takes the host's scaffold insets.
            OnboardingRouteContent(
                Modifier
                    .fillMaxSize()
                    .padding(LocalOnboardingContentPadding.current)
            )
        }
    }
}
