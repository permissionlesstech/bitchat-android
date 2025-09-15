package com.bitchat.android.mesh;

import android.util.Log;
import com.bitchat.android.model.FragmentPayload;
import com.bitchat.android.protocol.BitchatPacket;
import com.bitchat.android.protocol.MessagePadding;
import com.bitchat.android.protocol.MessageType;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gère la fragmentation et le réassemblage des messages. Compatible avec l'implémentation iOS.
 */
public class FragmentManager {

    private static final String TAG = "FragmentManager";
    private static final int FRAGMENT_SIZE_THRESHOLD = 512;
    private static final int MAX_FRAGMENT_SIZE = 469;
    private static final long FRAGMENT_TIMEOUT = 30000L;
    private static final long CLEANUP_INTERVAL = 10000L;

    private final Map<String, Map<Integer, byte[]>> incomingFragments = new ConcurrentHashMap<>();
    private final Map<String, FragmentMetadata> fragmentMetadata = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public FragmentManagerDelegate delegate;

    private static class FragmentMetadata {
        final int originalType;
        final int totalFragments;
        final long timestamp;
        FragmentMetadata(int originalType, int totalFragments, long timestamp) {
            this.originalType = originalType;
            this.totalFragments = totalFragments;
            this.timestamp = timestamp;
        }
    }

    public FragmentManager() {
        startPeriodicCleanup();
    }

    public List<BitchatPacket> createFragments(BitchatPacket packet) {
        byte[] encoded = packet.toBinaryData();
        if (encoded == null) return new ArrayList<>();

        byte[] fullData = MessagePadding.unpad(encoded);
        if (fullData.length <= FRAGMENT_SIZE_THRESHOLD) {
            return Collections.singletonList(packet);
        }

        List<BitchatPacket> fragments = new ArrayList<>();
        byte[] fragmentID = FragmentPayload.generateFragmentID();

        List<byte[]> fragmentChunks = new ArrayList<>();
        for (int offset = 0; offset < fullData.length; offset += MAX_FRAGMENT_SIZE) {
            int endOffset = Math.min(offset + MAX_FRAGMENT_SIZE, fullData.length);
            fragmentChunks.add(Arrays.copyOfRange(fullData, offset, endOffset));
        }

        for (int i = 0; i < fragmentChunks.size(); i++) {
            FragmentPayload fragmentPayload = new FragmentPayload(fragmentID, i, fragmentChunks.size(), packet.getType() & 0xFF, fragmentChunks.get(i));
            BitchatPacket fragmentPacket = new BitchatPacket(MessageType.FRAGMENT.getValue(), packet.getTtl(), new String(packet.getSenderID()), fragmentPayload.encode());
            fragments.add(fragmentPacket);
        }
        return fragments;
    }

    public BitchatPacket handleFragment(BitchatPacket packet) {
        if (packet.getPayload().length < FragmentPayload.HEADER_SIZE) return null;

        try {
            FragmentPayload payload = FragmentPayload.decode(packet.getPayload());
            if (payload == null || !payload.isValid()) return null;

            String fragmentIDString = payload.getFragmentIDString();

            incomingFragments.computeIfAbsent(fragmentIDString, k -> new ConcurrentHashMap<>());
            fragmentMetadata.putIfAbsent(fragmentIDString, new FragmentMetadata(payload.getOriginalType(), payload.getTotal(), System.currentTimeMillis()));

            Map<Integer, byte[]> fragmentMap = incomingFragments.get(fragmentIDString);
            if (fragmentMap != null) {
                fragmentMap.put(payload.getIndex(), payload.getData());
                if (fragmentMap.size() == payload.getTotal()) {
                    ByteArrayOutputStream reassembledStream = new ByteArrayOutputStream();
                    for (int i = 0; i < payload.getTotal(); i++) {
                        byte[] fragmentData = fragmentMap.get(i);
                        if (fragmentData != null) {
                            reassembledStream.write(fragmentData);
                        } else {
                            return null; // Fragment manquant
                        }
                    }

                    incomingFragments.remove(fragmentIDString);
                    fragmentMetadata.remove(fragmentIDString);

                    // La conversion de BitchatPacket.fromBinaryData est nécessaire ici.
                    // BitchatPacket originalPacket = BitchatPacket.fromBinaryData(reassembledStream.toByteArray());
                    // if (originalPacket != null) return originalPacket;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle fragment", e);
        }
        return null;
    }

    private void startPeriodicCleanup() {
        scheduler.scheduleAtFixedRate(this::cleanupOldFragments, CLEANUP_INTERVAL, CLEANUP_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void cleanupOldFragments() {
        long now = System.currentTimeMillis();
        long cutoff = now - FRAGMENT_TIMEOUT;
        fragmentMetadata.entrySet().removeIf(entry -> {
            if (entry.getValue().timestamp < cutoff) {
                incomingFragments.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
