package com.bitchat.android.mesh;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.util.Log;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Suit toutes les connexions Bluetooth et gère le nettoyage.
 */
public class BluetoothConnectionTracker {

    private static final String TAG = "BluetoothConnectionTracker";
    private static final long CLEANUP_INTERVAL = 30000L; // 30 secondes

    private final PowerManager powerManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, DeviceConnection> connectedDevices = new ConcurrentHashMap<>();
    public final Map<String, String> addressPeerMap = new ConcurrentHashMap<>();

    public static class DeviceConnection {
        public final BluetoothDevice device;
        public final BluetoothGatt gatt;
        public final BluetoothGattCharacteristic characteristic;
        // ... autres champs
        public DeviceConnection(BluetoothDevice device, BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            this.device = device;
            this.gatt = gatt;
            this.characteristic = characteristic;
        }
    }

    public BluetoothConnectionTracker(PowerManager powerManager) {
        this.powerManager = powerManager;
    }

    public void start() {
        startPeriodicCleanup();
    }

    public void stop() {
        scheduler.shutdown();
        cleanupAllConnections();
    }

    public void addDeviceConnection(String deviceAddress, DeviceConnection deviceConn) {
        connectedDevices.put(deviceAddress, deviceConn);
    }

    public DeviceConnection getDeviceConnection(String deviceAddress) {
        return connectedDevices.get(deviceAddress);
    }

    public Map<String, DeviceConnection> getConnectedDevices() {
        return Collections.unmodifiableMap(connectedDevices);
    }

    public boolean isDeviceConnected(String deviceAddress) {
        return connectedDevices.containsKey(deviceAddress);
    }

    public int getConnectedDeviceCount() {
        return connectedDevices.size();
    }

    public void disconnectDevice(String deviceAddress) {
        DeviceConnection conn = connectedDevices.get(deviceAddress);
        if (conn != null && conn.gatt != null) {
            conn.gatt.disconnect();
        }
        cleanupDeviceConnection(deviceAddress);
    }

    public void cleanupDeviceConnection(String deviceAddress) {
        DeviceConnection conn = connectedDevices.remove(deviceAddress);
        if (conn != null && conn.gatt != null) {
            conn.gatt.close();
        }
        addressPeerMap.remove(deviceAddress);
    }

    private void cleanupAllConnections() {
        for (DeviceConnection conn : connectedDevices.values()) {
            if (conn.gatt != null) {
                conn.gatt.disconnect();
                conn.gatt.close();
            }
        }
        connectedDevices.clear();
        addressPeerMap.clear();
    }

    private void startPeriodicCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            // La logique de nettoyage des connexions expirées irait ici.
        }, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }
}
