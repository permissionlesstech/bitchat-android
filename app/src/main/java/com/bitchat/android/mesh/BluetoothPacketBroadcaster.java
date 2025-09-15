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
                        broadcastSinglePacketInternal(new RoutedPacket(fragment, routed.getRelayAddress()), gattServer, characteristic);
                        try {
                            Thread.sleep(20); // Délai plus court entre les fragments
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

        for (BluetoothConnectionTracker.DeviceConnection conn : connectionTracker.getConnectedDevices().values()) {
            if (conn.device.getAddress().equals(routed.getRelayAddress())) {
                continue; // Ne pas renvoyer au relais
            }

            if (conn.isClient) {
                writeToDeviceConn(conn, data);
            } else {
                notifyDevice(conn.device, data, gattServer, characteristic);
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
            conn.gatt.writeCharacteristic(conn.characteristic);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Error writing to device " + conn.device.getAddress(), e);
            return false;
        }
    }

    public void shutdown() {
        broadcastExecutor.shutdown();
    }
}
