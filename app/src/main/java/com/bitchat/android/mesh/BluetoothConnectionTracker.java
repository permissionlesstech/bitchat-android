package com.bitchat.android.mesh;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final long CONNECTION_TIMEOUT = 60000L; // 60 secondes

    private final PowerManager powerManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, DeviceConnection> connectedDevices = new ConcurrentHashMap<>();
    private final Set<String> pendingConnections = ConcurrentHashMap.newKeySet();
    public final Map<String, String> addressPeerMap = new ConcurrentHashMap<>();

    public static class DeviceConnection {
        public final BluetoothDevice device;
        public final BluetoothGatt gatt;
        public final BluetoothGattCharacteristic characteristic;
        public long lastActivity;
        public final boolean isClient;

        public DeviceConnection(BluetoothDevice device, BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            this.device = device;
            this.gatt = gatt;
            this.characteristic = characteristic;
            this.lastActivity = System.currentTimeMillis();
            this.isClient = (gatt != null);
        }

        public void updateLastActivity() {
            this.lastActivity = System.currentTimeMillis();
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
        pendingConnections.remove(deviceAddress);
        connectedDevices.put(deviceAddress, deviceConn);
    }

    public void addPendingConnection(String deviceAddress) {
        pendingConnections.add(deviceAddress);
    }

    public boolean isConnectionPending(String deviceAddress) {
        return pendingConnections.contains(deviceAddress);
    }

    public DeviceConnection getDeviceConnection(String deviceAddress) {
        return connectedDevices.get(deviceAddress);
    }

    public Map<String, DeviceConnection> getConnectedDevices() {
        return Collections.unmodifiableMap(connectedDevices);
    }

    public List<BluetoothDevice> getSubscribedDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        for (DeviceConnection conn : connectedDevices.values()) {
            if (!conn.isClient) {
                devices.add(conn.device);
            }
        }
        return devices;
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
        pendingConnections.remove(deviceAddress);
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
        pendingConnections.clear();
        addressPeerMap.clear();
    }

    private void startPeriodicCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, DeviceConnection> entry : connectedDevices.entrySet()) {
                if (now - entry.getValue().lastActivity > CONNECTION_TIMEOUT) {
                    Log.i(TAG, "Device " + entry.getKey() + " timed out. Disconnecting.");
                    disconnectDevice(entry.getKey());
                }
            }
        }, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }
}
