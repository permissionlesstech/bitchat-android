package com.bitchat.android.geohash;

/**
 * Niveaux des canaux de localisation mappés aux précisions de geohash.
 * Port direct de l'implémentation iOS pour une compatibilité à 100%.
 */
public enum GeohashChannelLevel {
    BLOCK(7, "Block"),
    NEIGHBORHOOD(6, "Neighborhood"),
    CITY(5, "City"),
    PROVINCE(4, "Province"),
    REGION(2, "REGION");

    private final int precision;
    private final String displayName;

    GeohashChannelLevel(int precision, String displayName) {
        this.precision = precision;
        this.displayName = displayName;
    }

    public int getPrecision() {
        return precision;
    }

    public String getDisplayName() {
        return displayName;
    }
}
