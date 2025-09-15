package com.bitchat.android.mesh;

import com.bitchat.android.model.BitchatMessage;
import java.util.List;

/**
 * Interface déléguée pour les callbacks du service de maillage (mesh).
 * Maintient une interface identique à la version iOS.
 */
public interface BluetoothMeshDelegate {
    /**
     * Appelé lorsqu'un nouveau message est reçu.
     */
    void didReceiveMessage(BitchatMessage message);

    /**
     * Appelé lorsque la liste des pairs connectés est mise à jour.
     */
    void didUpdatePeerList(List<String> peers);

    /**
     * Appelé lorsqu'un pair quitte un canal.
     */
    void didReceiveChannelLeave(String channel, String fromPeer);

    /**
     * Appelé lors de la réception d'un accusé de livraison.
     */
    void didReceiveDeliveryAck(String messageID, String recipientPeerID);

    /**
     * Appelé lors de la réception d'un accusé de lecture.
     */
    void didReceiveReadReceipt(String messageID, String recipientPeerID);

    /**
     * Demande au délégué de déchiffrer un message de canal.
     */
    String decryptChannelMessage(byte[] encryptedContent, String channel);

    /**
     * Demande au délégué le pseudonyme actuel de l'utilisateur.
     */
    String getNickname();

    /**
     * Vérifie si un pair est marqué comme favori.
     */
    boolean isFavorite(String peerID);
}
