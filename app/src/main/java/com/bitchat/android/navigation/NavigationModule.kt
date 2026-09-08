package com.bitchat.android.navigation

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent

@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class NavigationModule {

    /**
     * Features inject [Navigator]; only the host injects [AppNavigator] itself,
     * since reading or seeding the back stack is the host's job.
     */
    @Binds
    abstract fun bindNavigator(impl: AppNavigator): Navigator
}
