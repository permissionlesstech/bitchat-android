package com.bitchat.android.noise;

import android.util.Log;
import com.bitchat.android.noise.southernstorm.protocol.*;
import java.util.Arrays;

/**
 * Gère une session Noise individuelle pour un pair spécifique.
 * Compatible avec l'implémentation Noise Protocol de Bitchat sur iOS.
 */
public class NoiseSession {

    private static final String TAG = "NoiseSession";
    private static final String PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256";
    private static final int REKEY_MESSAGE_LIMIT = 10000;
    private static final long REKEY_TIME_LIMIT = 3600000L; // 1 heure

    private final String peerID;
    private final boolean isInitiator;
    private final byte[] localStaticPrivateKey;
    private final byte[] localStaticPublicKey;

    private HandshakeState handshakeState;
    private CipherState sendCipher;
    private CipherState receiveCipher;
    private NoiseSessionState state = new NoiseSessionState.Uninitialized();
    private final long creationTime = System.currentTimeMillis();
    private long messagesSent = 0L;
    private long messagesReceived = 0L;
    private byte[] remoteStaticPublicKey;

    private final Object cipherLock = new Object();

    public NoiseSession(String peerID, boolean isInitiator, byte[] localStaticPrivateKey, byte[] localStaticPublicKey) {
        this.peerID = peerID;
        this.isInitiator = isInitiator;
        this.localStaticPrivateKey = localStaticPrivateKey;
        this.localStaticPublicKey = localStaticPublicKey;
    }

    public synchronized byte[] startHandshake() throws SessionError {
        if (!isInitiator) throw new IllegalStateException("Only initiator can start handshake");
        if (!(state instanceof NoiseSessionState.Uninitialized)) throw new IllegalStateException("Handshake already started");

        try {
            initializeNoiseHandshake(HandshakeState.INITIATOR);
            state = new NoiseSessionState.Handshaking();

            byte[] messageBuffer = new byte[32]; // XX_MESSAGE_1_SIZE
            int messageLength = handshakeState.writeMessage(messageBuffer, 0, null, 0, 0);
            return Arrays.copyOf(messageBuffer, messageLength);
        } catch (Exception e) {
            state = new NoiseSessionState.Failed(e);
            throw new SessionError.HandshakeFailed();
        }
    }

    public synchronized byte[] processHandshakeMessage(byte[] message) throws SessionError {
        try {
            if (state instanceof NoiseSessionState.Uninitialized && !isInitiator) {
                initializeNoiseHandshake(HandshakeState.RESPONDER);
                state = new NoiseSessionState.Handshaking();
            }

            if (!(state instanceof NoiseSessionState.Handshaking)) {
                throw new IllegalStateException("Invalid state for handshake: " + state);
            }

            byte[] payloadBuffer = new byte[256]; // Buffer for any payload
            handshakeState.readMessage(message, 0, message.length, payloadBuffer, 0);

            if (handshakeState.getAction() == HandshakeState.WRITE_MESSAGE) {
                byte[] responseBuffer = new byte[96]; // XX_MESSAGE_2_SIZE
                int responseLength = handshakeState.writeMessage(responseBuffer, 0, null, 0, 0);
                completeHandshakeIfNeeded();
                return Arrays.copyOf(responseBuffer, responseLength);
            } else if (handshakeState.getAction() == HandshakeState.SPLIT) {
                completeHandshakeIfNeeded();
                return null;
            }
            return null;
        } catch (Exception e) {
            state = new NoiseSessionState.Failed(e);
            throw new SessionError.HandshakeFailed();
        }
    }

    public byte[] encrypt(byte[] data) throws SessionError {
        if (!(state instanceof NoiseSessionState.Established)) throw new SessionError.NotEstablished();
        synchronized (cipherLock) {
            try {
                byte[] ciphertext = new byte[data.length + sendCipher.getMacLength()];
                sendCipher.setNonce(messagesSent);
                int len = sendCipher.encryptWithAd(null, data, 0, ciphertext, 0, data.size());
                messagesSent++;
                return Arrays.copyOf(ciphertext, len);
            } catch (Exception e) {
                throw new SessionError.EncryptionFailed();
            }
        }
    }

    public byte[] decrypt(byte[] data) throws SessionError {
        if (!(state instanceof NoiseSessionState.Established)) throw new SessionError.NotEstablished();
        synchronized (cipherLock) {
            try {
                byte[] plaintext = new byte[data.length];
                // Nonce handling for replay protection would go here.
                // For simplicity, we assume nonce is managed externally for now.
                // A real implementation needs a robust nonce and replay-attack handling.
                receiveCipher.setNonce(messagesReceived); // Simplified nonce management
                int len = receiveCipher.decryptWithAd(null, data, 0, plaintext, 0, data.length);
                messagesReceived++;
                return Arrays.copyOf(plaintext, len);
            } catch (Exception e) {
                throw new SessionError.DecryptionFailed();
            }
        }
    }

    private void initializeNoiseHandshake(int role) throws Exception {
        handshakeState = new HandshakeState(PROTOCOL_NAME, role);
        if (handshakeState.needsLocalKeyPair()) {
            DHState localKeyPair = handshakeState.getLocalKeyPair();
            localKeyPair.setPrivateKey(localStaticPrivateKey, 0);
        }
        handshakeState.start();
    }

    private void completeHandshakeIfNeeded() {
        if (handshakeState != null && handshakeState.getAction() == HandshakeState.SPLIT) {
             try {
                CipherStatePair cipherPair = handshakeState.split();
                sendCipher = cipherPair.getSender();
                receiveCipher = cipherPair.getReceiver();
                if (handshakeState.hasRemotePublicKey()) {
                    DHState remoteDH = handshakeState.getRemotePublicKey();
                    remoteStaticPublicKey = new byte[32];
                    remoteDH.getPublicKey(remoteStaticPublicKey, 0);
                }
                handshakeState.destroy();
                handshakeState = null;
                state = new NoiseSessionState.Established();
            } catch (Exception e) {
                state = new NoiseSessionState.Failed(e);
            }
        }
    }

    public boolean isEstablished() {
        return state instanceof NoiseSessionState.Established;
    }

    public NoiseSessionState getState() {
        return state;
    }

    public byte[] getRemoteStaticPublicKey() {
        return remoteStaticPublicKey;
    }

    public boolean needsRekey() {
        if (!isEstablished()) return false;
        boolean timeLimit = (System.currentTimeMillis() - creationTime) > REKEY_TIME_LIMIT;
        boolean messageLimit = (messagesSent + messagesReceived) > REKEY_MESSAGE_LIMIT;
        return timeLimit || messageLimit;
    }

    public void destroy() {
        if (sendCipher != null) sendCipher.destroy();
        if (receiveCipher != null) receiveCipher.destroy();
        if (handshakeState != null) handshakeState.destroy();
        state = new NoiseSessionState.Failed(new Exception("Session destroyed"));
    }
}
