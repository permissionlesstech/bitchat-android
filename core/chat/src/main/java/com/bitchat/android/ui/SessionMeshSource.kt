package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService

/** Where the chat session's mesh comes from, for panic clear to start over. */
interface SessionMeshSource {
    /**
     * Discards the process's mesh and builds a new one, which picks up the
     * freshly generated identity. Returns the mesh the session talks to.
     */
    fun recreate(): MeshService
}
