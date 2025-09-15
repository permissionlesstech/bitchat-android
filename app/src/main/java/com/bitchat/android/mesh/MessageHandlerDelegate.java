package com.bitchat.android.mesh;

import com.bitchat.android.model.BitchatMessage;
import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;

/**
 * Interface déléguée pour les callbacks du gestionnaire de messages (MessageHandler).
 */
public interface MessageHandlerDelegate {
    // Gestion des pairs
    boolean addOrUpdatePeer(String peerID, String nickname);
    void removePeer(String peerID);
    void updatePeerNickname(String peerID, String nickname);
    String getPeerNickname(String peerID);
    int getNetworkSize();
    String getMyNickname();
    PeerInfo getPeerInfo(String peerID);
    boolean updatePeerInfo(String peerID, String nickname, byte[] noisePublicKey, byte[] signingPublicKey, boolean isVerified);

    // Opérations sur les paquets
    void sendPacket(BitchatPacket packet);
    void relayPacket(RoutedPacket routed);
    byte[] getBroadcastRecipient();

    // Opérations cryptographiques
    boolean verifySignature(BitchatPacket packet, String peerID);
    byte[] encryptForPeer(byte[] data, String recipientPeerID);
    byte[] decryptFromPeer(byte[] encryptedData, String senderPeerID);
    boolean verifyEd25519Signature(byte[] signature, byte[] data, byte[] publicKey);

    // Opérations du protocole Noise
    boolean hasNoiseSession(String peerID);
    void initiateNoiseHandshake(String peerID);
    byte[] processHandshakeMessage(byte[] payload, String peerID);
    void updatePeerIDBinding(String newPeerID, String nickname, byte[] publicKey, String previousPeerID);

    // Opérations sur les messages
    String decryptChannelMessage(byte[] encryptedContent, String channel);

    // Callbacks
    void onMessageReceived(BitchatMessage message);
    void onChannelLeave(String channel, String fromPeer);
    void onDeliveryAckReceived(String messageID, String peerID);
    void onReadReceiptReceived(String messageID, String peerID);
}
