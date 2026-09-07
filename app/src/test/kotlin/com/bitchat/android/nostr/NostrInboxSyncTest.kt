package com.bitchat.android.nostr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NostrInboxSyncTest {
    @Test fun `catch-up includes wrapper randomization beyond thirty days`() {
        val now = 4_000_000_000L
        assertEquals(((now - 30L * 86_400_000 - NostrInboxSync.WRAPPER_OVERLAP_MS) / 1000).toInt(), NostrInboxSync.since(now, null))
        assertEquals(((now - 60_000 - NostrInboxSync.WRAPPER_OVERLAP_MS) / 1000).toInt(), NostrInboxSync.since(now, now - 60_000))
    }

    @Test fun `paginated history keeps every message including equal-second ties`() = runBlocking {
        val source = (0..1200).map { NostrEvent(id = "$it", pubkey = "synthetic", createdAt = it / 600, kind = 1059, tags = emptyList(), content = "fixture") }
        val processed = mutableSetOf<String>()
        val sync = NostrInboxSync(
            fetch = { since, until, limit -> source.filter { it.createdAt in since..until }.sortedByDescending { it.createdAt }.take(limit) },
            process = { processed.add(it.id); true }
        )
        assertTrue(sync.scan(0, 10))
        assertEquals(source.map { it.id }.toSet(), processed)
    }

    @Test fun `failed admission or missing EOSE leaves catch-up incomplete`() = runBlocking {
        val event = NostrEvent(pubkey = "synthetic", createdAt = 1, kind = 1059, tags = emptyList(), content = "fixture")
        assertFalse(NostrInboxSync({ _, _, _ -> null }, { true }).scan(0, 10))
        assertFalse(NostrInboxSync({ _, _, _ -> listOf(event) }, { false }).scan(0, 10))
    }
}
