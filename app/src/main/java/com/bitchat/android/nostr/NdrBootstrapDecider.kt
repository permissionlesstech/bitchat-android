package com.bitchat.android.nostr

enum class NdrBootstrapAction {
    NONE,
    START_NOISE_HANDSHAKE,
    SEND_OOB_INVITE
}

object NdrBootstrapDecider {
    private const val INVITE_RETRY_MS = 15_000L
    private const val HANDSHAKE_RETRY_MS = 5_000L

    fun decide(
        hasActiveDoubleRatchet: Boolean,
        hasEstablishedNoiseSession: Boolean,
        nowMs: Long,
        lastInviteAttemptMs: Long,
        lastHandshakeAttemptMs: Long
    ): NdrBootstrapAction {
        if (hasActiveDoubleRatchet) {
            return NdrBootstrapAction.NONE
        }

        if (!hasEstablishedNoiseSession) {
            return if (nowMs - lastHandshakeAttemptMs >= HANDSHAKE_RETRY_MS) {
                NdrBootstrapAction.START_NOISE_HANDSHAKE
            } else {
                NdrBootstrapAction.NONE
            }
        }

        return if (nowMs - lastInviteAttemptMs >= INVITE_RETRY_MS) {
            NdrBootstrapAction.SEND_OOB_INVITE
        } else {
            NdrBootstrapAction.NONE
        }
    }
}
