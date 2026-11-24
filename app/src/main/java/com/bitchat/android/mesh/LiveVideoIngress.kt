package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage

/**
 * Receive-side hook for live video. The phone app registers its player here; clients without
 * one (Wear OS) still validate and relay public video frames but never decode them.
 */
interface LiveVideoSink {
    /** Returns false only when the frame violates the live video wire/resource contract. */
    fun handleFrame(
        peerID: String,
        nickname: String,
        isPrivate: Boolean,
        payload: ByteArray,
        timestampMs: Long
    ): Boolean

    /** Replaces a live bubble with its finalized `video_<streamID>.mp4`; true when absorbed. */
    fun absorbFinalizedVideo(message: BitchatMessage): Boolean
}

object LiveVideoIngress {
    @Volatile var sink: LiveVideoSink? = null
}
