package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

/**
 * The mesh the chat session talks to.
 *
 * Read once from the injected mesh when the session starts and replaced only
 * by a panic clear, which recreates the mesh with a fresh identity. It is not
 * re-read from the holder on each use: the foreground service clears the
 * holder when it stops, and re-reading would quietly create a new mesh that
 * was never started and has no delegate, where the session keeps the one it
 * was using.
 */
@ActivityRetainedScoped
class ChatSessionMesh @Inject constructor(
    unified: MeshService,
) : NoiseSessionDelegate {

    /** Every transport behind one interface; what the session sends through. */
    var unified: MeshService = unified
        private set

    fun replace(unified: MeshService) {
        this.unified = unified
    }

    override fun hasEstablishedSession(peerID: String): Boolean = try {
        unified.getPeerInfo(peerID)?.isConnected == true &&
            unified.hasEstablishedSession(peerID)
    } catch (_: Exception) {
        false
    }

    override fun initiateHandshake(peerID: String) = unified.initiateNoiseHandshake(peerID)

    override fun getMyPeerID(): String = unified.myPeerID
}
