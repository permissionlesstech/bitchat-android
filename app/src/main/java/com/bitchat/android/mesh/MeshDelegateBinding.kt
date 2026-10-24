package com.bitchat.android.mesh

/**
 * Attaches the chat session as the mesh service's delegate while the UI is up and
 * hands it back when the UI goes away, so the foreground service can own DM
 * notifications.
 */
class MeshDelegateBinding(
    private val service: MeshService,
    private val delegate: MeshDelegate,
) {
    val attached: Boolean
        get() = service.delegate === delegate

    fun attach() {
        service.delegate = delegate
    }

    fun detachIfAttached() {
        if (attached) service.delegate = null
    }
}
