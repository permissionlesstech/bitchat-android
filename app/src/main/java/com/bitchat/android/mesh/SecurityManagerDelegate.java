package com.bitchat.android.mesh;

/**
 * Interface déléguée pour les callbacks du gestionnaire de sécurité (SecurityManager).
 */
public interface SecurityManagerDelegate {
    /**
     * Appelé lorsque l'échange de clés avec un pair est terminé avec succès.
     * @param peerID L'identifiant du pair.
     * @param peerPublicKeyData La clé publique du pair.
     */
    void onKeyExchangeCompleted(String peerID, byte[] peerPublicKeyData);

    /**
     * Demande au délégué d'envoyer une réponse de handshake.
     * @param peerID L'identifiant du pair destinataire.
     * @param response Le message de réponse à envoyer.
     */
    void sendHandshakeResponse(String peerID, byte[] response);

    /**
     * Obtient les informations sur un pair, nécessaire pour la vérification de signature.
     * @param peerID L'identifiant du pair.
     * @return L'objet PeerInfo ou null si le pair est inconnu.
     */
    PeerInfo getPeerInfo(String peerID);
}
