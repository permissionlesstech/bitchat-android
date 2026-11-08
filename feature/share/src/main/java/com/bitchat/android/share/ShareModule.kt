package com.bitchat.android.share

import android.content.Context
import com.bitchat.android.hotspot.ApkWebServingFactory
import com.bitchat.android.hotspot.HotspotControllerFactory
import com.bitchat.android.hotspot.apkWebServerServingFactory
import com.bitchat.android.hotspot.hotspotManagerControllerFactory
import com.bitchat.android.ui.ShareAppSectionImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ShareModule {
    @Binds
    abstract fun bindShareAppSection(impl: ShareAppSectionImpl): ShareAppSection

    companion object {
        @Provides
        fun provideHotspotControllerFactory(
            @ApplicationContext context: Context
        ): HotspotControllerFactory = hotspotManagerControllerFactory(context)

        @Provides
        fun provideApkWebServingFactory(
            @ApplicationContext context: Context
        ): ApkWebServingFactory = apkWebServerServingFactory(context)
    }
}
