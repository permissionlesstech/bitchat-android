package com.bitchat.android.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Pair;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Gère les opérations de persistance des données pour le système de chat.
 */
public class DataManager {

    private static final String TAG = "DataManager";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    private final Map<String, String> _channelCreators = new HashMap<>();
    private final Set<String> _favoritePeers = new HashSet<>();
    private final Set<String> _blockedUsers = new HashSet<>();
    private final Map<String, Set<String>> _channelMembers = new HashMap<>();
    private final Set<String> _geohashBlockedUsers = new HashSet<>();

    // Getters publics pour un accès en lecture seule
    public Map<String, String> getChannelCreators() { return Collections.unmodifiableMap(_channelCreators); }
    public Set<String> getFavoritePeers() { return Collections.unmodifiableSet(_favoritePeers); }
    public Set<String> getBlockedUsers() { return Collections.unmodifiableSet(_blockedUsers); }
    public Map<String, Set<String>> getChannelMembers() { return Collections.unmodifiableMap(_channelMembers); }
    public Set<String> getGeohashBlockedUsers() { return Collections.unmodifiableSet(_geohashBlockedUsers); }


    public DataManager(Context context) {
        this.prefs = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE);
    }

    public String loadNickname() {
        String savedNickname = prefs.getString("nickname", null);
        if (savedNickname != null) {
            return savedNickname;
        } else {
            String randomNickname = "anon" + (new Random().nextInt(9000) + 1000);
            saveNickname(randomNickname);
            return randomNickname;
        }
    }

    public void saveNickname(String nickname) {
        prefs.edit().putString("nickname", nickname).apply();
    }

    public Pair<Set<String>, Set<String>> loadChannelData() {
        Set<String> savedChannels = prefs.getStringSet("joined_channels", new HashSet<>());
        Set<String> savedProtectedChannels = prefs.getStringSet("password_protected_channels", new HashSet<>());
        String creatorsJson = prefs.getString("channel_creators", "{}");
        try {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> creatorsMap = gson.fromJson(creatorsJson, type);
            if (creatorsMap != null) {
                _channelCreators.putAll(creatorsMap);
            }
        } catch (Exception e) {
            // Ignorer les erreurs de parsing
        }
        for (String channel : savedChannels) {
            _channelMembers.computeIfAbsent(channel, k -> new HashSet<>());
        }
        return new Pair<>(savedChannels, savedProtectedChannels);
    }

    public void saveChannelData(Set<String> joinedChannels, Set<String> passwordProtectedChannels) {
        prefs.edit()
            .putStringSet("joined_channels", joinedChannels)
            .putStringSet("password_protected_channels", passwordProtectedChannels)
            .putString("channel_creators", gson.toJson(_channelCreators))
            .apply();
    }

    public void addChannelCreator(String channel, String creatorID) {
        _channelCreators.put(channel, creatorID);
    }

    public void loadFavorites() {
        Set<String> savedFavorites = prefs.getStringSet("favorites", new HashSet<>());
        _favoritePeers.addAll(savedFavorites);
        Log.d(TAG, "Loaded " + savedFavorites.size() + " favorite users from storage: " + savedFavorites);
    }

    public void saveFavorites() {
        prefs.edit().putStringSet("favorites", _favoritePeers).apply();
        Log.d(TAG, "Saved " + _favoritePeers.size() + " favorite users to storage: " + _favoritePeers);
    }

    public void addFavorite(String fingerprint) {
        if (_favoritePeers.add(fingerprint)) {
            saveFavorites();
            logAllFavorites();
        }
    }

    public void removeFavorite(String fingerprint) {
        if (_favoritePeers.remove(fingerprint)) {
            saveFavorites();
            logAllFavorites();
        }
    }

    public boolean isFavorite(String fingerprint) {
        return _favoritePeers.contains(fingerprint);
    }

    public void logAllFavorites() {
        Log.i(TAG, "=== ALL FAVORITE USERS ===");
        Log.i(TAG, "Total favorites: " + _favoritePeers.size());
        for (String fingerprint : _favoritePeers) {
            Log.i(TAG, "Favorite fingerprint: " + fingerprint);
        }
        Log.i(TAG, "========================");
    }

    public void loadBlockedUsers() {
        Set<String> savedBlockedUsers = prefs.getStringSet("blocked_users", new HashSet<>());
        _blockedUsers.addAll(savedBlockedUsers);
    }

    public void saveBlockedUsers() {
        prefs.edit().putStringSet("blocked_users", _blockedUsers).apply();
    }

    public void addBlockedUser(String fingerprint) {
        if (_blockedUsers.add(fingerprint)) {
            saveBlockedUsers();
        }
    }

    public void removeBlockedUser(String fingerprint) {
        if (_blockedUsers.remove(fingerprint)) {
            saveBlockedUsers();
        }
    }

    public boolean isUserBlocked(String fingerprint) {
        return _blockedUsers.contains(fingerprint);
    }

    public void loadGeohashBlockedUsers() {
        Set<String> saved = prefs.getStringSet("geohash_blocked_users", new HashSet<>());
        _geohashBlockedUsers.addAll(saved);
    }

    public void saveGeohashBlockedUsers() {
        prefs.edit().putStringSet("geohash_blocked_users", _geohashBlockedUsers).apply();
    }

    public void addGeohashBlockedUser(String pubkeyHex) {
        if (_geohashBlockedUsers.add(pubkeyHex)) {
            saveGeohashBlockedUsers();
        }
    }

    public void removeGeohashBlockedUser(String pubkeyHex) {
        if (_geohashBlockedUsers.remove(pubkeyHex)) {
            saveGeohashBlockedUsers();
        }
    }

    public boolean isGeohashUserBlocked(String pubkeyHex) {
        return _geohashBlockedUsers.contains(pubkeyHex);
    }

    public void clearAllData() {
        _channelCreators.clear();
        _favoritePeers.clear();
        _blockedUsers.clear();
        _geohashBlockedUsers.clear();
        _channelMembers.clear();
        prefs.edit().clear().apply();
    }
}
