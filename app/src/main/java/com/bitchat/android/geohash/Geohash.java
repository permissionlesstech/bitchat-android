package com.bitchat.android.geohash;

import android.util.Pair;
import java.util.HashMap;
import java.util.Map;

/**
 * Encodeur Geohash léger utilisé pour les canaux de localisation.
 * Port de l'implémentation iOS pour une compatibilité à 100%.
 */
public final class Geohash {

    private Geohash() {}

    private static final char[] BASE32_CHARS = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final Map<Character, Integer> CHAR_TO_VALUE = new HashMap<>();
    static {
        for (int i = 0; i < BASE32_CHARS.length; i++) {
            CHAR_TO_VALUE.put(BASE32_CHARS[i], i);
        }
    }

    public static class Bounds {
        public final double latMin, latMax, lonMin, lonMax;
        public Bounds(double latMin, double latMax, double lonMin, double lonMax) {
            this.latMin = latMin;
            this.latMax = latMax;
            this.lonMin = lonMin;
            this.lonMax = lonMax;
        }
    }

    public static String encode(double latitude, double longitude, int precision) {
        if (precision <= 0) return "";

        double[] latInterval = {-90.0, 90.0};
        double[] lonInterval = {-180.0, 180.0};
        boolean isEven = true;
        int bit = 0;
        int ch = 0;
        StringBuilder geohash = new StringBuilder();

        double lat = Math.max(-90.0, Math.min(latitude, 90.0));
        double lon = Math.max(-180.0, Math.min(longitude, 180.0));

        while (geohash.length() < precision) {
            if (isEven) {
                double mid = (lonInterval[0] + lonInterval[1]) / 2;
                if (lon >= mid) {
                    ch |= (1 << (4 - bit));
                    lonInterval[0] = mid;
                } else {
                    lonInterval[1] = mid;
                }
            } else {
                double mid = (latInterval[0] + latInterval[1]) / 2;
                if (lat >= mid) {
                    ch |= (1 << (4 - bit));
                    latInterval[0] = mid;
                } else {
                    latInterval[1] = mid;
                }
            }
            isEven = !isEven;
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32_CHARS[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return geohash.toString();
    }

    public static Pair<Double, Double> decodeToCenter(String geohash) {
        Bounds b = decodeToBounds(geohash);
        double latCenter = (b.latMin + b.latMax) / 2;
        double lonCenter = (b.lonMin + b.lonMax) / 2;
        return new Pair<>(latCenter, lonCenter);
    }

    public static Bounds decodeToBounds(String geohash) {
        if (geohash == null || geohash.isEmpty()) return new Bounds(0, 0, 0, 0);

        double[] latInterval = {-90.0, 90.0};
        double[] lonInterval = {-180.0, 180.0};
        boolean isEven = true;

        for (char c : geohash.toLowerCase().toCharArray()) {
            Integer cd = CHAR_TO_VALUE.get(c);
            if (cd == null) return new Bounds(0, 0, 0, 0);
            for (int mask : new int[]{16, 8, 4, 2, 1}) {
                if (isEven) {
                    double mid = (lonInterval[0] + lonInterval[1]) / 2;
                    if ((cd & mask) != 0) {
                        lonInterval[0] = mid;
                    } else {
                        lonInterval[1] = mid;
                    }
                } else {
                    double mid = (latInterval[0] + latInterval[1]) / 2;
                    if ((cd & mask) != 0) {
                        latInterval[0] = mid;
                    } else {
                        latInterval[1] = mid;
                    }
                }
                isEven = !isEven;
            }
        }
        return new Bounds(latInterval[0], latInterval[1], lonInterval[0], lonInterval[1]);
    }
}
