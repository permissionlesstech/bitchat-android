package com.bitchat.android.ui;

/**
 * Une interface déléguée pour gérer les interactions avec les sessions Noise.
 * Cela permet de découpler le PrivateChatManager des détails d'implémentation
 * du service de maillage (mesh).
 */
public interface NoiseSessionDelegate {
    /**
     * Vérifie si une session sécurisée a été établie avec un pair.
     * @param peerID L'identifiant du pair.
     * @return true si la session est établie, false sinon.
     */
    boolean hasEstablishedSession(String peerID);

    /**
     * Lance le processus de handshake Noise avec un pair.
     * @param peerID L'identifiant du pair.
     */
    void initiateHandshake(String peerID);

    /**
     * Obtient l'identifiant de notre propre pair.
     * @return L'identifiant du pair local.
     */
    String getMyPeerID();
}
