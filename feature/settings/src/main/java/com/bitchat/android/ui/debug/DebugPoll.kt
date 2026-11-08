package com.bitchat.android.ui.debug

import android.util.Log
import com.bitchat.android.settings.MeshDiagnostics
import com.bitchat.android.settings.MeshSnapshot
import com.bitchat.android.settings.TransportDiagnostics
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "DebugSettings"

/**
 * One tick of Debug's 1 Hz poll (inventory DE1): reads the mesh and writes the
 * connected devices to [manager], then mirrors Wi-Fi Aware's discovered and
 * connected peers into it. A failed read is logged and skipped, so the poll
 * goes on; the manager keeps the last value it had.
 *
 * With no mesh the connected devices are cleared, so the counts do not outlive it.
 *
 * Returns the mesh snapshot, or null when there is no mesh or the read failed.
 */
internal fun pollOnce(
    mesh: MeshDiagnostics?,
    diagnostics: TransportDiagnostics,
    manager: DebugSettingsManager,
): MeshSnapshot? {
    val snapshot = mesh?.let {
        try {
            it.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reading the mesh failed", e)
            null
        }
    }
    if (mesh == null) manager.updateConnectedDevices(emptyList())
    snapshot?.let { manager.updateConnectedDevices(it.devices) }
    try {
        manager.updateWifiAwareDiscovered(diagnostics.wifiAwareDiscoveredPeers.value)
        manager.updateWifiAwareConnected(diagnostics.wifiAwareConnectedPeers.value)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
    return snapshot
}
