package com.bitchat.android.mesh;

import java.util.List;

/**
 * Interface déléguée pour les callbacks du gestionnaire de pairs (PeerManager).
 */
public interface PeerManagerDelegate {
    /**
     * Appelé lorsque la liste des pairs est mise à jour.
     * @param peerIDs La nouvelle liste des identifiants des pairs actifs.
     */
    void onPeerListUpdated(List<String> peerIDs);

    /**
     * Appelé lorsqu'un pair est supprimé (par exemple, devient obsolète).
     * @param peerID L'identifiant du pair supprimé.
     */
    void onPeerRemoved(String peerID);
}
