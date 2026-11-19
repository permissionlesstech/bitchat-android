package com.bitchat.android.features.voice

/**
 * Which conversation is on screen, for deciding whether live voice plays and
 * whether it arrives read.
 *
 * Each screen that shows a conversation claims it under its own owner token
 * and releases the claim when it leaves. The visible conversation is the most
 * recent claim still held, and nothing once every claim is released. Screens
 * come and go out of order while a transition runs: the screen leaving can be
 * disposed after the one arriving has already claimed, and can even re-claim
 * the same conversation on its way out. A release only ever removes its own
 * claim, so a leaving screen cannot clear what replaced it.
 *
 * The unowned calls ([showPublicMesh], [showDirectMessage], [clear]) keep the
 * single-slot behaviour for a client with one screen at a time.
 */
class LiveVoiceVisibility {

    private data class Visible(val scope: LiveVoiceScope, val peerID: String?)

    private object Unowned

    // Insertion order is claim order: a claim moves its owner to the end.
    private val claims = LinkedHashMap<Any, Visible>()

    // Read on the frame-handling thread; scope and peer are published together.
    @Volatile private var current: Visible? = null

    fun claimPublicMesh(owner: Any) = claim(owner, Visible(LiveVoiceScope.PUBLIC_MESH, null))

    fun claimDirectMessage(owner: Any, peerID: String) =
        claim(owner, Visible(LiveVoiceScope.DIRECT_MESSAGE, peerID))

    /** Drops [owner]'s claim; the most recent claim left, if any, becomes visible. */
    @Synchronized
    fun release(owner: Any) {
        claims.remove(owner)
        publish()
    }

    fun showPublicMesh() = claimPublicMesh(Unowned)

    fun showDirectMessage(peerID: String) = claimDirectMessage(Unowned, peerID)

    @Synchronized
    fun clear() {
        claims.clear()
        publish()
    }

    fun isVisible(scope: LiveVoiceScope, peerID: String): Boolean {
        val visible = current ?: return false
        return visible.scope == scope &&
            (scope == LiveVoiceScope.PUBLIC_MESH || visible.peerID == peerID)
    }

    @Synchronized
    private fun claim(owner: Any, visible: Visible) {
        claims.remove(owner)
        claims[owner] = visible
        publish()
    }

    private fun publish() {
        current = claims.values.lastOrNull()
    }
}
