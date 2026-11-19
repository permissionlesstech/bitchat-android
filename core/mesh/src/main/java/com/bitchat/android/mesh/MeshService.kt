package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatFilePacket

/** Starts and stops a mesh transport. */
interface MeshLifecycle {
    fun startServices()
    fun stopServices()
}

/** Who is on the mesh, the Noise identity and sessions with them, and announcing ourselves. */
interface MeshPeers {
    val myPeerID: String

    fun getPeerNicknames(): Map<String, String>
    fun getPeerRSSI(): Map<String, Int>
    fun getActivePeerCount(): Int
    fun getPeerInfo(peerID: String): PeerInfo?
    fun getPeerFingerprint(peerID: String): String?
    fun getIdentityFingerprint(): String
    fun getStaticNoisePublicKey(): ByteArray?
    fun hasEstablishedSession(peerID: String): Boolean
    fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState
    fun initiateNoiseHandshake(peerID: String)
    fun sendBroadcastAnnounce()
    fun sendAnnouncementToPeer(peerID: String)
}

/** Sends chat messages, files and voice frames. */
interface MeshMessaging {
    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null)
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null)
    fun sendFileBroadcast(file: BitchatFilePacket)
    fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray)
}

/** Tells a peer that a private message was read. */
interface MeshReceipts {
    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String)
}

/** The QR verification challenge and response. */
interface MeshVerification {
    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray)
    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray)
}

/** Private file transfers that wait for a Noise session or the user's consent. */
interface MeshPrivateMedia {
    fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation
    fun cancelFileTransfer(transferId: String): Boolean
}

/** Transport addresses and the debug dump. */
interface MeshDiagnostics {
    fun getDeviceAddressForPeer(peerID: String): String?
    fun getDeviceAddressToPeerMapping(): Map<String, String>
    fun getDebugStatus(): String
}

/** Panic wipe of the in-memory and persistent state. */
interface MeshWipe {
    fun clearAllInternalData()
    /** Returns true only when every transport's persistent identity was wiped from disk. */
    fun clearAllEncryptionData(): Boolean
}

/**
 * Transport-agnostic mesh service API for UI and routing layers: every role together. A client that
 * cannot really do a role does not implement it.
 */
interface MeshService :
    MeshLifecycle,
    MeshPeers,
    MeshMessaging,
    MeshReceipts,
    MeshVerification,
    MeshPrivateMedia,
    MeshDiagnostics,
    MeshWipe {
    var delegate: MeshDelegate?
}
