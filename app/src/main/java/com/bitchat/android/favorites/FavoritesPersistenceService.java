package com.bitchat.android.favorites;

import android.content.Context;
import android.util.Log;
import com.bitchat.android.identity.SecureIdentityStateManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère les favoris avec le mappage Noise↔Nostr.
 * Implémentation du pattern Singleton.
 */
public final class FavoritesPersistenceService {

    private static final String TAG = "FavoritesPersistenceService";
    private static final String FAVORITES_KEY = "favorite_relationships";

    private static volatile FavoritesPersistenceService INSTANCE;

    private final SecureIdentityStateManager stateManager;
    private final Gson gson = new Gson();
    private final Map<String, FavoriteRelationship> favorites = new ConcurrentHashMap<>();
    private final List<FavoritesChangeListener> listeners = Collections.synchronizedList(new ArrayList<>());

    private FavoritesPersistenceService(Context context) {
        this.stateManager = new SecureIdentityStateManager(context);
        loadFavorites();
    }

    public static FavoritesPersistenceService getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("FavoritesPersistenceService not initialized");
        }
        return INSTANCE;
    }

    public static void initialize(Context context) {
        if (INSTANCE == null) {
            synchronized (FavoritesPersistenceService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FavoritesPersistenceService(context.getApplicationContext());
                }
            }
        }
    }

    public FavoriteRelationship getFavoriteStatus(byte[] noisePublicKey) {
        String keyHex = com.bitchat.android.util.BinaryEncodingUtils.hexEncodedString(noisePublicKey);
        return favorites.get(keyHex);
    }

    public void updateFavoriteStatus(byte[] noisePublicKey, String nickname, boolean isFavorite) {
        String keyHex = com.bitchat.android.util.BinaryEncodingUtils.hexEncodedString(noisePublicKey);
        FavoriteRelationship existing = favorites.get(keyHex);
        Date now = new Date();
        FavoriteRelationship updated;
        if (existing != null) {
            updated = new FavoriteRelationship(noisePublicKey, existing.getPeerNostrPublicKey(), nickname, isFavorite, existing.isTheyFavoritedUs(), isFavorite && !existing.isFavorite() ? now : existing.getFavoritedAt(), now);
        } else {
            updated = new FavoriteRelationship(noisePublicKey, null, nickname, isFavorite, false, now, now);
        }
        favorites.put(keyHex, updated);
        saveFavorites();
        notifyChanged(keyHex);
    }

    private void loadFavorites() {
        try {
            String json = stateManager.getSecureValue(FAVORITES_KEY);
            if (json != null) {
                Type type = new TypeToken<Map<String, FavoriteRelationshipData>>() {}.getType();
                Map<String, FavoriteRelationshipData> data = gson.fromJson(json, type);
                favorites.clear();
                for (Map.Entry<String, FavoriteRelationshipData> entry : data.entrySet()) {
                    favorites.put(entry.getKey(), entry.getValue().toFavoriteRelationship());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load favorites", e);
        }
    }

    private void saveFavorites() {
        try {
            Map<String, FavoriteRelationshipData> data = new HashMap<>();
            for (Map.Entry<String, FavoriteRelationship> entry : favorites.entrySet()) {
                data.put(entry.getKey(), FavoriteRelationshipData.fromFavoriteRelationship(entry.getValue()));
            }
            String json = gson.toJson(data);
            stateManager.storeSecureValue(FAVORITES_KEY, json);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save favorites", e);
        }
    }

    private void notifyChanged(String noiseKeyHex) {
        synchronized(listeners) {
            for(FavoritesChangeListener listener : listeners) {
                listener.onFavoriteChanged(noiseKeyHex);
            }
        }
    }

    // Classe de données privée pour la sérialisation JSON
    private static class FavoriteRelationshipData {
        String peerNoisePublicKeyHex;
        String peerNostrPublicKey;
        String peerNickname;
        boolean isFavorite;
        boolean theyFavoritedUs;
        long favoritedAt;
        long lastUpdated;

        static FavoriteRelationshipData fromFavoriteRelationship(FavoriteRelationship rel) {
            FavoriteRelationshipData data = new FavoriteRelationshipData();
            data.peerNoisePublicKeyHex = com.bitchat.android.util.BinaryEncodingUtils.hexEncodedString(rel.getPeerNoisePublicKey());
            data.peerNostrPublicKey = rel.getPeerNostrPublicKey();
            data.peerNickname = rel.getPeerNickname();
            data.isFavorite = rel.isFavorite();
            data.theyFavoritedUs = rel.isTheyFavoritedUs();
            data.favoritedAt = rel.getFavoritedAt().getTime();
            data.lastUpdated = rel.getLastUpdated().getTime();
            return data;
        }

        FavoriteRelationship toFavoriteRelationship() {
            byte[] noiseKey = com.bitchat.android.util.BinaryEncodingUtils.dataFromHexString(peerNoisePublicKeyHex);
            return new FavoriteRelationship(noiseKey, peerNostrPublicKey, peerNickname, isFavorite, theyFavoritedUs, new Date(favoritedAt), new Date(lastUpdated));
        }
    }
}
