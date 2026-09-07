package com.bitchat.android.services

import org.junit.Assert.*
import org.junit.Test

class ChannelInvitationTest {
    @Test fun `explicit synthetic cell round trips without location access`() {
        assertEquals("s000", ChannelInvitation.decode(ChannelInvitation.link("s000")!!))
    }
    @Test fun `rejects ambiguous or malformed invitations`() {
        listOf("https://geohash/s000", "bitchat://geohash/s000?live=1",
            "bitchat://geohash/s000#extra", "bitchat://user@geohash/s000",
            "bitchat://geohash:42/s000", "bitchat://geohash/", "bitchat://geohash/invalid",
            "bitchat://geohash/s000/extra").forEach { assertNull(it, ChannelInvitation.decode(it)) }
    }
}
