package com.bitchat.android.net

/**
 * Standard flavor implementation of TorProvider factory.
 * Returns StandardTorProvider instance.
 */
internal object TorProviderFactoryImpl {
    fun create(): TorProvider = StandardTorProvider()
}
