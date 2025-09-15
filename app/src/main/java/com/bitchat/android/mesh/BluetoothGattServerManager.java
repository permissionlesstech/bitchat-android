package com.bitchat.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;
import com.bitchat.android.protocol.BitchatPacket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gère les opérations du serveur GATT, la publicité et les connexions côté serveur.
 */
public class BluetoothGattServerManager {

    private static final String TAG = "BluetoothGattServerManager";
    private static final UUID SERVICE_UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C");
    private static final UUID CHARACTERISTIC_UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D");

    private final Context context;
    private final BluetoothConnectionTracker connectionTracker;
    private final BluetoothPermissionManager permissionManager;
    private final PowerManager powerManager;
    private final BluetoothConnectionManagerDelegate delegate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic characteristic;
    private BluetoothLeAdvertiser bleAdvertiser;
    private AdvertiseCallback advertiseCallback;
    private boolean isActive = false;

    public BluetoothGattServerManager(Context context, BluetoothConnectionTracker connectionTracker, BluetoothPermissionManager permissionManager, PowerManager powerManager, BluetoothConnectionManagerDelegate delegate) {
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

        bleAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (bleAdvertiser == null) return false;

        isActive = true;
        executor.execute(() -> {
            setupGattServer();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            startAdvertising();
        });
        return true;
    }

    public void stop() {
        if (!isActive) return;
        isActive = false;
        executor.execute(() -> {
            stopAdvertising();
            if (gattServer != null) {
                gattServer.close();
                gattServer = null;
            }
        });
    }

    private void setupGattServer() {
        if (!permissionManager.hasBluetoothPermissions()) return;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);

        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        characteristic = new BluetoothGattCharacteristic(CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(characteristic);
        gattServer.addService(service);
    }

    private void startAdvertising() {
        if (!isActive || bleAdvertiser == null) return;

        AdvertiseSettings settings = powerManager.getAdvertiseSettings();
        AdvertiseData data = new AdvertiseData.Builder()
            .addServiceUuid(new ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build();

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "Advertising started successfully.");
            }
            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "Advertising failed: " + errorCode);
            }
        };
        bleAdvertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void stopAdvertising() {
        if (bleAdvertiser != null && advertiseCallback != null) {
            bleAdvertiser.stopAdvertising(advertiseCallback);
            advertiseCallback = null;
        }
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (!isActive) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionTracker.addDeviceConnection(device.getAddress(), new BluetoothConnectionTracker.DeviceConnection(device, null, null));
                if (delegate != null) delegate.onDeviceConnected(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionTracker.cleanupDeviceConnection(device.getAddress());
                if (delegate != null) delegate.onDeviceDisconnected(device);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (!isActive) return;
            if (CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                BitchatPacket packet = BitchatPacket.fromBinaryData(value);
                if (packet != null) {
                    String peerID = new String(packet.getSenderID()).trim(); // Simplified
                    if (delegate != null) delegate.onPacketReceived(packet, peerID, device);
                }
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            }
        }
    };

    public BluetoothGattServer getGattServer() { return gattServer; }
    public BluetoothGattCharacteristic getCharacteristic() { return characteristic; }
}
