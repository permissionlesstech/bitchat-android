package com.bitchat.android.geohash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gère les permissions de localisation, la récupération de la localisation et le calcul des canaux geohash.
 */
public class LocationChannelManager {

    private static final String TAG = "LocationChannelManager";
    private static volatile LocationChannelManager INSTANCE;

    private final Context context;
    private final LocationManager locationManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final MutableLiveData<List<GeohashChannel>> _availableChannels = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<GeohashChannel>> availableChannels = _availableChannels;

    private LocationChannelManager(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static LocationChannelManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (LocationChannelManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new LocationChannelManager(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public void refreshChannels() {
        if (hasLocationPermission()) {
            requestOneShotLocation();
        }
    }

    private void requestOneShotLocation() {
        if (!hasLocationPermission()) return;

        executor.execute(() -> {
            try {
                // Tenter d'obtenir la dernière position connue
                Location lastKnownLocation = null;
                for (String provider : locationManager.getProviders(true)) {
                    Location l = locationManager.getLastKnownLocation(provider);
                    if (l != null && (lastKnownLocation == null || l.getTime() > lastKnownLocation.getTime())) {
                        lastKnownLocation = l;
                    }
                }

                if (lastKnownLocation != null) {
                    computeChannels(lastKnownLocation);
                } else {
                    // Demander une nouvelle position si aucune n'est connue
                    // La logique pour requestSingleUpdate ou getCurrentLocation irait ici.
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Location permission error", e);
            }
        });
    }

    private void computeChannels(Location location) {
        List<GeohashChannel> result = new ArrayList<>();
        for (GeohashChannelLevel level : GeohashChannelLevel.values()) {
            String geohash = Geohash.encode(location.getLatitude(), location.getLongitude(), level.getPrecision());
            result.add(new GeohashChannel(level, geohash));
        }
        _availableChannels.postValue(result);
    }

    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void cleanup() {
        executor.shutdown();
    }
}
