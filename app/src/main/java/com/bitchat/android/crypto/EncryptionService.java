package com.bitchat.android.crypto;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.bitchat.android.identity.SecureIdentityStateManager;
import com.bitchat.android.noise.NoiseEncryptionService;
import com.bitchat.android.noise.NoiseSessionState;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.Consumer;

/**
 * Service de chiffrement qui utilise maintenant NoiseEncryptionService en interne.
 * Maintient la même API publique pour la compatibilité ascendante.
 */
public class EncryptionService {

    private static final String TAG = "EncryptionService";

    private final NoiseEncryptionService noiseService;
    private final Ed25519PrivateKeyParameters ed25519PrivateKey;
    private final Ed25519PublicKeyParameters ed25519PublicKey;
    private final SecureIdentityStateManager identityManager;

    public Consumer<String> onHandshakeRequired;

    public EncryptionService(Context context) {
        this.identityManager = new SecureIdentityStateManager(context);
        this.noiseService = new NoiseEncryptionService(context, identityManager);

        // Gérer les callbacks
        this.noiseService.onHandshakeRequired = peerID -> {
            if (onHandshakeRequired != null) {
                onHandshakeRequired.accept(peerID);
            }
        };

        // Charger ou créer la paire de clés de signature Ed25519
        android.util.Pair<byte[], byte[]> signingKeys = identityManager.loadSigningKey();
        if (signingKeys != null) {
            this.ed25519PrivateKey = new Ed25519PrivateKeyParameters(signingKeys.first, 0);
            this.ed25519PublicKey = new Ed25519PublicKeyParameters(signingKeys.second, 0);
        } else {
            Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
            keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
            AsymmetricCipherKeyPair keyPair = keyGen.generateKeyPair();
            this.ed25519PrivateKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
            this.ed25519PublicKey = (Ed25519PublicKeyParameters) keyPair.getPublic();
            identityManager.saveSigningKey(this.ed25519PrivateKey.getEncoded(), this.ed25519PublicKey.getEncoded());
        }
    }

    public byte[] getStaticPublicKey() {
        return noiseService.getStaticPublicKeyData();
    }

    public byte[] getSigningPublicKey() {
        return ed25519PublicKey.getEncoded();
    }

    public byte[] signData(byte[] data) {
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, ed25519PrivateKey);
            signer.update(data, 0, data.length);
            return signer.generateSignature();
        } catch (Exception e) {
            Log.e(TAG, "Failed to sign data", e);
            return null;
        }
    }

    public boolean verifyEd25519Signature(byte[] signature, byte[] data, byte[] publicKeyBytes) {
         try {
            Ed25519PublicKeyParameters publicKey = new Ed25519PublicKeyParameters(publicKeyBytes, 0);
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, publicKey);
            verifier.update(data, 0, data.length);
            return verifier.verifySignature(signature);
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify signature", e);
            return false;
        }
    }

    public byte[] encrypt(byte[] data, String peerID) throws Exception {
        return noiseService.encrypt(data, peerID);
    }

    public byte[] decrypt(byte[] data, String peerID) throws Exception {
        return noiseService.decrypt(data, peerID);
    }

    public boolean hasEstablishedSession(String peerID) {
        return noiseService.hasEstablishedSession(peerID);
    }

    public NoiseSessionState getSessionState(String peerID) {
        return noiseService.getSessionState(peerID);
    }

    public byte[] initiateHandshake(String peerID) {
        return noiseService.initiateHandshake(peerID);
    }

    public byte[] processHandshakeMessage(byte[] data, String peerID) {
        return noiseService.processHandshakeMessage(data, peerID);
    }

    public String getIdentityFingerprint() {
        try {
            byte[] publicKey = getStaticPublicKey();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey);
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate identity fingerprint", e);
            return null;
        }
    }

    public void clearPersistentIdentity() {
        identityManager.clearAll();
        noiseService.clearAllSessions();
    }
}
