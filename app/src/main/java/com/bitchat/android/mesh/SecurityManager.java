package com.bitchat.android.mesh;

import android.util.Log;
import com.bitchat.android.crypto.EncryptionService;
import com.bitchat.android.model.RoutedPacket;
import com.bitchat.android.protocol.BitchatPacket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gère les aspects de sécurité du réseau maillé, y compris la détection de doublons,
 * la protection contre les attaques par rejeu et la gestion de l'échange de clés.
 */
public class SecurityManager {

    private static final String TAG = "SecurityManager";
    private static final long MESSAGE_TIMEOUT = 300000L; // 5 minutes
    private static final long CLEANUP_INTERVAL = 300000L; // 5 minutes

    private final EncryptionService encryptionService;
    private final String myPeerID;
    private final Set<String> processedMessages = Collections.synchronizedSet(ConcurrentHashMap.newKeySet());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public SecurityManagerDelegate delegate;

    public SecurityManager(EncryptionService encryptionService, String myPeerID) {
        this.encryptionService = encryptionService;
        this.myPeerID = myPeerID;
        startPeriodicCleanup();
    }

    public boolean validatePacket(BitchatPacket packet, String peerID) {
        if (myPeerID.equals(peerID)) return false;
        if (packet.getPayload() == null || packet.getPayload().length == 0) return false;

        // La logique de validation du timestamp et de détection des doublons irait ici.
        String messageID = generateMessageID(packet, peerID);
        if (processedMessages.contains(messageID)) {
            return false;
        }
        processedMessages.add(messageID);

        verifyPacketSignatureWithLogging(packet, peerID);

        return true;
    }

    public void handleNoiseHandshake(RoutedPacket routed) {
        scheduler.execute(() -> {
            BitchatPacket packet = routed.getPacket();
            String peerID = routed.getPeerID();
            if (peerID == null || peerID.equals(myPeerID)) return;

            // La logique de gestion du handshake irait ici.
            try {
                byte[] response = encryptionService.processHandshakeMessage(packet.getPayload(), peerID);
                if (response != null && delegate != null) {
                    delegate.sendHandshakeResponse(peerID, response);
                }
                if (encryptionService.hasEstablishedSession(peerID) && delegate != null) {
                    delegate.onKeyExchangeCompleted(peerID, packet.getPayload());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to process handshake", e);
            }
        });
    }

    private void verifyPacketSignatureWithLogging(BitchatPacket packet, String peerID) {
        if (delegate == null || packet.getSignature() == null) return;

        PeerInfo peerInfo = delegate.getPeerInfo(peerID);
        if (peerInfo == null || peerInfo.getSigningPublicKey() == null) return;

        byte[] dataToVerify = packet.toBinaryDataForSigning();
        if (dataToVerify == null) return;

        boolean isValid = encryptionService.verifyEd25519Signature(packet.getSignature(), dataToVerify, peerInfo.getSigningPublicKey());
        Log.d(TAG, "Signature verification for " + peerID + ": " + (isValid ? "VALID" : "INVALID"));
    }

    private String generateMessageID(BitchatPacket packet, String peerID) {
        // Logique de génération d'ID de message pour la détection de doublons.
        return packet.getTimestamp() + "-" + peerID + "-" + Arrays.hashCode(packet.getPayload());
    }

    private void startPeriodicCleanup() {
        scheduler.scheduleAtFixedRate(this::cleanupOldData, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void cleanupOldData() {
        // La logique de nettoyage des anciens messages traités irait ici.
        // Par exemple, en se basant sur un timestamp de réception.
        if (processedMessages.size() > 10000) {
            // Stratégie de nettoyage simple pour éviter une croissance infinie.
            processedMessages.clear();
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
