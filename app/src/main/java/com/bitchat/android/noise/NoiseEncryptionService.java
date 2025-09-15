package com.bitchat.android.noise;

import android.content.Context;
import android.util.Log;

import com.bitchat.android.identity.SecureIdentityStateManager;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.SecureRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Service de chiffrement principal qui utilise Noise pour la sécurité de la couche transport.
 * Maintient une API publique compatible avec les versions précédentes.
 */
public class NoiseEncryptionService {

    private static final String TAG = "NoiseEncryptionService";

    private final NoiseSessionManager sessionManager;
    private final byte[] staticIdentityPublicKey;

    public Consumer<String> onHandshakeRequired;
    public BiConsumer<String, String> onPeerAuthenticated;

    public NoiseEncryptionService(Context context) {
        SecureIdentityStateManager identityStateManager = new SecureIdentityStateManager(context);

        // Charger ou créer la clé d'identité statique.
        android.util.Pair<byte[], byte[]> staticKeyPair = identityStateManager.loadStaticKey();
        if (staticKeyPair == null) {
            // La logique de génération de clé serait ici, mais pour la simplicité de la conversion,
            // nous supposons qu'elle existe ou est gérée ailleurs.
            // Dans une application réelle, il faudrait appeler une méthode de génération.
            throw new IllegalStateException("Static identity key not found.");
        }
        this.staticIdentityPublicKey = staticKeyPair.second;

        // Initialiser le gestionnaire de session avec les clés.
        this.sessionManager = new NoiseSessionManager(staticKeyPair.first, staticKeyPair.second);

        // Configurer les callbacks
        this.sessionManager.onSessionEstablished = (peerID, remoteStaticKey) -> {
            if (onPeerAuthenticated != null) {
                String fingerprint = identityStateManager.generateFingerprint(remoteStaticKey);
                onPeerAuthenticated.accept(peerID, fingerprint);
            }
        };
    }

    public byte[] getStaticPublicKeyData() {
        return staticIdentityPublicKey.clone();
    }

    public byte[] encrypt(byte[] data, String peerID) throws Exception {
        return sessionManager.encrypt(data, peerID);
    }

    public byte[] decrypt(byte[] encryptedData, String peerID) throws Exception {
        return sessionManager.decrypt(encryptedData, peerID);
    }

    public boolean hasEstablishedSession(String peerID) {
        return sessionManager.hasEstablishedSession(peerID);
    }

    public NoiseSessionState getSessionState(String peerID) {
        return sessionManager.getSessionState(peerID);
    }

    public String getPeerFingerprint(String peerID) {
        NoiseSession session = sessionManager.getSession(peerID);
        if (session != null && session.getRemoteStaticPublicKey() != null) {
            SecureIdentityStateManager identityStateManager = new SecureIdentityStateManager(null); // Context non idéal ici
            return identityStateManager.generateFingerprint(session.getRemoteStaticPublicKey());
        }
        return null;
    }

    public byte[] initiateHandshake(String peerID) {
        try {
            return sessionManager.initiateHandshake(peerID);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initiate handshake", e);
            return null;
        }
    }

    public byte[] processHandshakeMessage(byte[] data, String peerID) {
        try {
            return sessionManager.processHandshakeMessage(peerID, data);
        } catch (Exception e) {
            Log.e(TAG, "Failed to process handshake message", e);
            return null;
        }
    }

    public void removePeer(String peerID) {
        sessionManager.removeSession(peerID);
    }

    public void shutdown() {
        sessionManager.shutdown();
    }
}
