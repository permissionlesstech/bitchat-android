package com.bitchat.android.mesh;

import android.util.Log;
import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gère de manière centralisée le relais des paquets.
 */
public class PacketRelayManager {

    private static final String TAG = "PacketRelayManager";
    private final String myPeerID;
    private final ExecutorService relayExecutor = Executors.newSingleThreadExecutor();

    public PacketRelayManagerDelegate delegate;

    public PacketRelayManager(String myPeerID) {
        this.myPeerID = myPeerID;
    }

    public void handlePacketRelay(RoutedPacket routed) {
        relayExecutor.execute(() -> {
            BitchatPacket packet = routed.getPacket();
            String peerID = routed.getPeerID() != null ? routed.getPeerID() : "unknown";

            if (isPacketAddressedToMe(packet) || peerID.equals(myPeerID)) {
                return;
            }

            if (packet.getTtl() == 0) {
                return;
            }

            // Création d'un nouveau paquet avec TTL décrémenté.
            // BitchatPacket a besoin d'un constructeur de copie.
            // BitchatPacket relayPacket = new BitchatPacket(packet, (byte)(packet.getTtl() - 1));

            // La logique de relais irait ici.
            // if (shouldRelayPacket(relayPacket)) {
            //     relayPacket(new RoutedPacket(relayPacket, peerID, routed.getRelayAddress()));
            // }
        });
    }

    private boolean isPacketAddressedToMe(BitchatPacket packet) {
        byte[] recipientID = packet.getRecipientID();
        if (recipientID == null) return false;

        byte[] broadcastRecipient = delegate != null ? delegate.getBroadcastRecipient() : null;
        if (broadcastRecipient != null && Arrays.equals(recipientID, broadcastRecipient)) {
            return false;
        }

        // La comparaison d'ID de pair se ferait ici.
        // return new String(recipientID).trim().equals(myPeerID);
        return false; // Placeholder
    }

    private boolean shouldRelayPacket(BitchatPacket packet) {
        if (packet.getTtl() >= 4) return true;
        int networkSize = delegate != null ? delegate.getNetworkSize() : 1;
        if (networkSize <= 3) return true;

        double relayProb = 0.5; // Logique de probabilité adaptative
        return new Random().nextDouble() < relayProb;
    }

    private void relayPacket(RoutedPacket routed) {
        Log.d(TAG, "🔄 Relaying packet type " + routed.getPacket().getType() + " with TTL " + routed.getPacket().getTtl());
        if (delegate != null) {
            delegate.broadcastPacket(routed);
        }
    }

    public void shutdown() {
        relayExecutor.shutdown();
    }
}
