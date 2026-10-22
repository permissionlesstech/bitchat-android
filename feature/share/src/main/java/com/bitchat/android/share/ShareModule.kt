package com.bitchat.android.share

import com.bitchat.android.ui.ShareAppSectionImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ShareModule {
    @Binds
    abstract fun bindShareAppSection(impl: ShareAppSectionImpl): ShareAppSection
}
