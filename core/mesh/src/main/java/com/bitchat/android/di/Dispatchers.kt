package com.bitchat.android.di

import javax.inject.Qualifier

/** Qualifies the `Dispatchers.IO` a class receives instead of reaching for it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Qualifies the `Dispatchers.Default` a class receives instead of reaching for it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Qualifies the `Dispatchers.Main` a class receives instead of reaching for it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** Qualifies the `Dispatchers.Main.immediate` a class receives instead of reaching for it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainImmediateDispatcher
