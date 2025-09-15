package com.bitchat.android.mesh;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Gère les pairs actifs, leurs pseudonymes, le suivi RSSI et les empreintes.
 */
public class PeerManager {

    private static final String TAG = "PeerManager";
    private static final long STALE_PEER_TIMEOUT = 180000L; // 3 minutes
    private static final long CLEANUP_INTERVAL = 60000L; // 1 minute

    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final PeerFingerprintManager fingerprintManager = PeerFingerprintManager.getInstance();
    public PeerManagerDelegate delegate;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public PeerManager() {
        startPeriodicCleanup();
    }

    public boolean updatePeerInfo(String peerID, String nickname, byte[] noisePublicKey, byte[] signingPublicKey, boolean isVerified) {
        if ("unknown".equals(peerID)) return false;

        long now = System.currentTimeMillis();
        PeerInfo existingPeer = peers.get(peerID);
        boolean isNewPeer = existingPeer == null;

        PeerInfo peerInfo = new PeerInfo(peerID, nickname, true, existingPeer != null ? existingPeer.isDirectConnection() : false, noisePublicKey, signingPublicKey, isVerified, now);
        peers.put(peerID, peerInfo);

        if (isNewPeer && isVerified) {
            notifyPeerListUpdate();
            Log.d(TAG, "🆕 New verified peer: " + nickname + " (" + peerID + ")");
            return true;
        }
        return false;
    }

    public void updatePeerLastSeen(String peerID) {
        if (!"unknown".equals(peerID)) {
            PeerInfo info = peers.get(peerID);
            if (info != null) {
                peers.put(peerID, new PeerInfo(info, null, null, true, null));
            }
        }
    }

    public void removePeer(String peerID) {
        PeerInfo removed = peers.remove(peerID);
        fingerprintManager.removePeer(peerID);
        if (removed != null && delegate != null) {
            delegate.onPeerRemoved(peerID);
            notifyPeerListUpdate();
        }
    }

    public List<String> getActivePeerIDs() {
        long now = System.currentTimeMillis();
        return peers.entrySet().stream()
            .filter(entry -> (now - entry.getValue().getLastSeen()) <= STALE_PEER_TIMEOUT && entry.getValue().isConnected())
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());
    }

    public String getPeerNickname(String peerID) {
        PeerInfo info = peers.get(peerID);
        return info != null ? info.getNickname() : null;
    }

    public Map<String, String> getAllPeerNicknames() {
        return peers.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getNickname()));
    }

    public PeerInfo getPeerInfo(String peerID) {
        return peers.get(peerID);
    }

    public void setDirectConnection(String peerID, boolean isDirect) {
        PeerInfo info = peers.get(peerID);
        if (info != null && info.isDirectConnection() != isDirect) {
            peers.put(peerID, new PeerInfo(info, null, null, null, isDirect));
            notifyPeerListUpdate();
        }
    }

    private void notifyPeerListUpdate() {
        if (delegate != null) {
            delegate.onPeerListUpdated(getActivePeerIDs());
        }
    }

    private void startPeriodicCleanup() {
        scheduler.scheduleAtFixedRate(this::cleanupStalePeers, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void cleanupStalePeers() {
        long now = System.currentTimeMillis();
        List<String> peersToRemove = new ArrayList<>();
        for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
            if ((now - entry.getValue().getLastSeen()) > STALE_PEER_TIMEOUT) {
                peersToRemove.add(entry.getKey());
            }
        }
        for (String peerID : peersToRemove) {
            Log.d(TAG, "Removing stale peer: " + peerID);
            removePeer(peerID);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    public void clearAllPeers() {
        peers.clear();
        fingerprintManager.clearAllFingerprints();
        notifyPeerListUpdate();
    }

    public String storeFingerprintForPeer(String peerID, byte[] publicKey) {
        return fingerprintManager.storeFingerprintForPeer(peerID, publicKey);
    }
}
