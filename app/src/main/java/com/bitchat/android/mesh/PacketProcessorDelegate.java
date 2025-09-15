package com.bitchat.android.mesh;

import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;

/**
 * Interface déléguée pour les callbacks du processeur de paquets (PacketProcessor).
 */
public interface PacketProcessorDelegate {
    // Validation de sécurité
    boolean validatePacketSecurity(BitchatPacket packet, String peerID);

    // Gestion des pairs
    void updatePeerLastSeen(String peerID);
    String getPeerNickname(String peerID);

    // Information réseau
    int getNetworkSize();
    byte[] getBroadcastRecipient();

    // Gestionnaires de type de message
    boolean handleNoiseHandshake(RoutedPacket routed);
    void handleNoiseEncrypted(RoutedPacket routed);
    void handleAnnounce(RoutedPacket routed);
    void handleMessage(RoutedPacket routed);
    void handleLeave(RoutedPacket routed);
    BitchatPacket handleFragment(RoutedPacket routed);
    void handleRequestSync(RoutedPacket routed);

    // Communication
    void sendAnnouncementToPeer(String peerID);
    void sendCachedMessages(String peerID);
    void relayPacket(RoutedPacket routed);
}
