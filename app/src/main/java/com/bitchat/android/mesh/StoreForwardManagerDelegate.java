package com.bitchat.android.mesh;

import com.bitchat.android.protocol.BitchatPacket;

/**
 * Interface déléguée pour les callbacks du gestionnaire de stockage et retransmission (Store-Forward).
 */
public interface StoreForwardManagerDelegate {
    /**
     * Vérifie si un pair est marqué comme favori.
     * @param peerID L'identifiant du pair.
     * @return true si le pair est un favori, false sinon.
     */
    boolean isFavorite(String peerID);

    /**
     * Vérifie si un pair est actuellement en ligne.
     * @param peerID L'identifiant du pair.
     * @return true si le pair est en ligne, false sinon.
     */
    boolean isPeerOnline(String peerID);

    /**
     * Demande au délégué d'envoyer un paquet.
     * @param packet Le paquet à envoyer.
     */
    void sendPacket(BitchatPacket packet);
}
