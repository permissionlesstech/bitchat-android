package com.bitchat.android.model;

import com.bitchat.android.protocol.BitchatPacket; // Note: Cette classe sera créée ultérieurement
import java.util.Objects;

/**
 * Représente un paquet routé avec des métadonnées supplémentaires.
 * Utilisé pour le traitement et le routage des paquets dans le réseau maillé.
 */
public final class RoutedPacket {

    private final BitchatPacket packet;
    private final String peerID;
    private final String relayAddress;

    public RoutedPacket(BitchatPacket packet, String peerID, String relayAddress) {
        this.packet = packet;
        this.peerID = peerID;
        this.relayAddress = relayAddress;
    }

    public RoutedPacket(BitchatPacket packet) {
        this(packet, null, null);
    }

    public RoutedPacket(BitchatPacket packet, String peerID) {
        this(packet, peerID, null);
    }

    // Getters
    public BitchatPacket getPacket() { return packet; }
    public String getPeerID() { return peerID; }
    public String getRelayAddress() { return relayAddress; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoutedPacket that = (RoutedPacket) o;
        return Objects.equals(packet, that.packet) &&
               Objects.equals(peerID, that.peerID) &&
               Objects.equals(relayAddress, that.relayAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packet, peerID, relayAddress);
    }
}
