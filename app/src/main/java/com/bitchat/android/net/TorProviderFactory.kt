package com.bitchat.android.net

/**
 * Factory to provide the correct TorProvider implementation based on build flavor.
 *
 * Uses double-checked locking singleton pattern for thread-safe lazy initialization.
 *
 * Implementations:
 * - standard flavor: StandardTorProvider (no Tor)
 * - tor flavor: RealTorProvider (full Tor)
 * - Phase 2: DynamicTorProvider (on-demand download)
 */
object TorProviderFactory {
    @Volatile
    private var instance: TorProvider? = null

    /**
     * Get the singleton TorProvider instance for the current build flavor.
     */
    fun getInstance(): TorProvider {
        instance?.let { return it }

        return synchronized(this) {
            instance?.let { return it }
            val newInstance = TorProviderFactoryImpl.create()
            instance = newInstance
            newInstance
        }
    }

    /**
     * Reset singleton for testing only.
     */
    internal fun resetForTesting() {
        synchronized(this) {
            instance = null
        }
    }
}
