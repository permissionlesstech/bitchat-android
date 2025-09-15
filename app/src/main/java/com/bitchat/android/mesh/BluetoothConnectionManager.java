package com.bitchat.android.mesh;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;
import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gestionnaire de connexion Bluetooth optimisé pour la consommation d'énergie.
 * Coordonne des composants plus petits et spécialisés pour une meilleure maintenabilité.
 */
public class BluetoothConnectionManager implements PowerManagerDelegate {

    private static final String TAG = "BluetoothConnectionManager";

    private final Context context;
    private final String myPeerID;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final PowerManager powerManager;
    private final BluetoothPermissionManager permissionManager;
    private final BluetoothConnectionTracker connectionTracker;
    private final BluetoothPacketBroadcaster packetBroadcaster;
    private final BluetoothGattServerManager serverManager;
    private final BluetoothGattClientManager clientManager;

    public BluetoothConnectionManagerDelegate delegate;
    private boolean isActive = false;

    public BluetoothConnectionManager(Context context, String myPeerID, FragmentManager fragmentManager) {
        this.context = context;
        this.myPeerID = myPeerID;

        this.powerManager = new PowerManager(context);
        this.permissionManager = new BluetoothPermissionManager(context);
        this.connectionTracker = new BluetoothConnectionTracker(powerManager);
        this.packetBroadcaster = new BluetoothPacketBroadcaster(connectionTracker, fragmentManager);

        // Le délégué interne pour que les composants communiquent entre eux
        BluetoothConnectionManagerDelegate componentDelegate = new BluetoothConnectionManagerDelegate() {
            @Override
            public void onPacketReceived(BitchatPacket packet, String peerID, BluetoothDevice device) {
                if (delegate != null) delegate.onPacketReceived(packet, peerID, device);
            }
            @Override
            public void onDeviceConnected(BluetoothDevice device) {
                if (delegate != null) delegate.onDeviceConnected(device);
            }
            @Override
            public void onDeviceDisconnected(BluetoothDevice device) {
                if (delegate != null) delegate.onDeviceDisconnected(device);
            }
            @Override
            public void onRSSIUpdated(String deviceAddress, int rssi) {
                if (delegate != null) delegate.onRSSIUpdated(deviceAddress, rssi);
            }
        };

        this.serverManager = new BluetoothGattServerManager(context, connectionTracker, permissionManager, powerManager, componentDelegate);
        this.clientManager = new BluetoothGattClientManager(context, connectionTracker, permissionManager, powerManager, componentDelegate);

        this.powerManager.delegate = this;
    }

    public boolean startServices() {
        if (isActive || !permissionManager.hasBluetoothPermissions()) return false;
        isActive = true;
        Log.i(TAG, "Starting Bluetooth connection manager...");

        powerManager.start();
        connectionTracker.start();
        serverManager.start();
        clientManager.start();

        return true;
    }

    public void stopServices() {
        if (!isActive) return;
        isActive = false;
        Log.i(TAG, "Stopping Bluetooth connection manager...");

        clientManager.stop();
        serverManager.stop();
        connectionTracker.stop();
        powerManager.stop();
        executor.shutdown();
    }

    public void broadcastPacket(RoutedPacket routed) {
        if (!isActive) return;
        packetBroadcaster.broadcastPacket(routed, serverManager.getGattServer(), serverManager.getCharacteristic());
    }

    @Override
    public void onPowerModeChanged(PowerManager.PowerMode newMode) {
        Log.i(TAG, "Power mode changed to: " + newMode);
        // La logique pour redémarrer la pub et le scan en fonction du mode irait ici.
        serverManager.restartAdvertising();
        clientManager.restartScanning();
    }

    @Override
    public void onScanStateChanged(boolean shouldScan) {
        clientManager.onScanStateChanged(shouldScan);
    }
}
