package com.bitchat.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;
import com.bitchat.android.protocol.BitchatPacket;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gère les opérations du client GATT, le scan et les connexions côté client.
 */
public class BluetoothGattClientManager {

    private static final String TAG = "BluetoothGattClientManager";
    private static final UUID SERVICE_UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D");

    private final Context context;
    private final BluetoothConnectionTracker connectionTracker;
    private final BluetoothPermissionManager permissionManager;
    private final PowerManager powerManager;
    private final BluetoothConnectionManagerDelegate delegate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;
    private boolean isActive = false;

    public BluetoothGattClientManager(Context context, BluetoothConnectionTracker connectionTracker, BluetoothPermissionManager permissionManager, PowerManager powerManager, BluetoothConnectionManagerDelegate delegate) {
        this.context = context;
        this.connectionTracker = connectionTracker;
        this.permissionManager = permissionManager;
        this.powerManager = powerManager;
        this.delegate = delegate;
    }

    public boolean start() {
        if (isActive || !permissionManager.hasBluetoothPermissions()) return false;

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return false;

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) return false;

        isActive = true;
        startScanning();
        return true;
    }

    public void stop() {
        if (!isActive) return;
        isActive = false;
        stopScanning();
        executor.shutdown();
    }

    public void onScanStateChanged(boolean shouldScan) {
        if (shouldScan) {
            startScanning();
        } else {
            stopScanning();
        }
    }

    private void startScanning() {
        if (!isActive || bleScanner == null || scanCallback != null) return;

        ScanFilter scanFilter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
        ScanSettings settings = powerManager.getScanSettings();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }
        };
        bleScanner.startScan(Collections.singletonList(scanFilter), settings, scanCallback);
    }

    private void stopScanning() {
        if (bleScanner != null && scanCallback != null) {
            bleScanner.stopScan(scanCallback);
            scanCallback = null;
        }
    }

    private void handleScanResult(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        if (device == null || connectionTracker.isDeviceConnected(device.getAddress())) return;

        // La logique de filtrage RSSI et de limite de connexion irait ici.
        connectToDevice(device, result.getRssi());
    }

    private void connectToDevice(BluetoothDevice device, int rssi) {
        if (!permissionManager.hasBluetoothPermissions()) return;
        connectionTracker.addPendingConnection(device.getAddress());
        device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String address = gatt.getDevice().getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionTracker.cleanupDeviceConnection(address);
                if (delegate != null) delegate.onDeviceDisconnected(gatt.getDevice());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true);
                        // La logique pour écrire sur le descripteur irait ici.
                        connectionTracker.addDeviceConnection(gatt.getDevice().getAddress(), new BluetoothConnectionTracker.DeviceConnection(gatt.getDevice(), gatt, characteristic));
                        if(delegate != null) delegate.onDeviceConnected(gatt.getDevice());
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
             if (CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                BitchatPacket packet = BitchatPacket.fromBinaryData(characteristic.getValue());
                if (packet != null) {
                    String peerID = new String(packet.getSenderID()).trim(); // Simplified
                    if (delegate != null) delegate.onPacketReceived(packet, peerID, gatt.getDevice());
                }
            }
        }
    };
}
