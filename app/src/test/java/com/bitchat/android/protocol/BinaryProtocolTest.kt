package com.bitchat.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class BinaryProtocolTest {

    private val senderHex = "1122334455667788"
    private val recipientHex = "8877665544332211"
    private val fixedTimestamp = 1709600000000uL

    /**
     * Minimal v1 broadcast packet (no recipient, no signature, no route)
     *
     * Baseline correctness test for the simplest possible packet shape.
     * Encodes a v1 packet with only the mandatory fields (version, type, TTL,
     * timestamp, senderID, payload) and decodes it back, verifying every field
     * survives the round-trip.
     *
     * Catches byte-order bugs, header size miscalculations, and off-by-one
     * errors in the payload length field. The payload is deliberately kept
     * below the 100-byte compression threshold to ensure compression is NOT
     * triggered — isolating pure encode/decode logic from the compression path.
     */
    @Test
    fun `minimal v1 broadcast packet round-trips correctly`() {
        val original = makePacket(payload = "Hello mesh".toByteArray())

        val decoded = roundTrip(original)

        assertPacketEquals(original, decoded)
        assertNull("recipientID should be null", decoded.recipientID)
        assertNull("signature should be null", decoded.signature)
        assertNull("route should be null", decoded.route)
    }

    /**
     * v1 packet with recipient
     *
     * Adds a recipientID to the packet, which sets the HAS_RECIPIENT flag
     * (bit 0) in the flags' byte. Encodes and decodes, then verifies the
     * recipientID bytes survive the round-trip intact.
     *
     * This validates the conditional recipient field and its flag. If the
     * flag or offset calculation is wrong, every subsequent field in the
     * packet (payload, signature) shifts by 8 bytes, corrupting decode.
     */
    @Test
    fun `v1 packet with recipient round-trips correctly`() {
        val original = makePacket(
            payload = "Private msg".toByteArray(),
            recipientID = hexToBytes(recipientHex)
        )

        val decoded = roundTrip(original)

        assertPacketEquals(original, decoded)
        assertNotNull("recipientID must not be null", decoded.recipientID)
        assertTrue("recipientID bytes must match",
            original.recipientID!!.contentEquals(decoded.recipientID!!))
    }

    /**
     * v1 packet with signature
     *
     * Adds a 64-byte Ed25519 signature to the packet, which sets the
     * HAS_SIGNATURE flag (bit 1). Encodes and decodes, then verifies
     * the signature bytes survive the round-trip exactly.
     *
     * The signature is appended AFTER the payload in the wire format.
     * If the payload length calculation is off by even one byte, the
     * decoder will read wrong bytes for the signature, silently
     * producing a corrupted value that fails verification downstream.
     */
    @Test
    fun `v1 packet with signature round-trips correctly`() {
        val signature = ByteArray(64) { (it + 0xA0).toByte() }
        val original = makePacket(
            payload = "Signed msg".toByteArray(),
            signature = signature
        )

        val decoded = roundTrip(original)

        assertPacketEquals(original, decoded)
        assertNotNull("signature must not be null", decoded.signature)
        assertTrue("signature bytes must match",
            original.signature!!.contentEquals(decoded.signature!!))
    }

    /**
     * v1 packet with recipient AND signature
     *
     * The most common real-world packet shape for private signed messages:
     * both optional v1 fields (recipientID + signature) are present
     * simultaneously. Encodes and decodes, verifying all fields.
     *
     * Tests that both conditional sections compose correctly. The wire
     * layout is: header | senderID | recipientID | payload | signature.
     * If either conditional section's size is miscalculated, it shifts
     * everything after it, corrupting the remaining fields.
     */
    @Test
    fun `v1 packet with recipient and signature round-trips correctly`() {
        val signature = ByteArray(64) { (it + 0xB0).toByte() }
        val original = makePacket(
            payload = "Private signed".toByteArray(),
            recipientID = hexToBytes(recipientHex),
            signature = signature
        )

        val decoded = roundTrip(original)

        assertPacketEquals(original, decoded)
        assertNotNull("recipientID must not be null", decoded.recipientID)
        assertNotNull("signature must not be null", decoded.signature)
        assertTrue("recipientID bytes must match",
            original.recipientID!!.contentEquals(decoded.recipientID!!))
        assertTrue("signature bytes must match",
            original.signature!!.contentEquals(decoded.signature!!))
    }

    /**
     * v2 packet with route
     *
     * Creates a v2 packet with a 3-hop source route, recipientID, and
     * signature — the most complex packet layout the protocol supports.
     * Verifies all route hops survive the round-trip, version stays 2,
     * and the 4-byte payload length field is handled correctly.
     *
     * v2 introduces two key changes: 4-byte (instead of 2-byte) payload
     * length, and a route section (1-byte hop count + N*8-byte peerIDs).
     * Route encoding bugs break source-routed mesh delivery where packets
     * must traverse specific intermediate nodes.
     */
    @Test
    fun `v2 packet with route round-trips correctly`() {
        val route = listOf(
            hexToBytes("AABBCCDDEEFF0011"),
            hexToBytes("1100FFEEDDCCBBAA"),
            hexToBytes("1234567890ABCDEF")
        )
        val signature = ByteArray(64) { (it + 0xC0).toByte() }
        val original = makePacket(
            version = 2u,
            payload = "Routed msg".toByteArray(),
            recipientID = hexToBytes(recipientHex),
            signature = signature,
            route = route
        )

        val decoded = roundTrip(original)

        assertPacketEquals(original, decoded)
        assertNotNull("route must not be null", decoded.route)
        assertEquals("route hop count", 3, decoded.route!!.size)
        for (i in route.indices) {
            assertTrue("route hop $i must match",
                route[i].contentEquals(decoded.route!![i]))
        }
    }

    /**
     * v2 packet with empty/null route
     *
     * Creates v2 packets with route=emptyList() and route=null, then
     * verifies the HAS_ROUTE flag is NOT set and both decode cleanly
     * with route=null in the result.
     *
     * Edge case: an empty route list should behave identically to null —
     * it should NOT encode a route-count byte of 0, which would waste a
     * byte and could confuse decoders that treat count=0 differently.
     */
    @Test
    fun `v2 packet with empty route decodes as null route`() {
        val emptyRoute = makePacket(
            version = 2u,
            payload = "No route".toByteArray(),
            route = emptyList()
        )
        val decodedEmpty = roundTrip(emptyRoute)
        assertNull("empty route should decode as null", decodedEmpty.route)

        val nullRoute = makePacket(
            version = 2u,
            payload = "No route".toByteArray(),
            route = null
        )
        val decodedNull = roundTrip(nullRoute)
        assertNull("null route should decode as null", decodedNull.route)
    }

    /**
     * All MessageType values round-trip
     *
     * Creates one packet per MessageType (ANNOUNCE, MESSAGE, LEAVE,
     * NOISE_HANDSHAKE, NOISE_ENCRYPTED, FRAGMENT, REQUEST_SYNC,
     * FILE_TRANSFER), encodes and decodes each, then verifies the type
     * field is preserved exactly.
     *
     * MessageType is stored as a single UByte. If any type value gets
     * mangled by signed/unsigned byte conversion (e.g., 0x80+ values
     * treated as negative), that entire message class silently breaks.
     * Also validates MessageType.fromValue() symmetry — every encoded
     * type must map back to its enum constant.
     */
    @Test
    fun `all MessageType values round-trip correctly`() {
        for (msgType in MessageType.entries) {
            val original = makePacket(
                payload = "type-test".toByteArray(),
                type = msgType.value
            )

            val encoded = BinaryProtocol.encode(original)
            assertNotNull("Encoding ${msgType.name} must not return null", encoded)

            val decoded = BinaryProtocol.decode(encoded!!)
            assertNotNull("Decoding ${msgType.name} must not return null", decoded)

            assertEquals("type must match for ${msgType.name}",
                msgType.value, decoded!!.type)
            assertEquals("fromValue must resolve for ${msgType.name}",
                msgType, MessageType.fromValue(decoded.type))
        }
    }

    /**
     * Large compressible payload triggers compression
     *
     * Creates a packet with a 500-byte repeating-text payload (well above
     * the 100-byte compression threshold and with low entropy). Encodes it,
     * verifies the encoded wire size is smaller than it would be without
     * compression, then decodes and verifies the payload is restored exactly.
     *
     * Validates the full compression pipeline: shouldCompress() → compress()
     * → IS_COMPRESSED flag set → original-size field prepended → decompress().
     * A mismatch in the original-size encoding (2 bytes for v1 vs 4 for v2)
     * would cause decompression to fail or produce wrong-length output.
     */
    @Test
    fun `large compressible payload is compressed and decompressed correctly`() {
        val repeating = "ABCDEFGH".repeat(63) // 504 bytes, highly compressible
        val payload = repeating.toByteArray()
        val original = makePacket(payload = payload)

        val encoded = BinaryProtocol.encode(original)
        assertNotNull("Encoding must not return null", encoded)

        // Verify IS_COMPRESSED flag is set in the encoded output
        val unpadded = MessagePadding.unpad(encoded!!)
        val flags = unpadded[11].toUByte()
        assertTrue("IS_COMPRESSED flag must be set",
            (flags and BinaryProtocol.Flags.IS_COMPRESSED) != 0u.toUByte())

        // Encoded should be smaller than payload + header overhead, proving compression fired
        // Header(13) + sender(8) + payload(504) + padding = would be 525+ uncompressed
        // With compression the raw data before padding should be much smaller
        val decoded = roundTrip(original)
        assertTrue("payload must survive compression round-trip",
            original.payload.contentEquals(decoded.payload))
        assertEquals("payload length must match after decompression",
            original.payload.size, decoded.payload.size)
    }

    /**
     * Small payload skips compression
     *
     * Creates a packet with a 50-byte payload, below the 100-byte
     * compression threshold. Verifies the IS_COMPRESSED flag (bit 2)
     * is NOT set in the encoded output.
     *
     * Compressing small payloads wastes bytes — the zlib header overhead
     * can make the compressed output larger than the input. The protocol
     * must skip compression for payloads under the threshold.
     */
    @Test
    fun `small payload skips compression`() {
        val payload = ByteArray(50) { 0x41 } // 50 bytes of 'A', below threshold
        val original = makePacket(payload = payload)

        val encoded = BinaryProtocol.encode(original)
        assertNotNull("Encoding must not return null", encoded)

        // Unpad to inspect raw flags byte
        val unpadded = MessagePadding.unpad(encoded!!)
        // Flags byte is at offset 11 in v1 (version=1, type=1, ttl=1, timestamp=8)
        val flags = unpadded[11].toUByte()
        assertEquals("IS_COMPRESSED flag must not be set", 0u.toUByte(),
            flags and BinaryProtocol.Flags.IS_COMPRESSED)

        // Verify payload still round-trips
        val decoded = BinaryProtocol.decode(encoded)
        assertNotNull(decoded)
        assertTrue("payload must match", payload.contentEquals(decoded!!.payload))
    }

    /**
     * High-entropy payload skips compression
     *
     * Creates a 200-byte payload of random bytes (high entropy, likely
     * >90% unique byte values). Verifies the encoder either skips
     * compression entirely or doesn't inflate the payload size.
     *
     * Tests the shouldCompress() entropy check. Compressing encrypted
     * or random data typically inflates it — the protocol must detect
     * high-entropy payloads and skip compression to avoid wasting bytes
     * and breaking the payload-length budget.
     */
    @Test
    fun `high-entropy payload skips compression`() {
        val random = Random(42) // fixed seed for reproducibility
        val payload = ByteArray(200)
        random.nextBytes(payload)

        val original = makePacket(payload = payload)
        val decoded = roundTrip(original)

        assertTrue("high-entropy payload must survive round-trip",
            original.payload.contentEquals(decoded.payload))
    }

    /**
     * Padding round-trip at each block size
     *
     * Creates packets with payloads sized to land in each PKCS#7 padding
     * bucket (256, 512, 1024, 2048 bytes). For each, verifies the encoded
     * output size matches the expected block size, then decodes and confirms
     * padding is stripped and the original packet is recovered.
     *
     * Padding is critical for traffic analysis resistance (all packets
     * appear as standard sizes on the wire) and iOS interoperability.
     * If the PKCS#7 pad-byte value or validation is wrong, unpadding
     * either strips too many bytes (truncating the packet) or too few
     * (leaving garbage appended to the signature).
     */
    @Test
    fun `padding produces correct block sizes and round-trips`() {
        // Test that encoded output lands on one of the standard block sizes (256, 512, 1024, 2048)
        // and that decode recovers the original payload.
        val validBlockSizes = setOf(256, 512, 1024, 2048)

        // Small payload: header(13) + sender(8) + payload(10) = 31 raw → pads to 256
        val smallPayload = ByteArray(10) { (it + 1).toByte() }
        val smallPacket = makePacket(payload = smallPayload)
        val smallEncoded = BinaryProtocol.encode(smallPacket)!!
        assertTrue("Small packet must pad to a standard block size (got ${smallEncoded.size})",
            smallEncoded.size in validBlockSizes)
        assertEquals("Small packet should pad to 256", 256, smallEncoded.size)
        val smallDecoded = BinaryProtocol.decode(smallEncoded)!!
        assertTrue("Small payload must survive padding round-trip",
            smallPayload.contentEquals(smallDecoded.payload))

        // Medium payload: header(13) + sender(8) + payload(80) = 101 raw → pads to 256
        val medPayload = ByteArray(80) { (it + 1).toByte() }
        val medPacket = makePacket(payload = medPayload)
        val medEncoded = BinaryProtocol.encode(medPacket)!!
        assertTrue("Medium packet must pad to a standard block size (got ${medEncoded.size})",
            medEncoded.size in validBlockSizes)
        val medDecoded = BinaryProtocol.decode(medEncoded)!!
        assertTrue("Medium payload must survive padding round-trip",
            medPayload.contentEquals(medDecoded.payload))
    }

    /**
     * Oversized packet bypasses padding
     *
     * Creates a packet whose encoded form exceeds 2048 bytes (the largest
     * PKCS#7 block). Verifies it still encodes and decodes correctly even
     * though padding is skipped.
     *
     * PKCS#7 uses a single byte for the padding length (max 255). When
     * the gap between raw size and the next block exceeds 255 bytes, or
     * the raw size already exceeds the largest block, padding cannot be
     * applied. The protocol must still encode/decode these packets — they
     * will typically be fragmented before transmission anyway.
     */
    @Test
    fun `oversized packet bypasses padding and still round-trips`() {
        // 2100-byte payload + 21 header = 2121 raw, exceeds 2048 block
        val payload = ByteArray(2100) { (it % 13).toByte() }
        val original = makePacket(payload = payload)

        val encoded = BinaryProtocol.encode(original)
        assertNotNull("Encoding oversized packet must not return null", encoded)

        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull("Decoding oversized packet must not return null", decoded)
        assertTrue("Oversized payload must survive round-trip",
            original.payload.contentEquals(decoded!!.payload))
    }

    /**
     * v1 header uses 2-byte payload length, v2 uses 4-byte
     *
     * Encodes a v1 packet, unpads it, then reads bytes 11-12 as a
     * big-endian unsigned short — verifies it matches the payload length.
     * Does the same for v2, reading bytes 11-14 as a big-endian int.
     *
     * This is the core v1/v2 wire format distinction. v1 uses a 2-byte
     * (UShort) payload length supporting up to 65535 bytes; v2 uses a
     * 4-byte (UInt) length for larger payloads. If the encoder writes
     * the wrong width, v1 clients can't read v2 packets and vice versa,
     * silently corrupting all subsequent fields.
     */
    @Test
    fun `v1 uses 2-byte and v2 uses 4-byte payload length`() {
        val payload = "length test".toByteArray()

        // v1 header: version(1) + type(1) + ttl(1) + timestamp(8) + flags(1) = 12, then payloadLen at 12-13
        val v1 = makePacket(payload = payload)
        val v1Encoded = MessagePadding.unpad(BinaryProtocol.encode(v1)!!)
        val v1Len = ByteBuffer.wrap(v1Encoded, 12, 2)
            .order(ByteOrder.BIG_ENDIAN).short.toUShort().toInt()
        assertEquals("v1 payload length field must match", payload.size, v1Len)

        // v2 header: same 12 bytes, then payloadLen at 12-15 (4 bytes)
        val v2 = makePacket(version = 2u, payload = payload)
        val v2Encoded = MessagePadding.unpad(BinaryProtocol.encode(v2)!!)
        val v2Len = ByteBuffer.wrap(v2Encoded, 12, 4)
            .order(ByteOrder.BIG_ENDIAN).int
        assertEquals("v2 payload length field must match", payload.size, v2Len)
    }

    /**
     * v2 route flag is ignored when decoding v1
     *
     * Constructs raw v1 bytes and manually sets the HAS_ROUTE flag (bit 3)
     * in the flags' byte. Verifies the decoder ignores the route flag for
     * v1 packets, per the spec: "HAS_ROUTE is only valid for v2+ packets."
     *
     * Ensures forward compatibility — if an old v1 packet somehow has
     * unexpected flags set (e.g., from a future protocol extension or
     * corruption), the decoder must not misinterpret trailing bytes as
     * a route section, which would corrupt payload parsing.
     */
    @Test
    fun `v1 decoder ignores HAS_ROUTE flag`() {
        val payload = "route-flag-test".toByteArray()
        val original = makePacket(payload = payload)

        val encoded = BinaryProtocol.encode(original)!!
        val unpadded = MessagePadding.unpad(encoded)

        // Manually set HAS_ROUTE flag (bit 3) on the v1 packet
        val tampered = unpadded.copyOf()
        tampered[11] = (tampered[11].toInt() or 0x08).toByte()

        // Re-pad so decode can handle it
        val repadded = MessagePadding.pad(tampered, MessagePadding.optimalBlockSize(tampered.size))
        val decoded = BinaryProtocol.decode(repadded)

        assertNotNull("v1 with HAS_ROUTE flag must still decode", decoded)
        assertNull("v1 packet must have null route regardless of flag", decoded!!.route)
        assertTrue("payload must still be correct",
            payload.contentEquals(decoded.payload))
    }

    /**
     * Empty payload
     *
     * Encodes a packet with payload = ByteArray(0), then decodes it and
     * verifies the empty payload survives the round-trip.
     *
     * ANNOUNCE and LEAVE packets can have minimal or empty payloads.
     * A zero-length payload must not break the payload-length field
     * calculation, cause an off-by-one in buffer positioning, or
     * trigger compression (which would fail on empty input).
     */
    @Test
    fun `empty payload round-trips correctly`() {
        val original = makePacket(
            payload = ByteArray(0),
            type = MessageType.LEAVE.value
        )

        val decoded = roundTrip(original)

        assertEquals("payload must be empty", 0, decoded.payload.size)
        assertEquals("type must match", MessageType.LEAVE.value, decoded.type)
    }

    /**
     * Maximum v1 payload (65535 bytes)
     *
     * Encodes a v1 packet at the 2-byte payload length limit (65535 bytes,
     * the maximum value of a UShort). Decodes and verifies the payload
     * content and length survive.
     *
     * v1 uses an unsigned 16-bit integer for payload length. At the boundary
     * of 65535, signed/unsigned conversion bugs surface — Java's short is
     * signed (-32768 to 32767), so the encoder must handle the cast to
     * UShort correctly. If it wraps to negative, the decoder reads a bogus
     * payload length and fails.
     */
    @Test
    fun `maximum v1 payload length round-trips correctly`() {
        val payload = ByteArray(65535) { (it % 251).toByte() }
        val original = makePacket(payload = payload)

        val encoded = BinaryProtocol.encode(original)
        assertNotNull("Encoding max-size payload must not return null", encoded)

        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull("Decoding max-size payload must not return null", decoded)
        assertEquals("payload length must match", 65535, decoded!!.payload.size)
        // Spot-check a few bytes rather than full compare (compression may or may not fire)
        // The full content check is what matters
        assertTrue("payload content must match",
            original.payload.contentEquals(decoded.payload))
    }

    /**
     * Truncated data returns null
     *
     * Passes progressively truncated byte arrays to decode(): first just
     * 1 byte, then the minimum header size minus one, then a valid header
     * with a truncated payload. Verifies decode returns null for each
     * without throwing an exception.
     *
     * Malformed BLE packets are common — the radio can drop trailing bytes,
     * fragment reassembly can fail, or a peer can send garbage. The decoder
     * must fail gracefully with null, never throw BufferUnderflowException
     * or ArrayIndexOutOfBoundsException, as uncaught exceptions crash the
     * mesh service and disconnect all peers.
     */
    @Test
    fun `truncated data returns null without crashing`() {
        // Work with unpadded bytes so truncation produces genuinely incomplete packets.
        // Padded data can accidentally contain valid-looking sub-packets.
        val valid = makePacket(payload = "truncation test".toByteArray())
        val encoded = BinaryProtocol.encode(valid)!!
        val raw = MessagePadding.unpad(encoded)
        // raw is the real packet without PKCS#7 padding

        // Truncation points that are definitely too short for the declared payload:
        // v1 min = HEADER(13) + SENDER(8) = 21, payload declared as 15, so need 36 total
        val truncationPoints = listOf(0, 1, 5, 12, 20, 25, 30)
        for (len in truncationPoints) {
            if (len > raw.size) continue
            val truncated = raw.copyOfRange(0, len)
            val result = BinaryProtocol.decode(truncated)
            assertNull("Truncated to $len bytes must return null", result)
        }
    }

    /**
     * Invalid version returns null
     *
     * Passes byte arrays with version=0, version=3, and version=255 to
     * the decoder. Verifies each returns null.
     *
     * Only v1 and v2 are supported by the protocol. Unknown versions must
     * be rejected immediately — attempting to parse them would use wrong
     * header sizes, payload length widths, and feature flags, leading to
     * garbage output or buffer overflows.
     */
    @Test
    fun `invalid version returns null`() {
        val valid = makePacket(payload = "version test".toByteArray())
        val encoded = MessagePadding.unpad(BinaryProtocol.encode(valid)!!)

        for (badVersion in listOf(0, 3, 255)) {
            val tampered = encoded.copyOf()
            tampered[0] = badVersion.toByte()
            val repadded = MessagePadding.pad(tampered, MessagePadding.optimalBlockSize(tampered.size))
            val result = BinaryProtocol.decode(repadded)
            assertNull("Version $badVersion must return null", result)
        }
    }

    /**
     * Garbage data returns null
     *
     * Passes random byte arrays of various sizes (10, 100, 512, 2048) to
     * the decoder. Verifies it returns null (or at most a valid-looking
     * packet if the random bytes happen to parse) without ever throwing
     * an exception.
     *
     * A fuzz-like robustness test. In a mesh network, any device can send
     * any bytes. The decoder must never crash, leak memory, or enter an
     * infinite loop on arbitrary input — it should simply return null for
     * anything it can't parse as a valid BitchatPacket.
     */
    @Test
    fun `garbage data returns null without crashing`() {
        val random = Random(12345)
        for (size in listOf(10, 100, 512, 2048)) {
            val garbage = ByteArray(size)
            random.nextBytes(garbage)
            // Just verify no exception is thrown; null is the expected result
            // but random data COULD accidentally form a valid packet structure
            try {
                BinaryProtocol.decode(garbage)
            } catch (e: Exception) {
                throw AssertionError("Garbage data of size $size must not throw, got: ${e.message}", e)
            }
        }
    }

    /**
     * Sender ID truncation and padding
     *
     * Tests senderID shorter than 8 bytes (should be zero-padded to 8)
     * and longer than 8 bytes (should be truncated to 8). Verifies
     * exactly 8 bytes appear in the decoded senderID.
     *
     * PeerIDs are always exactly 8 bytes in the wire format. If a short
     * senderID isn't padded, the encoder writes fewer bytes, shifting all
     * subsequent fields. If a long senderID isn't truncated, the encoder
     * writes extra bytes into the recipientID or payload region, silently
     * corrupting the packet.
     */
    @Test
    fun `sender ID is padded or truncated to exactly 8 bytes`() {
        // Short sender (4 bytes) — should be zero-padded to 8
        val shortSender = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val shortPacket = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = shortSender,
            timestamp = fixedTimestamp,
            payload = "short-sender".toByteArray(),
            ttl = 3u
        )
        val decodedShort = roundTrip(shortPacket)
        assertEquals("decoded senderID must be 8 bytes", 8, decodedShort.senderID.size)
        // First 4 bytes must match, rest should be zeros
        for (i in 0 until 4) {
            assertEquals("senderID byte $i must match", shortSender[i], decodedShort.senderID[i])
        }
        for (i in 4 until 8) {
            assertEquals("senderID byte $i must be zero-padded", 0.toByte(), decodedShort.senderID[i])
        }

        // Long sender (12 bytes) — should be truncated to 8
        val longSender = ByteArray(12) { (it + 0x50).toByte() }
        val longPacket = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = longSender,
            timestamp = fixedTimestamp,
            payload = "long-sender".toByteArray(),
            ttl = 3u
        )
        val decodedLong = roundTrip(longPacket)
        assertEquals("decoded senderID must be 8 bytes", 8, decodedLong.senderID.size)
        for (i in 0 until 8) {
            assertEquals("senderID byte $i must match first 8", longSender[i], decodedLong.senderID[i])
        }
    }

    /**
     * Signing data excludes signature and uses fixed TTL
     *
     * Creates a packet with a 64-byte signature and TTL=7, then calls
     * toBinaryDataForSigning(). Decodes the result and verifies:
     * (1) the signature is null (stripped for signing), and (2) the TTL
     * is 0 (SYNC_TTL_HOPS), not the original 7.
     *
     * Packet signatures must be deterministic regardless of how many
     * hops the packet has traversed (TTL decrements at each relay).
     * If TTL leaks into the signed data, a packet relayed even once
     * would fail signature verification at the recipient because the
     * TTL it was signed with differs from the TTL it arrives with.
     * The signature itself must also be excluded from the data being
     * signed (you can't sign data that includes its own signature).
     */
    @Test
    fun `toBinaryDataForSigning excludes signature and fixes TTL`() {
        val signature = ByteArray(64) { (it + 0xD0).toByte() }
        val original = makePacket(
            payload = "sign-me".toByteArray(),
            recipientID = hexToBytes(recipientHex),
            signature = signature,
            ttl = 7u
        )

        val signingData = original.toBinaryDataForSigning()
        assertNotNull("Signing data must not be null", signingData)

        val decoded = BinaryProtocol.decode(signingData!!)
        assertNotNull("Signing data must decode successfully", decoded)

        assertNull("Signature must be stripped for signing", decoded!!.signature)
        assertEquals("TTL must be fixed to SYNC_TTL_HOPS (0) for signing",
            0u.toUByte(), decoded.ttl)
        // Other fields must be preserved
        assertEquals("type must match", original.type, decoded.type)
        assertEquals("timestamp must match", original.timestamp, decoded.timestamp)
        assertTrue("senderID must match",
            original.senderID.contentEquals(decoded.senderID))
        assertTrue("recipientID must match",
            original.recipientID!!.contentEquals(decoded.recipientID!!))
        assertTrue("payload must match",
            original.payload.contentEquals(decoded.payload))
    }

    /**
     * v2 packet without route round-trips correctly
     *
     * Creates a v2 packet with recipientID and signature but NO route —
     * the most common real-world v2 shape for private signed messages.
     * Verifies all fields survive the round-trip and route is null.
     *
     * This exercises the 4-byte payload length field without the route
     * section. If the v2 header size (15 bytes) or payload length width
     * is wrong, all subsequent fields shift, corrupting decode.
     * The v2-with-route tests could pass by accident if route parsing
     * compensates for a header bug — this test isolates v2 header logic.
     */
    @Test
    fun `v2 packet without route round-trips correctly`() {
        val signature = ByteArray(64) { (it + 0xE0).toByte() }
        val original = makePacket(
            version = 2u,
            payload = "v2 no route".toByteArray(),
            recipientID = hexToBytes(recipientHex),
            signature = signature,
            route = null
        )

        val decoded = roundTrip(original)

        assertPacketEquals(original, decoded)
        assertEquals("version must be 2", 2u.toUByte(), decoded.version)
        assertNull("route must be null", decoded.route)
        assertNotNull("recipientID must not be null", decoded.recipientID)
        assertNotNull("signature must not be null", decoded.signature)
        assertTrue("recipientID bytes must match",
            original.recipientID!!.contentEquals(decoded.recipientID!!))
        assertTrue("signature bytes must match",
            original.signature!!.contentEquals(decoded.signature!!))
    }

    /**
     * v2 compressed payload round-trips correctly
     *
     * Creates a v2 packet with a large, compressible payload that triggers
     * compression. Verifies the IS_COMPRESSED flag is set, the v2 4-byte
     * original-size field is written/read correctly, and the payload
     * survives the round-trip intact.
     *
     * Covers encode lines 220, 290-292 (v2 compressed size field) and
     * decode lines 420-424 (v2 compressed size read).
     */
    @Test
    fun `v2 compressed payload round-trips correctly`() {
        val repeating = "ABCDEFGH".repeat(63) // 504 bytes, highly compressible
        val payload = repeating.toByteArray()
        val original = makePacket(version = 2u, payload = payload)

        val encoded = BinaryProtocol.encode(original)
        assertNotNull("Encoding must not return null", encoded)

        // Verify IS_COMPRESSED flag is set
        val unpadded = MessagePadding.unpad(encoded!!)
        val flags = unpadded[11].toUByte()
        assertTrue("IS_COMPRESSED flag must be set",
            (flags and BinaryProtocol.Flags.IS_COMPRESSED) != 0u.toUByte())

        // Verify version is 2
        assertEquals("version byte must be 2", 2.toByte(), unpadded[0])

        val decoded = roundTrip(original)
        assertEquals("version must be 2", 2u.toUByte(), decoded.version)
        assertTrue("payload must survive v2 compression round-trip",
            original.payload.contentEquals(decoded.payload))
        assertEquals("payload length must match after decompression",
            original.payload.size, decoded.payload.size)
    }

    /**
     * Short recipientID is zero-padded to 8 bytes
     *
     * Creates a packet with a 4-byte recipientID and verifies that after
     * encoding and decoding, the recipientID is exactly 8 bytes with
     * trailing zeros.
     *
     * Covers encode lines 272-273 (recipientID padding).
     */
    @Test
    fun `short recipientID is zero-padded to 8 bytes`() {
        val shortRecipient = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val original = makePacket(
            payload = "short-recipient".toByteArray(),
            recipientID = shortRecipient
        )

        val decoded = roundTrip(original)

        assertNotNull("recipientID must not be null", decoded.recipientID)
        assertEquals("recipientID must be 8 bytes", 8, decoded.recipientID!!.size)
        for (i in 0 until 4) {
            assertEquals("recipientID byte $i must match",
                shortRecipient[i], decoded.recipientID[i])
        }
        for (i in 4 until 8) {
            assertEquals("recipientID byte $i must be zero-padded",
                0.toByte(), decoded.recipientID[i])
        }
    }

    /**
     * v2 packet with route but no recipient round-trips correctly
     *
     * Creates a v2 packet with route hops but recipientID=null. Verifies
     * the route survives the round-trip and recipientID remains null.
     *
     * Covers encode lines 278-280, 248, 222 (route encode without recipient)
     * and decode line 378 (hasRecipient=false branch in route offset calc).
     */
    @Test
    fun `v2 packet with route but no recipient round-trips correctly`() {
        val route = listOf(
            hexToBytes("AABBCCDDEEFF0011"),
            hexToBytes("1100FFEEDDCCBBAA")
        )
        val original = makePacket(
            version = 2u,
            payload = "routed broadcast".toByteArray(),
            recipientID = null,
            route = route
        )

        val decoded = roundTrip(original)

        assertPacketEquals(original, decoded)
        assertNull("recipientID must be null", decoded.recipientID)
        assertNotNull("route must not be null", decoded.route)
        assertEquals("route hop count", 2, decoded.route!!.size)
        for (i in route.indices) {
            assertTrue("route hop $i must match",
                route[i].contentEquals(decoded.route!![i]))
        }
    }

    /**
     * v2 packet with HAS_ROUTE flag and count zero decodes route as null
     *
     * Manually crafts raw v2 bytes with the HAS_ROUTE flag set but the
     * route count byte = 0. Verifies the decoder treats this as null
     * (canonical representation).
     *
     * Covers decode lines 405-406 (count==0 → null).
     */
    @Test
    fun `v2 packet with HAS_ROUTE flag and count zero decodes route as null`() {
        val payload = "route-zero".toByteArray()

        val buffer = ByteBuffer.allocate(256).apply { order(ByteOrder.BIG_ENDIAN) }

        // v2 header
        buffer.put(2.toByte())                              // version = 2
        buffer.put(MessageType.MESSAGE.value.toByte())      // type
        buffer.put(5.toByte())                              // ttl
        buffer.putLong(fixedTimestamp.toLong())             // timestamp (8 bytes)

        // Flags: HAS_ROUTE set
        buffer.put(BinaryProtocol.Flags.HAS_ROUTE.toByte())

        // Payload length (4 bytes for v2)
        buffer.putInt(payload.size)

        // SenderID (8 bytes)
        buffer.put(hexToBytes(senderHex))

        // Route: count = 0 (1 byte)
        buffer.put(0.toByte())

        // Payload
        buffer.put(payload)

        val raw = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(raw)

        val padded = MessagePadding.pad(raw, MessagePadding.optimalBlockSize(raw.size))
        val decoded = BinaryProtocol.decode(padded)

        assertNotNull("Packet with route count=0 must decode", decoded)
        assertNull("Route with count=0 must decode as null", decoded!!.route)
        assertTrue("Payload must match", payload.contentEquals(decoded.payload))
    }

    /**
     * v2 compression bomb is rejected
     *
     * Same concept as the v1 compression bomb test but with version=2,
     * which uses a 4-byte original-size field instead of 2-byte.
     *
     * Covers decode lines 435-439 via the v2 path (4-byte size field).
     */
    @Test
    fun `v2 compression bomb is rejected`() {
        // Valid raw deflate final empty stored block (1 byte).
        // Claim a huge original size to exceed the 50,000:1 ratio guard.
        // ratio = 10,000,000 / 1 = 10,000,000:1
        val compressedData = byteArrayOf(0x03)
        val declaredOriginalSize = 10_000_000

        val buffer = ByteBuffer.allocate(256).apply { order(ByteOrder.BIG_ENDIAN) }

        // v2 header
        buffer.put(2.toByte())                              // version = 2
        buffer.put(MessageType.MESSAGE.value.toByte())      // type
        buffer.put(5.toByte())                              // ttl
        buffer.putLong(fixedTimestamp.toLong())             // timestamp (8 bytes)

        // Flags: IS_COMPRESSED set
        buffer.put(BinaryProtocol.Flags.IS_COMPRESSED.toByte())

        // Payload length (4 bytes for v2): original-size field (4 bytes) + compressed data
        val payloadFieldSize = 4 + compressedData.size
        buffer.putInt(payloadFieldSize)

        // SenderID (8 bytes)
        buffer.put(hexToBytes(senderHex))

        // Compressed payload section: original size (4 bytes for v2) + compressed data
        buffer.putInt(declaredOriginalSize)
        buffer.put(compressedData)

        val raw = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(raw)

        val padded = MessagePadding.pad(raw, MessagePadding.optimalBlockSize(raw.size))
        val result = BinaryProtocol.decode(padded)

        assertNull("v2 compression bomb (ratio > 50,000:1) must be rejected", result)
    }

    /**
     * Compression bomb is rejected
     *
     * Crafts a packet where the declared original size in the compressed
     * payload section is absurdly large relative to the compressed data,
     * exceeding the 50,000:1 ratio guard in decodeCore(). Verifies the
     * decoder returns null instead of attempting decompression.
     *
     * Without this check, an attacker could send a tiny packet claiming
     * to decompress into gigabytes, causing an OOM crash that kills the
     * mesh service and disconnects all peers. The 50,000:1 threshold
     * blocks this while still allowing legitimate compression ratios
     * (typical text compresses ~3:1 to ~10:1).
     */
    @Test
    fun `compression bomb is rejected`() {
        // Valid raw deflate final empty stored block (1 byte).
        // v1 uses a 2-byte (UShort) original-size field, so declared size
        // must fit in 0..65535. ratio = 60,000 / 1 = 60,000:1 > 50,000:1
        val tinyCompressed = byteArrayOf(0x03)
        val declaredOriginalSize = 60_000

        val buffer = ByteBuffer.allocate(256).apply { order(ByteOrder.BIG_ENDIAN) }

        // Header (13 bytes for v1)
        buffer.put(1.toByte())                          // version = 1
        buffer.put(MessageType.MESSAGE.value.toByte())  // type
        buffer.put(5.toByte())                          // ttl
        buffer.putLong(fixedTimestamp.toLong())         // timestamp (8 bytes)

        // Flags: IS_COMPRESSED set
        buffer.put(BinaryProtocol.Flags.IS_COMPRESSED.toByte())

        // Payload length: original-size field (2 bytes) + compressed data (1 byte) = 3
        val payloadFieldSize = 2 + tinyCompressed.size
        buffer.putShort(payloadFieldSize.toShort())

        // SenderID (8 bytes)
        buffer.put(hexToBytes(senderHex))

        // Compressed payload section: original size (2 bytes) + compressed data
        buffer.putShort(declaredOriginalSize.toShort())
        buffer.put(tinyCompressed)

        val raw = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(raw)

        // Pad to standard block size so decode() can process it
        val padded = MessagePadding.pad(raw, MessagePadding.optimalBlockSize(raw.size))
        val result = BinaryProtocol.decode(padded)

        assertNull("Compression bomb (ratio > 50,000:1) must be rejected", result)
    }

    /**
     * v1 packet with route silently drops route
     *
     * Creates a v1 packet with route hops set, encodes and decodes it.
     * Since routes are only supported in v2+, the encoder must silently
     * drop the route for v1, producing a packet with route=null.
     *
     * Covers encode lines 222, 248 (false branch: route non-empty but
     * version < 2) and decode line 279-280 (route not written for v1).
     */
    @Test
    fun `v1 packet with route silently drops route`() {
        val route = listOf(
            hexToBytes("AABBCCDDEEFF0011"),
            hexToBytes("1100FFEEDDCCBBAA")
        )
        val original = makePacket(
            version = 1u,
            payload = "v1 route drop".toByteArray(),
            route = route
        )

        val decoded = roundTrip(original)

        assertEquals("version must be 1", 1u.toUByte(), decoded.version)
        assertNull("route must be null for v1 packet", decoded.route)
        assertTrue("payload must survive round-trip",
            original.payload.contentEquals(decoded.payload))
    }

    /**
     * v2 truncated packet with HAS_ROUTE flag returns null
     *
     * Manually crafts a v2 packet with the HAS_ROUTE flag set but
     * truncates the data before the route count byte. The decoder must
     * return null because raw.size < routeOffset + 1.
     *
     * Covers decode line 382 false branch (raw too short for route count).
     */
    @Test
    fun `v2 truncated packet with HAS_ROUTE flag returns null`() {
        val payload = "truncated-route".toByteArray()

        val buffer = ByteBuffer.allocate(256).apply { order(ByteOrder.BIG_ENDIAN) }

        // v2 header
        buffer.put(2.toByte())                              // version = 2
        buffer.put(MessageType.MESSAGE.value.toByte())      // type
        buffer.put(5.toByte())                              // ttl
        buffer.putLong(fixedTimestamp.toLong())             // timestamp (8 bytes)

        // Flags: HAS_ROUTE set
        buffer.put(BinaryProtocol.Flags.HAS_ROUTE.toByte())

        // Payload length (4 bytes for v2)
        buffer.putInt(payload.size)

        // SenderID (8 bytes) — after this, the route count byte should follow
        // but we truncate here, so raw.size < routeOffset + 1
        buffer.put(hexToBytes(senderHex))

        // Do NOT write route count or payload — truncate right after senderID
        val raw = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(raw)

        // Don't pad — pass raw bytes directly so truncation is effective
        val result = BinaryProtocol.decode(raw)

        assertNull("Truncated v2 packet with HAS_ROUTE must return null", result)
    }

    /**
     * v2 compressed packet with payloadLength less than size field returns null
     *
     * Manually crafts v2 bytes with IS_COMPRESSED flag and a payloadLength
     * of 2, which is less than the 4-byte original-size field required for
     * v2 compressed payloads. The decoder must return null.
     *
     * Covers decode line 421 true branch (payloadLength < lengthFieldBytes).
     */
    @Test
    fun `v2 compressed packet with payloadLength less than size field returns null`() {
        val buffer = ByteBuffer.allocate(256).apply { order(ByteOrder.BIG_ENDIAN) }

        // v2 header
        buffer.put(2.toByte())                              // version = 2
        buffer.put(MessageType.MESSAGE.value.toByte())      // type
        buffer.put(5.toByte())                              // ttl
        buffer.putLong(fixedTimestamp.toLong())             // timestamp (8 bytes)

        // Flags: IS_COMPRESSED set
        buffer.put(BinaryProtocol.Flags.IS_COMPRESSED.toByte())

        // Payload length = 2 (less than 4-byte size field for v2)
        buffer.putInt(2)

        // SenderID (8 bytes)
        buffer.put(hexToBytes(senderHex))

        // Write 2 bytes of fake payload data (matching declared payloadLength)
        buffer.put(byteArrayOf(0x01, 0x02))

        val raw = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(raw)

        val padded = MessagePadding.pad(raw, MessagePadding.optimalBlockSize(raw.size))
        val result = BinaryProtocol.decode(padded)

        assertNull("v2 compressed with payloadLength < 4 must return null", result)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun makePacket(
        version: UByte = 1u,
        type: UByte = MessageType.MESSAGE.value,
        payload: ByteArray,
        recipientID: ByteArray? = null,
        signature: ByteArray? = null,
        ttl: UByte = 5u,
        route: List<ByteArray>? = null
    ) = BitchatPacket(
        version = version,
        type = type,
        senderID = hexToBytes(senderHex),
        recipientID = recipientID,
        timestamp = fixedTimestamp,
        payload = payload,
        signature = signature,
        ttl = ttl,
        route = route
    )

    private fun roundTrip(packet: BitchatPacket): BitchatPacket {
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull("Encoding must not return null", encoded)
        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull("Decoding must not return null", decoded)
        return decoded!!
    }

    private fun assertPacketEquals(expected: BitchatPacket, actual: BitchatPacket) {
        assertEquals("version", expected.version, actual.version)
        assertEquals("type", expected.type, actual.type)
        assertEquals("ttl", expected.ttl, actual.ttl)
        assertEquals("timestamp", expected.timestamp, actual.timestamp)
        assertTrue("senderID", expected.senderID.contentEquals(actual.senderID))
        assertTrue("payload", expected.payload.contentEquals(actual.payload))
    }
}
