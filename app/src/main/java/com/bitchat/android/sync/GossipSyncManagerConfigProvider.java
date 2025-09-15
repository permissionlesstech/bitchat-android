package com.bitchat.android.sync;

/**
 * Interface pour fournir la configuration au GossipSyncManager.
 */
public interface GossipSyncManagerConfigProvider {
    int seenCapacity();
    int gcsMaxBytes();
    double gcsTargetFpr();
}
