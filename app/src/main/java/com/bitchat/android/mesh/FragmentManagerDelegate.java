package com.bitchat.android.mesh;

import com.bitchat.android.protocol.BitchatPacket;

/**
 * Interface déléguée pour les callbacks du gestionnaire de fragments (FragmentManager).
 */
public interface FragmentManagerDelegate {
    /**
     * Appelé lorsqu'un paquet a été entièrement réassemblé à partir de ses fragments.
     * @param packet Le paquet original réassemblé.
     */
    void onPacketReassembled(BitchatPacket packet);
}
