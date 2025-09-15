package com.bitchat.android.mesh;

import android.util.Log;

import com.bitchat.android.util.BinaryEncodingUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton centralisé pour la gestion des empreintes digitales des pairs (fingerprints).
 * Fournit une source unique de vérité pour l'identité des pairs dans toute l'application.
 */
public final class PeerFingerprintManager {

    private static final String TAG = "PeerFingerprintManager";

    private static volatile PeerFingerprintManager INSTANCE;

    private final Map<String, String> peerIDToFingerprint = new ConcurrentHashMap<>();
    private final Map<String, String> fingerprintToPeerID = new ConcurrentHashMap<>();

    private PeerFingerprintManager() {}

    public static PeerFingerprintManager getInstance() {
        if (INSTANCE == null) {
            synchronized (PeerFingerprintManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new PeerFingerprintManager();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Stocke le mappage d'empreinte après une prise de contact (handshake) Noise réussie.
     * C'est le seul endroit où les empreintes doivent être stockées.
     * @param peerID L'ID actuel du pair.
     * @param publicKey La clé publique statique du pair obtenue via Noise.
     * @return L'empreinte calculée.
     */
    public String storeFingerprintForPeer(String peerID, byte[] publicKey) {
        String fingerprint = calculateFingerprint(publicKey);
        if (fingerprint == null) return null;

        peerIDToFingerprint.put(peerID, fingerprint);
        fingerprintToPeerID.put(fingerprint, peerID);

        Log.d(TAG, "Stored fingerprint for peer " + peerID + ": " + fingerprint.substring(0, 16) + "...");
        return fingerprint;
    }

    public String getFingerprintForPeer(String peerID) {
        if (peerID == null || peerID.isEmpty()) return null;
        return peerIDToFingerprint.get(peerID);
    }

    public String getPeerIDForFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return null;
        return fingerprintToPeerID.get(fingerprint);
    }

    public Map<String, String> getAllPeerFingerprints() {
        return Collections.unmodifiableMap(peerIDToFingerprint);
    }

    public void removePeer(String peerID) {
        if (peerID == null || peerID.isEmpty()) return;
        String fingerprint = peerIDToFingerprint.remove(peerID);
        if (fingerprint != null) {
            fingerprintToPeerID.remove(fingerprint);
            Log.d(TAG, "Removed peer mappings for " + peerID);
        }
    }

    public void clearAllFingerprints() {
        int count = peerIDToFingerprint.size();
        peerIDToFingerprint.clear();
        fingerprintToPeerID.clear();
        Log.w(TAG, "Cleared all " + count + " fingerprint mappings.");
    }

    private String calculateFingerprint(byte[] publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey);
            return BinaryEncodingUtils.hexEncodedString(hash);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 algorithm not found", e);
            // Devrait ne jamais arriver sur une plateforme Android standard.
            return null;
        }
    }
}
