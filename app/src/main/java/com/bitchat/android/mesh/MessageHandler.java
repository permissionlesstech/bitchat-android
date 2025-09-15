package com.bitchat.android.mesh;

import android.util.Log;
import com.bitchat.android.model.BitchatMessage;
import com.bitchat.android.model.IdentityAnnouncement;
import com.bitchat.android.model.NoisePayload;
import com.bitchat.android.model.PrivateMessagePacket;
import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;
import com.bitchat.android.protocol.MessageType;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gère le traitement des différents types de messages.
 */
public class MessageHandler {

    private static final String TAG = "MessageHandler";
    private final String myPeerID;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public MessageHandlerDelegate delegate;
    public PacketProcessor packetProcessor;

    public MessageHandler(String myPeerID) {
        this.myPeerID = myPeerID;
    }

    public void handleNoiseEncrypted(RoutedPacket routed) {
        executor.execute(() -> {
            BitchatPacket packet = routed.getPacket();
            String peerID = routed.getPeerID() != null ? routed.getPeerID() : "unknown";

            if (peerID.equals(myPeerID)) return;

            String recipientIDHex = new String(packet.getRecipientID()).trim(); // Simplified
            if (!myPeerID.equals(recipientIDHex)) return;

            try {
                byte[] decryptedData = delegate.decryptFromPeer(packet.getPayload(), peerID);
                if (decryptedData == null) return;

                NoisePayload noisePayload = NoisePayload.decode(decryptedData);
                if (noisePayload == null) return;

                switch (noisePayload.getType()) {
                    case PRIVATE_MESSAGE:
                        PrivateMessagePacket privateMessage = PrivateMessagePacket.decode(noisePayload.getData());
                        if (privateMessage != null) {
                            BitchatMessage message = new BitchatMessage(
                                privateMessage.getMessageID(),
                                delegate.getPeerNickname(peerID),
                                privateMessage.getContent(),
                                new Date(packet.getTimestamp()),
                                false, null, true, delegate.getMyNickname(), peerID, null, null, null, false, null, null
                            );
                            delegate.onMessageReceived(message);
                            sendDeliveryAck(privateMessage.getMessageID(), peerID);
                        }
                        break;
                    // Other cases...
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing Noise encrypted message", e);
            }
        });
    }

    private void sendDeliveryAck(String messageID, String senderPeerID) {
        // ... Logic to create and send delivery ACK packet
    }

    public void handleAnnounce(RoutedPacket routed) {
        executor.execute(() -> {
             BitchatPacket packet = routed.getPacket();
             String peerID = routed.getPeerID() != null ? routed.getPeerID() : "unknown";
             if (peerID.equals(myPeerID)) return;

             IdentityAnnouncement announcement = IdentityAnnouncement.decode(packet.getPayload());
             if (announcement == null) return;

             boolean verified = false;
             if (packet.getSignature() != null) {
                 verified = delegate.verifyEd25519Signature(packet.getSignature(), packet.toBinaryDataForSigning(), announcement.getSigningPublicKey());
             }

             if (verified) {
                 delegate.updatePeerInfo(peerID, announcement.getNickname(), announcement.getNoisePublicKey(), announcement.getSigningPublicKey(), true);
                 delegate.updatePeerIDBinding(peerID, announcement.getNickname(), announcement.getNoisePublicKey(), null);
             }
        });
    }

    public void handleMessage(RoutedPacket routed) {
        // ...
    }

    public void handleLeave(RoutedPacket routed) {
        // ...
    }

    public void shutdown() {
        executor.shutdown();
    }
}
