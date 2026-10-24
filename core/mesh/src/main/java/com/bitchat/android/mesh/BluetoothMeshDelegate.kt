package com.bitchat.android.mesh

/**
 * Delegate interface for BLE mesh callbacks. Extends the shared mesh delegate so
 * transport-agnostic facades can receive the same callback stream.
 */
interface BluetoothMeshDelegate : MeshDelegate {
    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long)
    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long)
}
