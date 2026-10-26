package com.bitchat.android.mesh

/**
 * Reference-counted request for fast BLE connection intervals while live media flows.
 *
 * The default balanced interval carries only a handful of 512-byte writes per second per link;
 * live video needs several times that. Holders (a live capture, a stream being watched) acquire
 * and release; the BLE transport applies the change to its client links.
 */
object LiveMediaLinkBoost {
    private var holders = 0

    /** Applies `true` (high priority) or `false` (balanced) to every client link. */
    @Volatile var applier: ((Boolean) -> Unit)? = null

    val isActive: Boolean
        @Synchronized get() = holders > 0

    @Synchronized
    fun acquire() {
        holders++
        if (holders == 1) runCatching { applier?.invoke(true) }
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) runCatching { applier?.invoke(false) }
    }
}
