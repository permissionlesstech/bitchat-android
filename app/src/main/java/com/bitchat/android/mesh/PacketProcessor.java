package com.bitchat.android.mesh;

import android.util.Log;
import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;
import com.bitchat.android.protocol.MessageType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Traite les paquets entrants et les route vers les gestionnaires appropriés.
 * Utilise un Executor par pair pour sérialiser le traitement et éviter les race conditions.
 */
public class PacketProcessor {

    private static final String TAG = "PacketProcessor";
    private final String myPeerID;
    private final PacketRelayManager packetRelayManager;
    private final Map<String, ExecutorService> peerExecutors = new ConcurrentHashMap<>();

    public PacketProcessorDelegate delegate;

    public PacketProcessor(String myPeerID) {
        this.myPeerID = myPeerID;
        this.packetRelayManager = new PacketRelayManager(myPeerID);
        setupRelayManager();
    }

    private void setupRelayManager() {
        packetRelayManager.delegate = new PacketRelayManagerDelegate() {
            @Override
            public int getNetworkSize() {
                return delegate != null ? delegate.getNetworkSize() : 1;
            }
            @Override
            public byte[] getBroadcastRecipient() {
                return delegate != null ? delegate.getBroadcastRecipient() : new byte[0];
            }
            @Override
            public void broadcastPacket(RoutedPacket routed) {
                if (delegate != null) delegate.relayPacket(routed);
            }
        };
    }

    public void processPacket(RoutedPacket routed) {
        String peerID = routed.getPeerID();
        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping");
            return;
        }
        ExecutorService executor = peerExecutors.computeIfAbsent(peerID, id -> Executors.newSingleThreadExecutor());
        executor.execute(() -> handleReceivedPacket(routed));
    }

    private void handleReceivedPacket(RoutedPacket routed) {
        if (delegate == null) return;

        BitchatPacket packet = routed.getPacket();
        String peerID = routed.getPeerID();

        if (!delegate.validatePacketSecurity(packet, peerID)) {
            Log.d(TAG, "Packet failed security validation from " + peerID);
            return;
        }

        MessageType messageType = MessageType.fromValue(packet.getType() & 0xFF);
        if (messageType == null) {
            Log.w(TAG, "Unknown message type: " + packet.getType());
            return;
        }

        switch (messageType) {
            case ANNOUNCE:
                delegate.handleAnnounce(routed);
                break;
            case MESSAGE:
                delegate.handleMessage(routed);
                break;
            case LEAVE:
                delegate.handleLeave(routed);
                break;
            case NOISE_HANDSHAKE:
                delegate.handleNoiseHandshake(routed);
                break;
            case NOISE_ENCRYPTED:
                delegate.handleNoiseEncrypted(routed);
                break;
            case FRAGMENT:
                BitchatPacket assembledPacket = delegate.handleFragment(routed);
                if (assembledPacket != null) {
                    processPacket(new RoutedPacket(assembledPacket, routed.getRelayAddress()));
                }
                break;
            case REQUEST_SYNC:
                delegate.handleRequestSync(routed);
                break;
            default:
                Log.w(TAG, "Unhandled message type: " + messageType);
        }

        delegate.updatePeerLastSeen(peerID);
        packetRelayManager.handlePacketRelay(routed);
    }

    public void shutdown() {
        for (ExecutorService executor : peerExecutors.values()) {
            executor.shutdown();
        }
        peerExecutors.clear();
        packetRelayManager.shutdown();
    }
}
