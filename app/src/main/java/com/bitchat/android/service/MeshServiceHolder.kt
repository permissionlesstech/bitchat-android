package com.bitchat.android.service

import android.content.Context
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 */
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"
    @Volatile
    var sharedGossipSyncManager: GossipSyncManager? = null
        private set

    private val activeGossipOwners = mutableSetOf<String>()

    @Synchronized
    fun setGossipManager(
        mgr: GossipSyncManager,
        signer: (BitchatPacket) -> BitchatPacket
    ) {
        val previous = sharedGossipSyncManager
        if (previous !== mgr) {
            try { previous?.stop() } catch (_: Exception) { }
        }
        sharedGossipSyncManager = mgr
        mgr.delegate = TransportGossipDelegate(signer)
        if (activeGossipOwners.isNotEmpty()) {
            mgr.start()
        }
    }

    @Synchronized
    fun startSharedGossip(owner: String) {
        val wasIdle = activeGossipOwners.isEmpty()
        activeGossipOwners.add(owner)
        if (wasIdle) {
            sharedGossipSyncManager?.start()
        }
    }

    @Synchronized
    fun stopSharedGossip(owner: String) {
        activeGossipOwners.remove(owner)
        if (activeGossipOwners.isEmpty()) {
            sharedGossipSyncManager?.stop()
        }
    }

    private class TransportGossipDelegate(
        private val signer: (BitchatPacket) -> BitchatPacket
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) {
            TransportBridgeService.broadcastFromLocal(RoutedPacket(packet))
        }

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            TransportBridgeService.sendToPeerFromLocal(peerID, packet)
        }

        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
            return signer(packet)
        }
    }

    @Volatile
    var meshService: BluetoothMeshService? = null
        private set

    @Volatile
    var unifiedMeshService: UnifiedMeshService? = null
        private set

    @Synchronized
    fun getOrCreate(context: Context): BluetoothMeshService {
        val existing = meshService
        if (existing != null) {
            // If the existing instance is healthy, reuse it; otherwise, replace it.
            return try {
                if (existing.isReusable()) {
                    android.util.Log.d(TAG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    android.util.Log.w(TAG, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    // Best-effort stop before replacing
                    try { existing.stopServices() } catch (e: Exception) {
                        android.util.Log.w(TAG, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = BluetoothMeshService(context.applicationContext)
                    android.util.Log.i(TAG, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    unifiedMeshService = null
                    created
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking service reusability; creating new instance: ${e.message}")
                val created = BluetoothMeshService(context.applicationContext)
                meshService = created
                unifiedMeshService = null
                created
            }
        }
        val created = BluetoothMeshService(context.applicationContext)
        android.util.Log.i(TAG, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        unifiedMeshService = null
        return created
    }

    @Synchronized
    fun getUnifiedOrCreate(context: Context): UnifiedMeshService {
        val bluetooth = getOrCreate(context)
        val existing = unifiedMeshService
        if (existing != null) {
            existing.refreshDelegates()
            return existing
        }
        val created = UnifiedMeshService(context.applicationContext, bluetooth)
        unifiedMeshService = created
        android.util.Log.i(TAG, "Created new UnifiedMeshService")
        return created
    }

    @Synchronized
    fun attach(service: BluetoothMeshService) {
        android.util.Log.d(TAG, "Attaching BluetoothMeshService to holder")
        meshService = service
        unifiedMeshService = null
    }

    @Synchronized
    fun clear() {
        android.util.Log.d(TAG, "Clearing BluetoothMeshService from holder")
        try { sharedGossipSyncManager?.stop() } catch (_: Exception) { }
        sharedGossipSyncManager = null
        activeGossipOwners.clear()
        meshService = null
        unifiedMeshService = null
    }
}
