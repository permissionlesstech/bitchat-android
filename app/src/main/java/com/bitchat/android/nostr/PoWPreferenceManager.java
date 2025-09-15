package com.bitchat.android.nostr;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.ArrayList;
import java.util.List;
import android.util.Pair;

/**
 * Gère les préférences de Preuve de Travail (Proof of Work) pour les événements Nostr.
 */
public final class PoWPreferenceManager {

    private static final String PREFS_NAME = "pow_preferences";
    private static final String KEY_POW_ENABLED = "pow_enabled";
    private static final String KEY_POW_DIFFICULTY = "pow_difficulty";
    private static final boolean DEFAULT_POW_ENABLED = false;
    private static final int DEFAULT_POW_DIFFICULTY = 12;

    private static final MutableLiveData<Boolean> _powEnabled = new MutableLiveData<>(DEFAULT_POW_ENABLED);
    public static final LiveData<Boolean> powEnabled = _powEnabled;

    private static final MutableLiveData<Integer> _powDifficulty = new MutableLiveData<>(DEFAULT_POW_DIFFICULTY);
    public static final LiveData<Integer> powDifficulty = _powDifficulty;

    private static SharedPreferences sharedPrefs;
    private static boolean isInitialized = false;

    private PoWPreferenceManager() {}

    public static synchronized void init(Context context) {
        if (isInitialized) return;
        sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        _powEnabled.postValue(sharedPrefs.getBoolean(KEY_POW_ENABLED, DEFAULT_POW_ENABLED));
        _powDifficulty.postValue(sharedPrefs.getInt(KEY_POW_DIFFICULTY, DEFAULT_POW_DIFFICULTY));
        isInitialized = true;
    }

    public static class PoWSettings {
        public final boolean enabled;
        public final int difficulty;
        public PoWSettings(boolean enabled, int difficulty) {
            this.enabled = enabled;
            this.difficulty = difficulty;
        }
    }

    public static PoWSettings getCurrentSettings() {
        return new PoWSettings(_powEnabled.getValue(), _powDifficulty.getValue());
    }

    public static void setPowEnabled(boolean enabled) {
        _powEnabled.postValue(enabled);
        if (sharedPrefs != null) {
            sharedPrefs.edit().putBoolean(KEY_POW_ENABLED, enabled).apply();
        }
    }

    public static void setPowDifficulty(int difficulty) {
        int clamped = Math.max(0, Math.min(32, difficulty));
        _powDifficulty.postValue(clamped);
        if (sharedPrefs != null) {
            sharedPrefs.edit().putInt(KEY_POW_DIFFICULTY, clamped).apply();
        }
    }
}
