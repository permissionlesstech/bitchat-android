package com.bitchat.android.noise;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Gère les sessions Noise avec les différents pairs.
 */
public class NoiseSessionManager {

    private static final String TAG = "NoiseSessionManager";

    private final Map<String, NoiseSession> sessions = new ConcurrentHashMap<>();
    private final byte[] localStaticPrivateKey;
    private final byte[] localStaticPublicKey;

    public BiConsumer<String, byte[]> onSessionEstablished;
    public BiConsumer<String, Throwable> onSessionFailed;

    public NoiseSessionManager(byte[] localStaticPrivateKey, byte[] localStaticPublicKey) {
        this.localStaticPrivateKey = localStaticPrivateKey;
        this.localStaticPublicKey = localStaticPublicKey;
    }

    public NoiseSession getSession(String peerID) {
        return sessions.get(peerID);
    }

    public void removeSession(String peerID) {
        NoiseSession session = sessions.remove(peerID);
        if (session != null) {
            session.destroy();
            Log.d(TAG, "Removed session for " + peerID);
        }
    }

    public byte[] initiateHandshake(String peerID) throws SessionError {
        Log.d(TAG, "initiateHandshake(" + peerID + ")");
        removeSession(peerID);
        NoiseSession session = new NoiseSession(peerID, true, localStaticPrivateKey, localStaticPublicKey);
        sessions.put(peerID, session);
        try {
            return session.startHandshake();
        } catch (Exception e) {
            sessions.remove(peerID);
            throw new SessionError.HandshakeFailed();
        }
    }

    public byte[] processHandshakeMessage(String peerID, byte[] message) throws SessionError {
        Log.d(TAG, "processHandshakeMessage(" + peerID + ", " + message.length + " bytes)");
        try {
            NoiseSession session = sessions.get(peerID);
            if (session == null) {
                session = new NoiseSession(peerID, false, localStaticPrivateKey, localStaticPublicKey);
                sessions.put(peerID, session);
            }
            byte[] response = session.processHandshakeMessage(message);
            if (session.isEstablished()) {
                Log.d(TAG, "✅ Session ESTABLISHED with " + peerID);
                if (onSessionEstablished != null) {
                    onSessionEstablished.accept(peerID, session.getRemoteStaticPublicKey());
                }
            }
            return response;
        } catch (Exception e) {
            sessions.remove(peerID);
            if (onSessionFailed != null) {
                onSessionFailed.accept(peerID, e);
            }
            throw new SessionError.HandshakeFailed();
        }
    }

    public byte[] encrypt(byte[] data, String peerID) throws NoiseSessionError, SessionError {
        NoiseSession session = getSession(peerID);
        if (session == null) throw new NoiseSessionError.SessionNotFound();
        if (!session.isEstablished()) throw new NoiseSessionError.SessionNotEstablished();
        return session.encrypt(data);
    }

    public byte[] decrypt(byte[] encryptedData, String peerID) throws NoiseSessionError, SessionError {
        NoiseSession session = getSession(peerID);
        if (session == null) throw new NoiseSessionError.SessionNotFound();
        if (!session.isEstablished()) throw new NoiseSessionError.SessionNotEstablished();
        return session.decrypt(encryptedData);
    }

    public boolean hasEstablishedSession(String peerID) {
        NoiseSession session = getSession(peerID);
        return session != null && session.isEstablished();
    }

    public NoiseSessionState getSessionState(String peerID) {
        NoiseSession session = getSession(peerID);
        return session != null ? session.getState() : new NoiseSessionState.Uninitialized();
    }

    public List<String> getSessionsNeedingRekey() {
        List<String> needsRekey = new ArrayList<>();
        for (Map.Entry<String, NoiseSession> entry : sessions.entrySet()) {
            if (entry.getValue().isEstablished() && entry.getValue().needsRekey()) {
                needsRekey.add(entry.getKey());
            }
        }
        return needsRekey;
    }

    public void shutdown() {
        for (NoiseSession session : sessions.values()) {
            session.destroy();
        }
        sessions.clear();
        Log.d(TAG, "Noise session manager shut down");
    }
}
