package com.bitchat.android.mesh;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.util.Log;
import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gère la diffusion de paquets aux appareils connectés en utilisant un exécuteur pour la sérialisation.
 */
public class BluetoothPacketBroadcaster {

    private static final String TAG = "BluetoothPacketBroadcaster";

    private final BluetoothConnectionTracker connectionTracker;
    private final FragmentManager fragmentManager;
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor();

    public BluetoothPacketBroadcaster(BluetoothConnectionTracker connectionTracker, FragmentManager fragmentManager) {
        this.connectionTracker = connectionTracker;
        this.fragmentManager = fragmentManager;
    }

    public void broadcastPacket(RoutedPacket routed, BluetoothGattServer gattServer, BluetoothGattCharacteristic characteristic) {
        broadcastExecutor.execute(() -> {
            BitchatPacket packet = routed.getPacket();
            if (fragmentManager != null) {
                List<BitchatPacket> fragments = fragmentManager.createFragments(packet);
                if (fragments.size() > 1) {
                    for (BitchatPacket fragment : fragments) {
                        broadcastSinglePacketInternal(new RoutedPacket(fragment), gattServer, characteristic);
                        try {
                            Thread.sleep(200); // Délai entre les fragments
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return;
                }
            }
            broadcastSinglePacketInternal(routed, gattServer, characteristic);
        });
    }

    private void broadcastSinglePacketInternal(RoutedPacket routed, BluetoothGattServer gattServer, BluetoothGattCharacteristic characteristic) {
        byte[] data = routed.getPacket().toBinaryData();
        if (data == null) return;

        // Diffuser aux appareils abonnés (connexions serveur)
        for (BluetoothDevice device : connectionTracker.getSubscribedDevices()) {
            if (!device.getAddress().equals(routed.getRelayAddress())) {
                notifyDevice(device, data, gattServer, characteristic);
            }
        }

        // Diffuser aux appareils connectés (connexions client)
        for (BluetoothConnectionTracker.DeviceConnection conn : connectionTracker.getConnectedDevices().values()) {
            if (conn.isClient && !conn.device.getAddress().equals(routed.getRelayAddress())) {
                writeToDeviceConn(conn, data);
            }
        }
    }

    private boolean notifyDevice(BluetoothDevice device, byte[] data, BluetoothGattServer gattServer, BluetoothGattCharacteristic characteristic) {
        if (gattServer == null || characteristic == null) return false;
        try {
            characteristic.setValue(data);
            return gattServer.notifyCharacteristicChanged(device, characteristic, false);
        } catch (Exception e) {
            Log.w(TAG, "Error notifying device " + device.getAddress(), e);
            return false;
        }
    }

    private boolean writeToDeviceConn(BluetoothConnectionTracker.DeviceConnection conn, byte[] data) {
        if (conn.gatt == null || conn.characteristic == null) return false;
        try {
            conn.characteristic.setValue(data);
            return conn.gatt.writeCharacteristic(conn.characteristic);
        } catch (Exception e) {
            Log.w(TAG, "Error writing to device " + conn.device.getAddress(), e);
            return false;
        }
    }

    public void shutdown() {
        broadcastExecutor.shutdown();
    }
}
