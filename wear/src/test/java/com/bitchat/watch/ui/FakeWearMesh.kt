package com.bitchat.watch.ui

import com.bitchat.android.mesh.MeshMessaging
import com.bitchat.android.mesh.MeshPeers
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.noise.NoiseSession.NoiseSessionState

/**
 * Records what the Wear helpers ask of the mesh roles, in order, and answers the peer questions
 * from fields a test sets.
 */
internal class FakeWearMesh(
    override val myPeerID: String = "aaaa0000aaaa0000",
) : MeshMessaging, MeshPeers {

    val calls = mutableListOf<String>()

    data class PublicSend(val content: String, val mentions: List<String>, val channel: String?)
    data class PrivateSend(val content: String, val peerID: String, val nickname: String, val messageID: String?)

    val publicSends = mutableListOf<PublicSend>()
    val privateSends = mutableListOf<PrivateSend>()
    val broadcastFiles = mutableListOf<BitchatFilePacket>()
    val handshakes = mutableListOf<String>()

    var established = false
    var fingerprint: String? = null
    var identity = "ff".repeat(32)
    var peerInfo: PeerInfo? = null

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        calls += "sendMessage"
        publicSends += PublicSend(content, mentions, channel)
    }

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        calls += "sendPrivateMessage"
        privateSends += PrivateSend(content, recipientPeerID, recipientNickname, messageID)
    }

    override fun sendFileBroadcast(file: BitchatFilePacket) {
        calls += "sendFileBroadcast"
        broadcastFiles += file
    }

    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) = error("unused")

    override fun hasEstablishedSession(peerID: String): Boolean = established

    override fun initiateNoiseHandshake(peerID: String) {
        calls += "initiateNoiseHandshake"
        handshakes += peerID
    }

    override fun getPeerFingerprint(peerID: String): String? = fingerprint

    override fun getIdentityFingerprint(): String = identity

    override fun getPeerInfo(peerID: String): PeerInfo? = peerInfo

    override fun getPeerNicknames(): Map<String, String> = error("unused")
    override fun getPeerRSSI(): Map<String, Int> = error("unused")
    override fun getActivePeerCount(): Int = error("unused")
    override fun getStaticNoisePublicKey(): ByteArray? = error("unused")
    override fun getSessionState(peerID: String): NoiseSessionState = error("unused")
    override fun sendBroadcastAnnounce() = error("unused")
    override fun sendAnnouncementToPeer(peerID: String) = error("unused")
}
