package com.bitchat.android.services.bridge

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.PrekeyBundle
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.ContactIdentityResolver

/**
 * Coordinates authenticated prekey packets without owning cryptographic
 * persistence. [PrekeyManager] remains the repository/crypto boundary.
 */
internal class PrekeyCoordinator(
    private val manager: PrekeyManager,
    private val meshProvider: () -> MeshService?,
    private val peersProvider: () -> List<VerifiedBridgePeer>,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val pendingPackets = linkedMapOf<String, BitchatPacket>()
    private var lastBroadcastMs = 0L

    fun handlePacket(packet: BitchatPacket) {
        val bundle = PrekeyBundle.decode(packet.payload) ?: return
        val owner = ContactIdentityResolver.peerIdForNoiseKey(bundle.noiseStaticPublicKey)
        if (owner != packet.senderID.toHex()) return
        val peer = peersProvider().firstOrNull { it.peerId == owner }
        if (peer == null || !peer.noiseKey.contentEquals(bundle.noiseStaticPublicKey)) {
            if (pendingPackets.size < MAX_PENDING_PACKETS || owner in pendingPackets) {
                pendingPackets[owner] = packet
            }
            return
        }
        ingestVerified(packet, bundle, peer)
    }

    fun handlePeerVerified(peerId: String) {
        pendingPackets.remove(peerId)?.let(::handlePacket)
    }

    fun broadcast(force: Boolean = false) {
        val now = clock()
        if (!force && now - lastBroadcastMs < REBROADCAST_INTERVAL_MS) return
        val bundle = manager.currentSignedBundle(now) ?: return
        val encoded = bundle.encode() ?: return
        lastBroadcastMs = now
        meshProvider()?.sendPrekeyBundle(encoded)
    }

    fun wipe() {
        pendingPackets.clear()
        lastBroadcastMs = 0L
        manager.wipe()
    }

    private fun ingestVerified(
        packet: BitchatPacket,
        bundle: PrekeyBundle,
        peer: VerifiedBridgePeer
    ) {
        if (!BridgePacketSignatureVerifier.verify(packet, peer.signingKey)) return
        manager.verifyAndIngest(bundle, peer.noiseKey, peer.signingKey, clock())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_PENDING_PACKETS = 64
        const val REBROADCAST_INTERVAL_MS = 60L * 60 * 1000
    }
}
