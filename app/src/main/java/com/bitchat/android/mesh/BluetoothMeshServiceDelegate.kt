package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage

/**
 * A dedicated delegate for providing real-time state updates from the
 * BluetoothMeshService to the ForegroundService notification.
 */
interface MeshServiceStateDelegate {
    fun onMeshStateUpdated(
        peerCount: Int,
        unreadCount: Int,
        recentMessages: List<BitchatMessage>
    )
}
