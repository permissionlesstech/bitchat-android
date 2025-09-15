package com.bitchat.android.favorites;

import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

/**
 * Représente la relation de favori entre l'utilisateur et un pair.
 * Fait le pont entre les identités Noise et Nostr.
 */
public final class FavoriteRelationship {

    private final byte[] peerNoisePublicKey;
    private final String peerNostrPublicKey;
    private final String peerNickname;
    private final boolean isFavorite;
    private final boolean theyFavoritedUs;
    private final Date favoritedAt;
    private final Date lastUpdated;

    public FavoriteRelationship(byte[] peerNoisePublicKey, String peerNostrPublicKey, String peerNickname, boolean isFavorite, boolean theyFavoritedUs, Date favoritedAt, Date lastUpdated) {
        this.peerNoisePublicKey = peerNoisePublicKey;
        this.peerNostrPublicKey = peerNostrPublicKey;
        this.peerNickname = peerNickname;
        this.isFavorite = isFavorite;
        this.theyFavoritedUs = theyFavoritedUs;
        this.favoritedAt = favoritedAt;
        this.lastUpdated = lastUpdated;
    }

    public boolean isMutual() {
        return isFavorite && theyFavoritedUs;
    }

    // Getters
    public byte[] getPeerNoisePublicKey() { return peerNoisePublicKey; }
    public String getPeerNostrPublicKey() { return peerNostrPublicKey; }
    public String getPeerNickname() { return peerNickname; }
    public boolean isFavorite() { return isFavorite; }
    public boolean isTheyFavoritedUs() { return theyFavoritedUs; }
    public Date getFavoritedAt() { return favoritedAt; }
    public Date getLastUpdated() { return lastUpdated; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FavoriteRelationship that = (FavoriteRelationship) o;
        return isFavorite == that.isFavorite &&
               theyFavoritedUs == that.theyFavoritedUs &&
               Arrays.equals(peerNoisePublicKey, that.peerNoisePublicKey) &&
               Objects.equals(peerNostrPublicKey, that.peerNostrPublicKey) &&
               Objects.equals(peerNickname, that.peerNickname);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(peerNostrPublicKey, peerNickname, isFavorite, theyFavoritedUs);
        result = 31 * result + Arrays.hashCode(peerNoisePublicKey);
        return result;
    }
}
