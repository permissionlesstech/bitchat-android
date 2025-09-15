package com.bitchat.android.geohash;

import java.util.Objects;

/**
 * Représente une option de canal geohash calculée.
 * Port direct de l'implémentation iOS.
 */
public final class GeohashChannel {

    private final GeohashChannelLevel level;
    private final String geohash;

    public GeohashChannel(GeohashChannelLevel level, String geohash) {
        this.level = level;
        this.geohash = geohash;
    }

    public String getId() {
        return level.name() + "-" + geohash;
    }

    public String getDisplayName() {
        return level.getDisplayName() + " • " + geohash;
    }

    // Getters
    public GeohashChannelLevel getLevel() { return level; }
    public String getGeohash() { return geohash; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GeohashChannel that = (GeohashChannel) o;
        return level == that.level && geohash.equals(that.geohash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, geohash);
    }
}
