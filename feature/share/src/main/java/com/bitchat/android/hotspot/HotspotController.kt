package com.bitchat.android.hotspot

import android.content.Context

/**
 * The part of [HotspotManager] that [HotspotViewModel] drives. It exists so the
 * ViewModel can be tested without a Wi-Fi Direct radio.
 */
interface HotspotController {
    fun startHotspot(callback: HotspotManager.HotspotCallback, confirmedReplacementName: String? = null)
    fun stopHotspot(onTeardownComplete: (() -> Unit)? = null)
    fun getConnectionInfo(): HotspotManager.ConnectionInfo?
}

/** Makes a fresh [HotspotController] for each start, as the ViewModel did with [HotspotManager]. */
fun interface HotspotControllerFactory {
    fun create(): HotspotController
}

/** Delegates every call to a real [HotspotManager]. */
internal class HotspotManagerController(private val manager: HotspotManager) : HotspotController {
    override fun startHotspot(
        callback: HotspotManager.HotspotCallback,
        confirmedReplacementName: String?
    ) = manager.startHotspot(callback, confirmedReplacementName)

    override fun stopHotspot(onTeardownComplete: (() -> Unit)?) =
        manager.stopHotspot(onTeardownComplete)

    override fun getConnectionInfo(): HotspotManager.ConnectionInfo? = manager.getConnectionInfo()
}

internal fun hotspotManagerControllerFactory(context: Context) = HotspotControllerFactory {
    HotspotManagerController(HotspotManager(context))
}
