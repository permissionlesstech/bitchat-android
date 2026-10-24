package com.bitchat.android.settings

import com.bitchat.android.navigation.AboutRoute
import com.bitchat.android.navigation.DebugSettingsRoute
import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.share.AppBuildInfo
import com.bitchat.android.share.ShareAppSection
import com.bitchat.android.ui.AboutScreen
import com.bitchat.android.ui.debug.DebugSettingsScreen
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet

/** About and Debug settings, contributed to the host's navigation graph. */
@Module
@InstallIn(ActivityRetainedComponent::class)
object SettingsEntriesModule {

    @Provides
    @IntoSet
    fun settingsEntries(
        navigator: Navigator,
        share: ShareAppSection,
        backgroundMesh: BackgroundMeshSetting,
        diagnostics: TransportDiagnostics,
        buildInfo: AppBuildInfo,
    ): EntryProviderInstaller = {
        entry<AboutRoute> {
            AboutScreen(
                shareAppSection = share,
                backgroundMesh = backgroundMesh,
                onClose = { navigator.goBack() },
                onShowDebug = { navigator.goTo(DebugSettingsRoute) },
            )
        }
        entry<DebugSettingsRoute> {
            DebugSettingsScreen(
                diagnostics = diagnostics,
                buildInfo = buildInfo,
                onClose = { navigator.popTo(DebugSettingsRoute, inclusive = true) },
            )
        }
    }
}
