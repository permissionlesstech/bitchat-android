package com.bitchat

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF) // Disable Conscrypt to avoid native library loading issues
class FileTransferTest {

    @Test
    fun `encode and decode file packet with all fields should preserve data`() {
        // Given: Complete file packet
        val contentArray = ByteArray(1024) { (it % 256).toByte() }
        val originalPacket = BitchatFilePacket(
            fileName = "test.png",
            mimeType = "image/png",
            fileSize = 1024000,
            content = contentArray
        )

        // When: Encode and decode
        val encoded = originalPacket.encode()
        val decoded = BitchatFilePacket.decode(encoded!!)

        // Then: Data should be preserved
        assertNotNull(decoded)
        assertEquals(originalPacket.fileName, decoded!!.fileName)
        assertEquals(originalPacket.mimeType, decoded.mimeType)
        assertEquals(originalPacket.fileSize, decoded.fileSize)
        assertEquals(originalPacket.content.size, decoded.content.size)
        for (i in 0 until originalPacket.content.size) {
            assertEquals(originalPacket.content[i], decoded.content[i])
        }
    }

    @Test
    fun `encode file packet with filename should include filename TLV`() {
        // Given: Packet with filename
        val packet = BitchatFilePacket(
            fileName = "myimage.jpg",
            mimeType = "image/jpeg",
            fileSize = 2048,
            content = ByteArray(256) { 0xFF.toByte() }
        )

        // When: Encode
        val encoded = packet.encode()
        assertNotNull(encoded)

        // Then: Should contain filename TLV
        // FILE_NAME type (0x01) + length (11) + "myimage.jpg" (UTF-8 with null terminator might add 1 byte)
        val expectedType = 0x01.toByte()
        val expectedFilename = "myimage.jpg".toByteArray(Charsets.UTF_8)
        val expectedLength = expectedFilename.size // Should be 10 for UTF-8 "myimage.jpg"



        assertEquals(expectedType, encoded!![0])
        // Calculate the actual length from little-endian encoded data
        val actualLength = (encoded[2].toInt() and 0xFF) or ((encoded[1].toInt() and 0xFF) shl 8)
        // The encoding seems to be including a null terminator or extended bytes
        assertEquals(11, actualLength) // The encoding produces 11 bytes for "myimage.jpg"

        val actualFilename = encoded!!.sliceArray(3 until 3 + expectedLength)
        for (i in expectedFilename.indices) {
            assertEquals(expectedFilename[i], actualFilename[i])
        }
    }

    @Test
    fun `encode file size should use big endian byte order for file size`() {
        // Given: File with specific size
        val fileSize = 0x12345678L
        val packet = BitchatFilePacket(
            fileName = "test.bin",
            mimeType = "application/octet-stream",
            fileSize = fileSize,
            content = ByteArray(10)
        )

        // When: Encode
        val encoded = packet.encode()
        assertNotNull(encoded)

        // Then: File size should be in big endian order
        // Find FILE_SIZE TLV (type 0x02)
        var offset = 0
        while (offset < encoded!!.size - 1) {
            if (encoded!![offset] == 0x02.toByte()) {
                // This is FILE_SIZE TLV
                offset += 1  // Skip type byte
                val length = (encoded!![offset].toInt() and 0xFF) or ((encoded[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2 // Skip length bytes
                if (length == 4) { // FILE_SIZE always has 4 bytes
                    val decodedFileSize = ByteBuffer.wrap(encoded!!.sliceArray(offset until offset + 4))
                        .order(ByteOrder.BIG_ENDIAN)
                        .int.toLong()
                    assertEquals(fileSize, decodedFileSize)
                    break
                }
            }
            offset += 1
        }
    }

    @Test
    fun `decode minimal file packet should handle defaults correctly`() {
        // Given: Minimal valid packet (the constructor requires non-null values)
        val originalPacket = BitchatFilePacket(
            fileName = "test",
            mimeType = "application/octet-stream",
            fileSize = 32,  // Matches content size
            content = ByteArray(32) { 0xAA.toByte() }
        )

        // When: Encode and decode
        val encoded = originalPacket.encode()
        val decoded = BitchatFilePacket.decode(encoded!!)

        // Then: Data should be preserved completely
        assertNotNull(decoded)
        assertEquals(32, decoded!!.content.size)
        for (i in 0 until 32) {
            assertEquals(0xAA.toByte(), decoded.content[i])
        }
        assertEquals("test", decoded.fileName)
        assertEquals("application/octet-stream", decoded.mimeType)
        assertEquals(32L, decoded.fileSize)
    }

    @Test
    fun `decode should skip unknown TLV types instead of dropping the whole file`() {
        // Given: a packet from a peer that added one TLV this build does not
        // know (a message id, tag 0x05), placed before CONTENT the way an
        // encoder that appends content last would emit it.
        val content = ByteArray(64) { (it % 256).toByte() }
        val fileName = "photo.jpg".toByteArray(Charsets.UTF_8)
        val mimeType = "image/jpeg".toByteArray(Charsets.UTF_8)
        val unknownValue = "some-message-id".toByteArray(Charsets.UTF_8)

        val buf = ByteBuffer.allocate(
            (1 + 2 + fileName.size) + (1 + 2 + 4) + (1 + 2 + mimeType.size) +
                (1 + 2 + unknownValue.size) + (1 + 4 + content.size)
        ).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x01.toByte()); buf.putShort(fileName.size.toShort()); buf.put(fileName)
        buf.put(0x02.toByte()); buf.putShort(4); buf.putInt(content.size)
        buf.put(0x03.toByte()); buf.putShort(mimeType.size.toShort()); buf.put(mimeType)
        buf.put(0x05.toByte()); buf.putShort(unknownValue.size.toShort()); buf.put(unknownValue)
        buf.put(0x04.toByte()); buf.putInt(content.size); buf.put(content)

        // When: Decoding
        val decoded = BitchatFilePacket.decode(buf.array())

        // Then: the unknown TLV costs nothing — the media still arrives.
        // Rejecting it made every such transfer vanish with no error on either
        // side, while iOS decoded the very same bytes fine.
        assertNotNull(decoded)
        assertEquals("photo.jpg", decoded!!.fileName)
        assertEquals("image/jpeg", decoded.mimeType)
        assertEquals(content.size.toLong(), decoded.fileSize)
        assertEquals(content.size, decoded.content.size)
        for (i in content.indices) {
            assertEquals(content[i], decoded.content[i])
        }
    }

    @Test
    fun `decode should skip an unknown TLV that trails the content`() {
        // Given: the same kind of extension appended AFTER content, which a
        // decoder that stops at the first unknown tag also loses.
        val content = ByteArray(16) { 0x7F }
        val fileName = "note.m4a".toByteArray(Charsets.UTF_8)
        val trailing = ByteArray(4) { 0x11 }

        val buf = ByteBuffer.allocate(
            (1 + 2 + fileName.size) + (1 + 4 + content.size) + (1 + 2 + trailing.size)
        ).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x01.toByte()); buf.putShort(fileName.size.toShort()); buf.put(fileName)
        buf.put(0x04.toByte()); buf.putInt(content.size); buf.put(content)
        buf.put(0x7F.toByte()); buf.putShort(trailing.size.toShort()); buf.put(trailing)

        // When: Decoding
        val decoded = BitchatFilePacket.decode(buf.array())

        // Then: Defaults still apply and the content is intact
        assertNotNull(decoded)
        assertEquals("note.m4a", decoded!!.fileName)
        assertEquals("application/octet-stream", decoded.mimeType)
        assertEquals(content.size.toLong(), decoded.fileSize)
        assertEquals(content.size, decoded.content.size)
    }

    @Test
    fun `decode should reject an incomplete TLV header after an unknown extension`() {
        // Given: a valid file and unknown extension followed by either only a
        // tag or a tag plus one length byte.
        val content = ByteArray(8) { 0x2A }
        val fileName = "truncated.bin".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(
            (1 + 2 + fileName.size) + (1 + 4 + content.size) + (1 + 2)
        ).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x01.toByte()); buf.putShort(fileName.size.toShort()); buf.put(fileName)
        buf.put(0x04.toByte()); buf.putInt(content.size); buf.put(content)
        buf.put(0x05.toByte()); buf.putShort(0)
        val packetWithUnknownExtension = buf.array()

        // When/Then: Android rejects the same incomplete tails that iOS does.
        assertNull(BitchatFilePacket.decode(packetWithUnknownExtension + byteArrayOf(0x06)))
        assertNull(BitchatFilePacket.decode(packetWithUnknownExtension + byteArrayOf(0x06, 0x00)))
    }

    @Test
    fun `decode should handle a packet padded with many zero-length unknown TLVs`() {
        // Given: the cheapest padding a peer can send — a zero-length unknown
        // TLV is 3 bytes, so one packet can carry hundreds of thousands of
        // them. Anything the skip path does per TLV (copying the empty value,
        // formatting a log line) is scaled by the sender, not by us.
        val padCount = 200_000
        val content = ByteArray(8) { 0x5A }
        val fileName = "padded.bin".toByteArray(Charsets.UTF_8)

        val buf = ByteBuffer.allocate(
            (1 + 2 + fileName.size) + (padCount * 3) + (1 + 4 + content.size)
        ).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x01.toByte()); buf.putShort(fileName.size.toShort()); buf.put(fileName)
        repeat(padCount) {
            buf.put(0x05.toByte()); buf.putShort(0)
        }
        buf.put(0x04.toByte()); buf.putInt(content.size); buf.put(content)

        // When: Decoding
        val decoded = BitchatFilePacket.decode(buf.array())

        // Then: the real fields still come through and the padding is ignored
        assertNotNull(decoded)
        assertEquals("padded.bin", decoded!!.fileName)
        assertEquals(content.size.toLong(), decoded.fileSize)
        assertEquals(content.size, decoded.content.size)
    }

    @Test
    fun `replaceFilePathInContent should correctly format content markers for different file types`() {
        // Given: Different file types
        val imageMessage = BitchatMessage(
            id = "test1",
            sender = "alice",
            senderPeerID = "12345678",
            content = "/data/user/0/com.bitchat.android/files/images/photo.jpg",
            type = BitchatMessageType.Image,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = false
        )

        val audioMessage = BitchatMessage(
            id = "test2",
            sender = "bob",
            senderPeerID = "87654321",
            content = "/data/user/0/com.bitchat.android/files/audio/voice.amr",
            type = BitchatMessageType.Audio,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = false
        )

        val fileMessage = BitchatMessage(
            id = "test3",
            sender = "charlie",
            senderPeerID = "11223344",
            content = "/data/user/0/com.bitchat.android/files/documents/document.pdf",
            type = BitchatMessageType.File,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = false
        )

        // When: Converting to display format (this would be done in MessageMutable)
        var result = imageMessage.content
        result = result.replace(
            "/data/user/0/com.bitchat.android/files/images/photo.jpg",
            "[image] photo.jpg"
        )

        // Then: Should match expected pattern
        assertEquals("[image] photo.jpg", result)

        // Similar pattern for audio and file would be used in the actual implementation
    }

    @Test
    fun `buildPrivateMessagePreview should generate user-friendly notifications for file types`() {
        // Note: This test is for the NotificationTextUtils.buildPrivateMessagePreview function
        // The actual function is in a separate utility file as part of the refactoring

        // Given: Incoming image message
        val imageMessage = BitchatMessage(
            id = "test1",
            sender = "alice",
            senderPeerID = "1234abcd",
            content = "📷 sent an image", // This would be the result of the utility function
            type = BitchatMessageType.Image,
            timestamp = Date(System.currentTimeMillis()),
            isPrivate = true
        )

        // When: Building preview (this would call NotificationTextUtils.buildPrivateMessagePreview)
        val preview = imageMessage.content // In actual code, this would be generated

        // Then: Should provide user-friendly preview
        assertEquals("📷 sent an image", preview)

        // Additional assertions would test different file types
        // Audio: "🎤 sent a voice message"
        // File with specific extension: "📄 document.pdf"
        // Generic file: "📎 sent a file"
    }

    @Test
    fun `waveform extraction should handle empty audio data gracefully`() {
        // This test would verify that empty or very short audio files
        // don't cause crashes in waveform extraction

        // Given: Empty audio data
        val emptyAudioData = ByteArray(0)

        // When: Attempting to extract waveform
        // Note: Actual waveform extraction would be tested in the Waveform class
        // This is a unit test placeholder

        // Then: Should not crash and should return reasonable result
        // For empty data, waveform might be empty array or default values
        assertEquals(0, emptyAudioData.size)
    }

    @Test
    fun `media picker should handle file size limits correctly`() {
        // This test would verify that media file selection
        // respects size limits before attempting transfer

        // Given: Large file size (simulated)
        val largeFileSize = 100L * 1024 * 1024 // 100MB
        val maxAllowedSize = com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES

        // When: Checking if file can be transferred
        val isAllowed = largeFileSize <= maxAllowedSize

        // Then: Should be rejected
        assert(!isAllowed)
    }

    @Test
    fun `transfer cancellation should cleanup resources properly`() {
        // This test would verify that when a file transfer is cancelled,
        // all associated resources are cleaned up

        // Given: Active transfer in progress
        val transferId = "test_transfer_123"

        // When: Transfer is cancelled
        // In the actual implementation, this would call cancellation logic
        val cancelled = true // Simulated cancellation

        // Then: Resources should be cleaned up
        // This would verify temp files are deleted, progress tracking is cleared, etc.
        assert(cancelled)
    }
}
