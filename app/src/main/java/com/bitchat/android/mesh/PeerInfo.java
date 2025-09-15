package com.bitchat.android.mesh;

import java.util.Arrays;
import java.util.Objects;

/**
 * Structure d'information sur un pair, avec statut de vérification.
 * Compatible avec la structure PeerInfo sur iOS.
 */
public final class PeerInfo {

    private final String id;
    private final String nickname;
    private final boolean isConnected;
    private final boolean isDirectConnection;
    private final byte[] noisePublicKey;
    private final byte[] signingPublicKey;
    private final boolean isVerifiedNickname;
    private final long lastSeen;

    public PeerInfo(String id, String nickname, boolean isConnected, boolean isDirectConnection, byte[] noisePublicKey, byte[] signingPublicKey, boolean isVerifiedNickname, long lastSeen) {
        this.id = id;
        this.nickname = nickname;
        this.isConnected = isConnected;
        this.isDirectConnection = isDirectConnection;
        this.noisePublicKey = noisePublicKey;
        this.signingPublicKey = signingPublicKey;
        this.isVerifiedNickname = isVerifiedNickname;
        this.lastSeen = lastSeen;
    }

    /**
     * Constructeur de copie pour les mises à jour immuables.
     */
    public PeerInfo(PeerInfo other, String newNickname, Long newLastSeen, Boolean newIsConnected, Boolean newIsDirectConnection) {
        this.id = other.id;
        this.nickname = newNickname != null ? newNickname : other.nickname;
        this.isConnected = newIsConnected != null ? newIsConnected : other.isConnected;
        this.isDirectConnection = newIsDirectConnection != null ? newIsDirectConnection : other.isDirectConnection;
        this.noisePublicKey = other.noisePublicKey;
        this.signingPublicKey = other.signingPublicKey;
        this.isVerifiedNickname = other.isVerifiedNickname;
        this.lastSeen = newLastSeen != null ? newLastSeen : other.lastSeen;
    }


    // Getters
    public String getId() { return id; }
    public String getNickname() { return nickname; }
    public boolean isConnected() { return isConnected; }
    public boolean isDirectConnection() { return isDirectConnection; }
    public byte[] getNoisePublicKey() { return noisePublicKey; }
    public byte[] getSigningPublicKey() { return signingPublicKey; }
    public boolean isVerifiedNickname() { return isVerifiedNickname; }
    public long getLastSeen() { return lastSeen; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerInfo peerInfo = (PeerInfo) o;
        return isConnected == peerInfo.isConnected &&
               isDirectConnection == peerInfo.isDirectConnection &&
               isVerifiedNickname == peerInfo.isVerifiedNickname &&
               lastSeen == peerInfo.lastSeen &&
               id.equals(peerInfo.id) &&
               nickname.equals(peerInfo.nickname) &&
               Arrays.equals(noisePublicKey, peerInfo.noisePublicKey) &&
               Arrays.equals(signingPublicKey, peerInfo.signingPublicKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, nickname, isConnected, isDirectConnection, isVerifiedNickname, lastSeen);
        result = 31 * result + Arrays.hashCode(noisePublicKey);
        result = 31 * result + Arrays.hashCode(signingPublicKey);
        return result;
    }
}
