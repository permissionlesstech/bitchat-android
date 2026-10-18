package com.bitchat.android.services.bridge

import com.bitchat.android.model.NostrCarrierPacket
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrKind
import org.junit.Assert.*
import org.junit.Test

class GatewayEventPolicyTest {
    private val now = 1_800_000_000L
    private fun carrier(age: Long = 0, kind: Int = NostrKind.EPHEMERAL_EVENT, cell: String = "s000") =
        NostrCarrierPacket.fromEvent(NostrCarrierPacket.Direction.TO_GATEWAY, "s000",
            NostrEvent(pubkey = "00".repeat(32), createdAt = (now - age).toInt(), kind = kind,
                tags = listOf(listOf("g", cell)), content = "synthetic gateway event"))!!

    @Test fun `accepts exact timestamp boundaries for signature verification`() {
        listOf(-900L, 0L, 900L).forEach { assertNotNull(GatewayEventPolicy.inspect(carrier(it), now)) }
    }
    @Test fun `rejects stale future wrong kind and mismatched channel before signature work`() {
        assertNull(GatewayEventPolicy.inspect(carrier(901), now))
        assertNull(GatewayEventPolicy.inspect(carrier(-901), now))
        assertNull(GatewayEventPolicy.inspect(carrier(kind = NostrKind.TEXT_NOTE), now))
        assertNull(GatewayEventPolicy.inspect(carrier(cell = "s001"), now))
    }
}
