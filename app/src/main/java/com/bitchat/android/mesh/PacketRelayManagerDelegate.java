package com.bitchat.android.mesh;

import com.bitchat.android.model.RoutedPacket;

/**
 * Interface déléguée pour les callbacks du gestionnaire de relais de paquets (PacketRelayManager).
 */
public interface PacketRelayManagerDelegate {
    /**
     * Obtient la taille actuelle du réseau (nombre de pairs).
     */
    int getNetworkSize();

    /**
     * Obtient l'identifiant de destinataire pour la diffusion (broadcast).
     */
    byte[] getBroadcastRecipient();

    /**
     * Demande au délégué de diffuser un paquet pour le relais.
     * @param routed Le paquet routé à diffuser.
     */
    void broadcastPacket(RoutedPacket routed);
}
