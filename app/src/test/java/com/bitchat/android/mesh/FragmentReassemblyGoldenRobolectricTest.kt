package com.bitchat.android.mesh

import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.BitchatPacket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FragmentReassemblyGoldenRobolectricTest {
    private val manager = FragmentManager()

    @After
    fun tearDown() {
        manager.shutdown()
    }

    @Test
    fun `literal count boundaries complete through the wire decoder and reassembler`() {
        listOf("count-256", "count-257", "count-10000").forEach { assertCompletes(vector(it)) }
    }

    @Test
    fun `literal byte boundaries complete and return the entire retained budget`() {
        listOf("old-byte-cap", "old-byte-cap-plus-one", "frame-cap").forEach {
            assertCompletes(vector(it))
        }
    }

    @Test
    fun `one fragment above either receive boundary is rejected without discarding held bytes`() {
        val tooMany = vector("count-10001")
        assertTrue(!tooMany.completes)
        assertNull(manager.handleFragment(fragment(tooMany, 0)))
        assertEquals(0L, chargedBytes())
        assertEquals(0, retainedEntries())

        val tooLarge = vector("frame-cap-plus-one")
        assertTrue(!tooLarge.completes)
        repeat(tooLarge.total - 1) { assertNull(manager.handleFragment(fragment(tooLarge, it))) }
        val retained = chargedBytes()
        assertEquals((tooLarge.total - 1) * (tooLarge.chunkBytes + 64L), retained)
        assertTrue(
            "A frame above the receive ceiling must not complete",
            manager.handleFragment(fragment(tooLarge, tooLarge.total - 1)) == null
        )
        assertEquals(retained, chargedBytes())
        assertEquals(tooLarge.total - 1, retainedEntries())
        manager.clearAllFragments()
        assertEquals(0L, chargedBytes())
        assertCompletes(vector("frame-cap"))
    }

    @Test
    fun `one-byte entries exhaust the real global budget and duplicates do not charge twice`() {
        // Literal contract: floor(4 MiB / (one data byte + 64 entry bytes)).
        val accepted = 64_527
        repeat(accepted) { index ->
            assertNull(manager.handleFragment(tinyFragment(index)))
        }
        assertEquals(4_194_255L, chargedBytes())
        assertEquals(accepted, retainedEntries())
        assertNull(manager.handleFragment(tinyFragment(0)))
        assertEquals(4_194_255L, chargedBytes())
        assertNull(manager.handleFragment(tinyFragment(accepted)))
        assertEquals(4_194_255L, chargedBytes())
        assertEquals(accepted, retainedEntries())
        manager.clearAllFragments()
        assertEquals(0L, chargedBytes())
        assertEquals(0, retainedEntries())
        assertCompletes(vector("frame-cap"))
    }

    @Test
    fun `a sixty-fifth incomplete set is rejected and clearing releases all set slots`() {
        repeat(64) { index ->
            val id = fragmentID.copyOf().also { it[0] = index.toByte() }
            assertNull(manager.handleFragment(decodeOuter(fragmentHeader(0, 2, id) + byteArrayOf(1))))
        }
        assertEquals(64 * 65L, chargedBytes())
        assertEquals(64, retainedEntries())
        val extraID = fragmentID.copyOf().also { it[0] = 64 }
        assertNull(manager.handleFragment(decodeOuter(fragmentHeader(0, 2, extraID) + byteArrayOf(1))))
        assertEquals(64 * 65L, chargedBytes())
        assertEquals(64, retainedEntries())
        manager.clearAllFragments()
        assertEquals(0L, chargedBytes())
        assertEquals(0, retainedEntries())
        assertCompletes(vector("count-257"))
    }

    private fun assertCompletes(vector: Vector) {
        assertTrue(vector.completes)
        // Receive in reverse order, leaving index zero (the envelope header) until last.
        for (index in vector.total - 1 downTo 1) {
            assertNull(vector.name, manager.handleFragment(fragment(vector, index)))
        }
        assertEquals(
            vector.name,
            vector.frame.size - vector.chunkBytes + (vector.total - 1) * 64L,
            chargedBytes()
        )
        val result = manager.handleFragment(fragment(vector, 0))
        assertNotNull(vector.name, result)
        result!!
        assertEquals(2u.toUByte(), result.version)
        assertEquals(0x22u.toUByte(), result.type)
        assertEquals(0u.toUByte(), result.ttl)
        assertEquals(0x0102030405060708uL, result.timestamp)
        assertArrayEquals(sender, result.senderID)
        assertArrayEquals(recipient, result.recipientID)
        assertEquals(vector.payloadBytes, result.payload.size)
        assertEquals(vector.payloadHash, sha256(result.payload))
        assertArrayEquals(ByteArray(vector.payloadBytes) { it.toByte() }, result.payload)
        assertEquals(0L, chargedBytes())
        assertEquals(0, retainedEntries())
    }

    private fun fragment(vector: Vector, index: Int): BitchatPacket {
        val offset = index * vector.chunkBytes
        val data = vector.frame.copyOfRange(offset, minOf(offset + vector.chunkBytes, vector.frame.size))
        return decodeOuter(fragmentHeader(index, vector.total, fragmentID) + data)
    }

    private fun tinyFragment(index: Int): BitchatPacket {
        // Each set stays incomplete. Different IDs allow the global, not per-set, limit to bind.
        val id = fragmentID.copyOf().also { it[0] = (index / 5_000).toByte() }
        return decodeOuter(fragmentHeader(index % 5_000, 10_000, id) + byteArrayOf(1))
    }

    private fun fragmentHeader(index: Int, total: Int, id: ByteArray): ByteArray =
        id + uint16(index) + uint16(total) + byteArrayOf(0x22)

    private fun decodeOuter(payload: ByteArray): BitchatPacket {
        // Deliberately do not call FragmentPayload.encode, createFragments, or BinaryProtocol.encode.
        val wire = "012007010203040506070801".hexBytes() + uint16(payload.size) + sender + recipient + payload
        assertTrue("Golden fragments must fit the transport MTU", wire.size <= 512)
        return requireNotNull(BinaryProtocol.decode(wire))
    }

    private fun chargedBytes(): Long =
        Regex("Global Buffered Bytes: (\\d+)").find(manager.getDebugInfo())!!.groupValues[1].toLong()

    private fun retainedEntries(): Int =
        Regex(": (\\d+)/").findAll(manager.getDebugInfo()).sumOf { it.groupValues[1].toInt() }

    private fun vector(name: String): Vector {
        val stream = requireNotNull(javaClass.getResourceAsStream("/contracts/fragment-reassembly-v2.txt"))
        val columns = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().filter { it.startsWith("$name|") }.single().split('|')
        }
        return Vector(
            columns[0], columns[1].toInt(), columns[2].toInt(), columns[3].toInt(),
            columns[4].hexBytes(), columns[5], columns[6], columns[7], columns[8], columns[9].toBooleanStrict()
        ).also {
            assertEquals(name, 32, it.header.size)
            assertEquals(name, (it.frame.size + it.chunkBytes - 1) / it.chunkBytes, it.total)
            assertEquals(name, it.frameHash, sha256(it.frame))
            assertEquals(name, it.payloadHash, sha256(it.frame.copyOfRange(32, it.frame.size)))
            assertEquals(name, it.firstHeader, fragmentHeader(0, it.total, fragmentID).toHex())
            assertEquals(name, it.lastHeader, fragmentHeader(it.total - 1, it.total, fragmentID).toHex())
        }
    }

    private data class Vector(
        val name: String,
        val payloadBytes: Int,
        val chunkBytes: Int,
        val total: Int,
        val header: ByteArray,
        val frameHash: String,
        val payloadHash: String,
        val firstHeader: String,
        val lastHeader: String,
        val completes: Boolean
    ) {
        val frame: ByteArray = header + ByteArray(payloadBytes) { it.toByte() }
    }

    companion object {
        private val sender = "1122334455667788".hexBytes()
        private val recipient = "8877665544332211".hexBytes()
        private val fragmentID = "a0a1a2a3a4a5a6a7".hexBytes()

        private fun uint16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
        private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    }
}
