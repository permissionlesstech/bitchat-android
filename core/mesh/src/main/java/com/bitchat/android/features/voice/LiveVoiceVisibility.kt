package com.bitchat.android.features.voice

import java.util.concurrent.atomic.AtomicReference

/**
 * Which conversation is on screen, for deciding whether live voice plays.
 *
 * Several screens set it, and they come and go out of order while a
 * transition runs: the screen leaving can be disposed after the one arriving
 * has already set itself. [leaveDirectMessage] therefore clears only the
 * private chat it names, so a leaving chat cannot clear what replaced it.
 *
 * Scope and peer change together, so a reader never sees one without the other.
 */
class LiveVoiceVisibility {

    private data class Visible(val scope: LiveVoiceScope, val peerID: String?)

    private val visible = AtomicReference<Visible?>(null)

    fun showPublicMesh() {
        visible.set(Visible(LiveVoiceScope.PUBLIC_MESH, null))
    }

    fun showDirectMessage(peerID: String) {
        visible.set(Visible(LiveVoiceScope.DIRECT_MESSAGE, peerID))
    }

    fun clear() {
        visible.set(null)
    }

    /** Clears the private chat with [peerID] if it is still the one on screen. */
    fun leaveDirectMessage(peerID: String) {
        visible.updateAndGet { current ->
            if (current?.scope == LiveVoiceScope.DIRECT_MESSAGE && current.peerID == peerID) {
                null
            } else {
                current
            }
        }
    }

    fun isVisible(scope: LiveVoiceScope, peerID: String): Boolean {
        val current = visible.get() ?: return false
        return current.scope == scope &&
            (scope == LiveVoiceScope.PUBLIC_MESH || current.peerID == peerID)
    }
}
