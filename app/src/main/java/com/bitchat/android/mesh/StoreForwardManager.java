package com.bitchat.android.mesh;

import android.util.Log;
import com.bitchat.android.protocol.BitchatPacket;
import com.bitchat.android.protocol.MessageType;
import com.bitchat.android.protocol.SpecialRecipients;
import com.bitchat.android.util.BinaryEncodingUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gère la messagerie en mode "store-and-forward" pour les pairs hors ligne.
 */
public class StoreForwardManager {

    private static final String TAG = "StoreForwardManager";
    private static final long MESSAGE_CACHE_TIMEOUT = 43200000L; // 12 heures
    private static final int MAX_CACHED_MESSAGES = 100;
    private static final long CLEANUP_INTERVAL = 600000L; // 10 minutes

    private static class StoredMessage {
        final BitchatPacket packet;
        final long timestamp;
        StoredMessage(BitchatPacket packet, long timestamp) {
            this.packet = packet;
            this.timestamp = timestamp;
        }
    }

    private final List<StoredMessage> messageCache = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> deliveredMessages = Collections.synchronizedSet(new HashSet<>());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public StoreForwardManagerDelegate delegate;

    public StoreForwardManager() {
        startPeriodicCleanup();
    }

    public void cacheMessage(BitchatPacket packet, String messageID) {
        if (packet.getType() == MessageType.ANNOUNCE.getValue() || packet.getType() == MessageType.LEAVE.getValue()) {
            return;
        }
        if (packet.getRecipientID() != null && Arrays.equals(packet.getRecipientID(), SpecialRecipients.BROADCAST)) {
            return;
        }
        messageCache.add(new StoredMessage(packet, System.currentTimeMillis()));
    }

    public void sendCachedMessages(String peerID) {
        scheduler.execute(() -> {
            List<StoredMessage> messagesToSend = new ArrayList<>();
            synchronized (messageCache) {
                messageCache.removeIf(storedMessage -> {
                    String recipient = BinaryEncodingUtils.hexEncodedString(storedMessage.packet.getRecipientID());
                    if (peerID.equals(recipient)) {
                        messagesToSend.add(storedMessage);
                        return true;
                    }
                    return false;
                });
            }

            if (!messagesToSend.isEmpty()) {
                Log.i(TAG, "Sending " + messagesToSend.size() + " cached messages to " + peerID);
                messagesToSend.sort((m1, m2) -> Long.compare(m1.timestamp, m2.timestamp));
                for (StoredMessage storedMessage : messagesToSend) {
                    if (delegate != null) {
                        delegate.sendPacket(storedMessage.packet);
                    }
                    try {
                        Thread.sleep(100); // Délai pour éviter de surcharger la connexion
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }

    private void startPeriodicCleanup() {
        scheduler.scheduleAtFixedRate(this::cleanupMessageCache, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void cleanupMessageCache() {
        long cutoffTime = System.currentTimeMillis() - MESSAGE_CACHE_TIMEOUT;
        synchronized (messageCache) {
            messageCache.removeIf(storedMessage -> storedMessage.timestamp < cutoffTime);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
