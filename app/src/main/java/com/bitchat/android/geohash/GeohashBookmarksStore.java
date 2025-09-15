package com.bitchat.android.geohash;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Geocoder;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stocke une liste de canaux geohash favoris maintenue par l'utilisateur.
 */
public class GeohashBookmarksStore {

    private static final String TAG = "GeohashBookmarksStore";
    private static final String STORE_KEY = "locationChannel.bookmarks";
    private static final String NAMES_STORE_KEY = "locationChannel.bookmarkNames";

    private static volatile GeohashBookmarksStore INSTANCE;

    private final Context context;
    private final Gson gson = new Gson();
    private final SharedPreferences prefs;
    private final Set<String> membership = new HashSet<>();
    private final MutableLiveData<List<String>> _bookmarks = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<String>> bookmarks = _bookmarks;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private GeohashBookmarksStore(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("geohash_prefs", Context.MODE_PRIVATE);
        load();
    }

    public static GeohashBookmarksStore getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (GeohashBookmarksStore.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GeohashBookmarksStore(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private static String normalize(String raw) {
        return raw.trim().toLowerCase(Locale.US).replace("#", "");
    }

    public boolean isBookmarked(String geohash) {
        return membership.contains(normalize(geohash));
    }

    public void toggle(String geohash) {
        String gh = normalize(geohash);
        if (membership.contains(gh)) {
            remove(gh);
        } else {
            add(gh);
        }
    }

    public void add(String geohash) {
        String gh = normalize(geohash);
        if (gh.isEmpty() || !membership.add(gh)) return;
        List<String> updated = new ArrayList<>(_bookmarks.getValue());
        updated.add(0, gh);
        _bookmarks.postValue(updated);
        persist(updated);
    }

    public void remove(String geohash) {
        String gh = normalize(geohash);
        if (!membership.remove(gh)) return;
        List<String> current = new ArrayList<>(_bookmarks.getValue());
        current.remove(gh);
        _bookmarks.postValue(current);
        persist(current);
    }

    private void load() {
        executor.execute(() -> {
            String json = prefs.getString(STORE_KEY, null);
            if (json != null) {
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                List<String> loaded = gson.fromJson(json, listType);
                if (loaded != null) {
                    membership.addAll(loaded);
                    _bookmarks.postValue(loaded);
                }
            }
        });
    }

    private void persist(List<String> list) {
        executor.execute(() -> {
            String json = gson.toJson(list);
            prefs.edit().putString(STORE_KEY, json).apply();
        });
    }

    public void clearAll() {
        membership.clear();
        _bookmarks.postValue(Collections.emptyList());
        prefs.edit().remove(STORE_KEY).remove(NAMES_STORE_KEY).apply();
    }
}
