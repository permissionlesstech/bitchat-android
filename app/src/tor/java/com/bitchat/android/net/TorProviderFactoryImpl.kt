package com.bitchat.android.net

/**
 * Tor flavor implementation of TorProvider factory.
 * Returns RealTorProvider instance with full Guardian Project Arti support.
 */
internal object TorProviderFactoryImpl {
    fun create(): TorProvider = RealTorProvider()
}
