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
     *
     * The one sanctioned exception is the chat feature, which injects
     * [AppNavigator] to read (never write) the back stack for the private chat
     * open over it. Do not follow that precedent to seed or rewrite the stack
     * from a feature.
     */
    @Binds
    abstract fun bindNavigator(impl: AppNavigator): Navigator
}
