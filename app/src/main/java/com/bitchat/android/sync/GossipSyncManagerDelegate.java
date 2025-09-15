package com.bitchat.android.sync;

import com.bitchat.android.protocol.BitchatPacket;

/**
 * Interface déléguée pour le GossipSyncManager.
 */
public interface GossipSyncManagerDelegate {
    void sendPacket(BitchatPacket packet);
    void sendPacketToPeer(String peerID, BitchatPacket packet);
    BitchatPacket signPacketForBroadcast(BitchatPacket packet);
}
