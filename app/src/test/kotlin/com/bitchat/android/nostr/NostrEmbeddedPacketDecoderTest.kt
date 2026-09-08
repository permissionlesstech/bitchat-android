package com.bitchat.android.nostr

import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Build

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class NostrEmbeddedPacketDecoderTest {

    @Test
    fun `decodes a small base64url payload`() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .replace("=", "")
        assertArrayEquals(raw, NostrEmbeddedPacketDecoder.decodeBounded(encoded, maxBytes = 64))
    }

    @Test
    fun `rejects an encoding that cannot fit under the byte ceiling`() {
        val maxBytes = 16
        // 4 chars encode at most 3 bytes; 24 chars would decode to ~18 bytes.
        val oversizedEncoding = "A".repeat(24)
        assertNull(NostrEmbeddedPacketDecoder.decodeBounded(oversizedEncoding, maxBytes = maxBytes))
    }

    @Test
    fun `rejects a decode that expands past the byte ceiling`() {
        val maxBytes = 8
        // 12 chars of valid base64url decode to 9 bytes.
        val nineBytes = ByteArray(9) { 0x41 }
        val encoded = Base64.encodeToString(nineBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .replace("=", "")
        assertNull(NostrEmbeddedPacketDecoder.decodeBounded(encoded, maxBytes = maxBytes))
    }

    @Test
    fun `accepts a payload that fills the ceiling exactly`() {
        val maxBytes = 9
        val nineBytes = ByteArray(9) { 0x42 }
        val encoded = Base64.encodeToString(nineBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .replace("=", "")
        assertNotNull(NostrEmbeddedPacketDecoder.decodeBounded(encoded, maxBytes = maxBytes))
    }
}
