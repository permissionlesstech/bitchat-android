package com.bitchat.android.nostr;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.bitchat.android.geohash.Geohash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Charge les coordonnées des relais et fournit une recherche du relais le plus proche par geohash.
 */
public final class RelayDirectory {

    private static final String TAG = "RelayDirectory";
    private static volatile boolean initialized = false;
    private static final List<RelayInfo> relays = Collections.synchronizedList(new ArrayList<>());
    private static final ExecutorService ioScope = Executors.newSingleThreadExecutor();

    private RelayDirectory() {}

    public static class RelayInfo {
        public final String url;
        public final double latitude;
        public final double longitude;
        public RelayInfo(String url, double latitude, double longitude) {
            this.url = url;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    public static void initialize(Application application) {
        if (initialized) return;
        synchronized (RelayDirectory.class) {
            if (initialized) return;
            ioScope.execute(() -> {
                try {
                    loadFromAssets(application);
                    initialized = true;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize RelayDirectory", e);
                }
            });
        }
    }

    public static List<String> closestRelaysForGeohash(String geohash, int nRelays) {
        // ... La logique pour trouver les relais les plus proches irait ici.
        return new ArrayList<>();
    }

    private static void loadFromAssets(Application application) {
        try (InputStream is = application.getAssets().open("nostr_relays.csv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            List<RelayInfo> parsedRelays = reader.lines()
                .map(line -> line.split(","))
                .filter(parts -> parts.length >= 3)
                .map(parts -> {
                    try {
                        String url = parts[0].trim();
                        double lat = Double.parseDouble(parts[1].trim());
                        double lon = Double.parseDouble(parts[2].trim());
                        return new RelayInfo(url, lat, lon);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

            synchronized (relays) {
                relays.clear();
                relays.addAll(parsedRelays);
            }
            Log.i(TAG, "Loaded " + relays.size() + " relays from assets.");

        } catch (Exception e) {
            Log.e(TAG, "Failed to load relays from assets", e);
        }
    }
}
