package com.bitchat.android.nostr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Global, thread-safe service for event deduplication
 * - Can be used by multiple components without conflicts
 * - Optimized for high-throughput environments
 */
public class NostrEventDeduplicator {

    private static final int MAX_CAPACITY = 5000;
    private static volatile NostrEventDeduplicator instance;

    private final Set<String> processedEventIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final List<String> insertionOrder = Collections.synchronizedList(new ArrayList<>());

    private NostrEventDeduplicator() {}

    public static NostrEventDeduplicator getInstance() {
        if (instance == null) {
            synchronized (NostrEventDeduplicator.class) {
                if (instance == null) {
                    instance = new NostrEventDeduplicator();
                }
            }
        }
        return instance;
    }

    /**
     * Atomically checks if an event has been processed. If not, marks it as processed
     * and executes the handler.
     *
     * @param event The Nostr event to process.
     * @param handler A lambda that will be executed only if the event is new.
     * @return `true` if the event was new and the handler was called, `false` otherwise.
     */
    public boolean processEvent(NostrEvent event, Consumer<NostrEvent> handler) {
        // Fast path: check for existence without locking
        if (processedEventIds.contains(event.getId())) {
            return false;
        }

        boolean isNew = false;

        // Synchronize to ensure atomicity of check-and-add
        synchronized (this) {
            if (!processedEventIds.contains(event.getId())) {
                processedEventIds.add(event.getId());
                insertionOrder.add(event.getId());
                isNew = true;

                // Evict old entries if capacity is exceeded
                if (processedEventIds.size() > MAX_CAPACITY) {
                    if (!insertionOrder.isEmpty()) {
                        String oldestId = insertionOrder.remove(0);
                        processedEventIds.remove(oldestId);
                    }
                }
            }
        }

        if (isNew) {
            handler.accept(event);
        }

        return isNew;
    }

    /**
     * Returns statistics about the deduplicator's state.
     */
    public DeduplicationStats getStats() {
        return new DeduplicationStats(processedEventIds.size());
    }

    /**
     * Clears all stored event IDs.
     */
    public void clear() {
        synchronized (this) {
            processedEventIds.clear();
            insertionOrder.clear();
        }
    }
}
