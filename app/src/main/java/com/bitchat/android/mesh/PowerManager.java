package com.bitchat.android.mesh;

import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gère la consommation d'énergie en adaptant le comportement Bluetooth à l'état de la batterie.
 */
public class PowerManager {

    private static final String TAG = "PowerManager";

    public enum PowerMode {
        PERFORMANCE, BALANCED, POWER_SAVER, ULTRA_LOW_POWER
    }

    private final Context context;
    private PowerMode currentMode = PowerMode.BALANCED;
    private boolean isCharging = false;
    private int batteryLevel = 100;
    private boolean isAppInBackground = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> dutyCycleFuture;

    public PowerManagerDelegate delegate;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level != -1 && scale != -1) {
                    batteryLevel = (level * 100) / scale;
                }
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                isCharging = true;
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                isCharging = false;
            }
            updatePowerMode();
        }
    };

    public PowerManager(Context context) {
        this.context = context;
        registerBatteryReceiver();
        updatePowerMode();
    }

    public void start() {
        Log.i(TAG, "Starting power management");
        startDutyCycle();
    }

    public void stop() {
        Log.i(TAG, "Stopping power management");
        scheduler.shutdown();
        unregisterBatteryReceiver();
    }

    public void setAppBackgroundState(boolean inBackground) {
        if (isAppInBackground != inBackground) {
            isAppInBackground = inBackground;
            updatePowerMode();
        }
    }

    public ScanSettings getScanSettings() {
        ScanSettings.Builder builder = new ScanSettings.Builder();
        // La logique pour configurer les ScanSettings en fonction du currentMode irait ici.
        return builder.build();
    }

    public AdvertiseSettings getAdvertiseSettings() {
        AdvertiseSettings.Builder builder = new AdvertiseSettings.Builder();
        // La logique pour configurer les AdvertiseSettings en fonction du currentMode irait ici.
        return builder.build();
    }

    private void updatePowerMode() {
        PowerMode newMode;
        if (isCharging && !isAppInBackground) {
            newMode = PowerMode.PERFORMANCE;
        } else if (batteryLevel <= 10) {
            newMode = PowerMode.ULTRA_LOW_POWER;
        } else if (batteryLevel <= 20) {
            newMode = PowerMode.POWER_SAVER;
        } else {
            newMode = PowerMode.BALANCED;
        }

        if (newMode != currentMode) {
            currentMode = newMode;
            Log.i(TAG, "Power mode changed to: " + newMode);
            if (delegate != null) {
                delegate.onPowerModeChanged(newMode);
            }
            startDutyCycle();
        }
    }

    private void startDutyCycle() {
        if (dutyCycleFuture != null) {
            dutyCycleFuture.cancel(false);
        }
        if (currentMode == PowerMode.PERFORMANCE) {
            if (delegate != null) delegate.onScanStateChanged(true);
            return;
        }
        // La logique pour le cycle de scan (on/off) avec le scheduler irait ici.
    }

    private void registerBatteryReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            context.registerReceiver(batteryReceiver, filter);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register battery receiver", e);
        }
    }

    private void unregisterBatteryReceiver() {
        try {
            context.unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister battery receiver", e);
        }
    }
}
