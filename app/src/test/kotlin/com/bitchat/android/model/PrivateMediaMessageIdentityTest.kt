package com.bitchat.android.model

import org.junit.Assert.*
import org.junit.Test

class PrivateMediaMessageIdentityTest {
    private val sender = "0011223344556677"
    private val recipient = "8899aabbccddeeff"

    @Test
    fun `matches the iOS version one golden vector`() {
        assertEquals("media-910bd42c65060ab76bb6406f220c4516",
            PrivateMediaMessageIdentity.stableID(sender, recipient,
                "img_20260725_105708_1CC2760D-76AA-40C3-8013-C7FAA6C2EF99.jpg"))
    }

    @Test
    fun `retries keep identity while direction and name changes do not collide`() {
        val name = "voice_0011223344556677.m4a"
        val id = PrivateMediaMessageIdentity.stableID(sender, recipient, name)
        assertNotNull(id)
        assertTrue(PrivateMediaMessageIdentity.isStableID(id!!))
        assertNotEquals(id, PrivateMediaMessageIdentity.stableID(recipient, sender, name))
        assertNotEquals(id, PrivateMediaMessageIdentity.stableID(sender, recipient, "voice_0011223344556678.m4a"))
    }

    @Test
    fun `ordinary names and paths do not acquire receipt identities`() {
        listOf("voice_20260908.m4a", "img_20260908.jpg", "../voice_0011223344556677.m4a",
            "voice_0011223344556677.mp3", "photo.png").forEach {
            assertNull(PrivateMediaMessageIdentity.stableID(sender, recipient, it))
        }
    }
}
