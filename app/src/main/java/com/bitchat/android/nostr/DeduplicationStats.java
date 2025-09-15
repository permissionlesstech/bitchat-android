package com.bitchat.android.nostr;

/**
 * Data class for deduplication statistics.
 */
public class DeduplicationStats {
    private final int processedCount;

    public DeduplicationStats(int processedCount) {
        this.processedCount = processedCount;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    @Override
    public String toString() {
        return "DeduplicationStats{" +
                "processedCount=" + processedCount +
                '}';
    }
}
