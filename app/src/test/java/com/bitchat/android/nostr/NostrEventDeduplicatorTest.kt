package com.bitchat.android.nostr

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

/**
 * Test class for NostrEventDeduplicator
 */
class NostrEventDeduplicatorTest {

    private lateinit var deduplicator: NostrEventDeduplicator

    @Before
    fun setUp() {
        deduplicator = NostrEventDeduplicator(maxCapacity = 5) // Small capacity for testing
        deduplicator.clear() // Ensure clean state
    }

    @Test
    fun testBasicDeduplication() {
        val eventId1 = "event1"
        val eventId2 = "event2"
        
        // First time should not be duplicate
        assertFalse("First event should not be duplicate", deduplicator.isDuplicate(eventId1))
        assertFalse("Second event should not be duplicate", deduplicator.isDuplicate(eventId2))
        
        // Second time should be duplicate
        assertTrue("Event1 should be duplicate on second check", deduplicator.isDuplicate(eventId1))
        assertTrue("Event2 should be duplicate on second check", deduplicator.isDuplicate(eventId2))
        
        assertEquals("Cache should contain 2 events", 2, deduplicator.size())
    }

    @Test
    fun testLRUEviction() {
        // Add events up to capacity
        for (i in 1..5) {
            assertFalse("Event$i should not be duplicate", deduplicator.isDuplicate("event$i"))
        }
        
        assertEquals("Cache should be at capacity", 5, deduplicator.size())
        
        // Access event1 to make it most recently used
        assertTrue("Event1 should be duplicate", deduplicator.isDuplicate("event1"))
        
        // Add one more event to trigger eviction
        assertFalse("Event6 should not be duplicate", deduplicator.isDuplicate("event6"))
        
        assertEquals("Cache should still be at capacity", 5, deduplicator.size())
        
        // event2 should have been evicted (least recently used)
        assertFalse("Event2 should not be duplicate (evicted)", deduplicator.isDuplicate("event2"))
        
        // event1 should still be present (most recently used)
        assertTrue("Event1 should still be duplicate", deduplicator.isDuplicate("event1"))
    }

    @Test
    fun testProcessEvent() {
        var processedCount = 0
        val processedEvents = mutableListOf<String>()
        
        val event1 = createTestEvent("event1", "Test content 1")
        val event2 = createTestEvent("event2", "Test content 2")
        val event1Duplicate = createTestEvent("event1", "Test content 1") // Same ID
        
        // Process first event
        val wasProcessed1 = deduplicator.processEvent(event1) { event ->
            processedCount++
            processedEvents.add(event.id)
        }
        
        assertTrue("First event should be processed", wasProcessed1)
        assertEquals("Should have processed 1 event", 1, processedCount)
        
        // Process second event
        val wasProcessed2 = deduplicator.processEvent(event2) { event ->
            processedCount++
            processedEvents.add(event.id)
        }
        
        assertTrue("Second event should be processed", wasProcessed2)
        assertEquals("Should have processed 2 events", 2, processedCount)
        
        // Process duplicate event
        val wasProcessed3 = deduplicator.processEvent(event1Duplicate) { event ->
            processedCount++
            processedEvents.add(event.id)
        }
        
        assertFalse("Duplicate event should not be processed", wasProcessed3)
        assertEquals("Should still have processed only 2 events", 2, processedCount)
        
        assertEquals("Should have processed event1 and event2", listOf("event1", "event2"), processedEvents)
    }

    @Test
    fun testStatistics() {
        // Initially empty
        val initialStats = deduplicator.getStats()
        assertEquals("Initial size should be 0", 0, initialStats.currentSize)
        assertEquals("Initial checks should be 0", 0, initialStats.totalChecks)
        assertEquals("Initial duplicates should be 0", 0, initialStats.duplicateCount)
        assertEquals("Initial hit rate should be 0", 0.0, initialStats.hitRate, 0.01)
        
        // Add some events
        deduplicator.isDuplicate("event1")
        deduplicator.isDuplicate("event2")
        deduplicator.isDuplicate("event1") // duplicate
        deduplicator.isDuplicate("event3")
        deduplicator.isDuplicate("event2") // duplicate
        
        val stats = deduplicator.getStats()
        assertEquals("Size should be 3", 3, stats.currentSize)
        assertEquals("Total checks should be 5", 5, stats.totalChecks)
        assertEquals("Duplicate count should be 2", 2, stats.duplicateCount)
        assertEquals("Hit rate should be 40%", 0.4, stats.hitRate, 0.01)
    }

    @Test
    fun testClear() {
        // Add some events
        deduplicator.isDuplicate("event1")
        deduplicator.isDuplicate("event2")
        
        assertTrue("Should contain event1", deduplicator.contains("event1"))
        assertTrue("Should contain event2", deduplicator.contains("event2"))
        assertEquals("Size should be 2", 2, deduplicator.size())
        
        // Clear the cache
        deduplicator.clear()
        
        assertFalse("Should not contain event1 after clear", deduplicator.contains("event1"))
        assertFalse("Should not contain event2 after clear", deduplicator.contains("event2"))
        assertEquals("Size should be 0 after clear", 0, deduplicator.size())
        
        val stats = deduplicator.getStats()
        assertEquals("Stats should be reset", 0, stats.totalChecks)
        assertEquals("Stats should be reset", 0, stats.duplicateCount)
    }

    @Test
    fun testEvictionStatistics() {
        val smallDeduplicator = NostrEventDeduplicator(maxCapacity = 2)
        smallDeduplicator.clear()
        
        // Add events to trigger eviction
        smallDeduplicator.isDuplicate("event1")
        smallDeduplicator.isDuplicate("event2")
        smallDeduplicator.isDuplicate("event3") // Should evict event1
        
        val stats = smallDeduplicator.getStats()
        assertEquals("Should have 1 eviction", 1, stats.evictionCount)
        assertEquals("Size should be at capacity", 2, stats.currentSize)
    }

    @Test
    fun testSingletonInstance() {
        val instance1 = NostrEventDeduplicator.getInstance()
        val instance2 = NostrEventDeduplicator.getInstance()
        
        assertSame("Should return same singleton instance", instance1, instance2)
        
        // Test that singleton maintains state
        instance1.isDuplicate("test-event")
        assertTrue("Singleton state should be maintained", instance2.isDuplicate("test-event"))
    }

    @Test
    fun testLargeNumberOfEvents() {
        val largeDeduplicator = NostrEventDeduplicator(maxCapacity = 1000)
        largeDeduplicator.clear()
        
        // Add many events
        for (i in 1..1500) {
            val isNewEvent = !largeDeduplicator.isDuplicate("event$i")
            assertTrue("Event$i should be new", isNewEvent)
        }
        
        val stats = largeDeduplicator.getStats()
        assertEquals("Size should be at capacity", 1000, stats.currentSize)
        assertEquals("Should have 500 evictions", 500, stats.evictionCount)
        assertEquals("Should have 1500 total checks", 1500, stats.totalChecks)
        
        // Early events should be evicted
        assertFalse("Early event should be evicted", largeDeduplicator.contains("event1"))
        assertTrue("Recent event should still be present", largeDeduplicator.contains("event1500"))
    }

    private fun createTestEvent(id: String, content: String): NostrEvent {
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        return NostrEvent(
            id = id,
            pubkey = publicKey,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content,
            sig = "dummy_signature"
        )
    }
}
