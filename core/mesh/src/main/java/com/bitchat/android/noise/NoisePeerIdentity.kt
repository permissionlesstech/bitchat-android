package com.bitchat.android.noise

import com.bitchat.android.util.toHexString
import java.security.MessageDigest

/**
 * Canonical binding between an authenticated Noise static key and its mesh wire identity.
 *
 * Mesh peer IDs are the first eight bytes of SHA-256(staticPublicKey), encoded as 16 lowercase
 * hexadecimal characters. A self-signed announcement or completed Noise XX handshake is not
 * sufficient on its own: the claimed wire ID must also match this derivation.
 */
object NoisePeerIdentity {
    const val STATIC_PUBLIC_KEY_SIZE = 32
    const val WIRE_PEER_ID_LENGTH = 16

    private val sha256ThreadLocal = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    fun derivePeerID(staticPublicKey: ByteArray): String? {
        if (staticPublicKey.size != STATIC_PUBLIC_KEY_SIZE) return null
        val md = sha256ThreadLocal.get()!!
        md.reset()
        return md.digest(staticPublicKey).toHexString(8)
    }

    fun matchesClaimedPeerID(claimedPeerID: String, staticPublicKey: ByteArray): Boolean {
        if (claimedPeerID.length != WIRE_PEER_ID_LENGTH) return false
        for (i in 0 until WIRE_PEER_ID_LENGTH) {
            val c = claimedPeerID[i]
            if (c !in '0'..'9' && c !in 'a'..'f') return false
        }
        return derivePeerID(staticPublicKey) == claimedPeerID
    }
}
