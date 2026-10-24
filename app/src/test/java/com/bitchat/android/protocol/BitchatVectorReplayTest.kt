package com.bitchat.android.protocol

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.FragmentPayload
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.noise.NoisePeerIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Replays the shared cross-platform wire-format vectors against this codec.
 *
 * The vector file pins the exact bytes this codec produced at a known revision for
 * BinaryProtocol, MessagePadding, CompressionUtil, IdentityAnnouncement,
 * PeerCapabilities, NoisePayload, PrivateMessagePacket, BitchatFilePacket,
 * FragmentPayload and NoisePeerIdentity. The same file is replayed by other
 * clients, so a change that breaks this test is a wire-format change other
 * implementations will see.
 *
 * Location: the env var SONAR_VECTOR_FILE, else the classpath resource
 * /bitchat-vectors/bitchat-android.json (app/src/test/resources/bitchat-vectors/).
 *
 * Hex encoding: lowercase hex, or "repeat:xx*N" for one byte repeated N times.
 *
 * DEFLATE: java.util.zip.Deflater output is deterministic for a given zlib build
 * (the JDK bundles its own), so `compress` cases are byte-exact by default. If a
 * JDK upgrade changes the stream, set SONAR_VECTOR_DEFLATE_LENIENT=1 to require
 * only that both streams inflate back to the payload; the inflate direction is
 * always asserted, since that is what every peer must accept.
 */
class BitchatVectorReplayTest {

    private val failures = mutableListOf<String>()
    private val replayed = sortedMapOf<String, Int>()

    // ---------- loading ----------

    private fun loadVectors(): JsonObject {
        val envPath = System.getenv("SONAR_VECTOR_FILE")
        val text = if (!envPath.isNullOrBlank()) {
            File(envPath).readText()
        } else {
            val stream = javaClass.getResourceAsStream("/bitchat-vectors/bitchat-android.json")
                ?: error("vector file not found: set SONAR_VECTOR_FILE or add app/src/test/resources/bitchat-vectors/bitchat-android.json")
            stream.bufferedReader().use { it.readText() }
        }
        return JsonParser.parseString(text).asJsonObject
    }

    // ---------- json helpers ----------

    private fun JsonObject.opt(key: String): JsonElement? {
        val e = get(key) ?: return null
        return if (e.isJsonNull) null else e
    }

    private fun unhex(s: String): ByteArray {
        if (s.startsWith("repeat:")) {
            val (byteHex, count) = s.removePrefix("repeat:").split("*")
            return ByteArray(count.toInt()) { byteHex.toInt(16).toByte() }
        }
        require(s.length % 2 == 0) { "odd hex length: $s" }
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun hex(b: ByteArray?): String? = b?.joinToString("") { "%02x".format(it) }

    private fun JsonObject.bytes(key: String): ByteArray? = opt(key)?.asString?.let { unhex(it) }
    private fun JsonObject.str(key: String): String? = opt(key)?.asString
    private fun JsonObject.int(key: String): Int? = opt(key)?.asInt
    private fun JsonObject.long(key: String): Long? = opt(key)?.asLong
    private fun JsonObject.bool(key: String): Boolean? = opt(key)?.asBoolean
    private fun JsonObject.strList(key: String): List<String>? = opt(key)?.asJsonArray?.map { it.asString }

    // ---------- assertion helpers ----------

    private fun show(v: Any?): String = when (v) {
        null -> "null"
        is ByteArray -> "hex(${v.size})=" + (hex(v)!!.let { if (it.length > 96) it.take(96) + "..." else it })
        else -> v.toString()
    }

    private fun same(expected: Any?, actual: Any?): Boolean = when {
        expected == null && actual == null -> true
        expected is ByteArray && actual is ByteArray -> expected.contentEquals(actual)
        expected is Number && actual is Number -> expected.toLong() == actual.toLong()
        else -> expected == actual
    }

    private fun check(case: String, field: String, expected: Any?, actual: Any?) {
        if (!same(expected, actual)) {
            failures += "$case / $field: expected ${show(expected)} but got ${show(actual)}"
        }
    }

    /** Assert only if the vector carries the field (absent key = not recorded). */
    private fun JsonObject.checkBytes(case: String, key: String, actual: ByteArray?) {
        if (has(key)) check(case, key, bytes(key), actual)
    }
    private fun JsonObject.checkStr(case: String, key: String, actual: String?) {
        if (has(key)) check(case, key, str(key), actual)
    }
    private fun JsonObject.checkLong(case: String, key: String, actual: Long?) {
        if (has(key)) check(case, key, long(key), actual)
    }
    private fun JsonObject.checkBool(case: String, key: String, actual: Boolean?) {
        if (has(key)) check(case, key, bool(key), actual)
    }

    // ---------- the replay ----------

    @Test
    fun replayVectors() {
        val root = loadVectors()
        val cases = root.getAsJsonArray("cases")
        assertTrue("vector file has no cases", cases.size() > 0)

        for (element in cases) {
            val c = element.asJsonObject
            val name = c.get("name").asString
            val kind = c.get("kind").asString
            val input = c.getAsJsonObject("input")
            val exp = c.getAsJsonObject("expected")
            val tag = "$kind:$name"
            try {
                when (kind) {
                    "should_compress" -> replayShouldCompress(tag, input, exp)
                    "compress" -> replayCompress(tag, input, exp)
                    "padding" -> replayPadding(tag, input, exp)
                    "optimal_block_size" -> replayOptimalBlockSize(tag, input, exp)
                    "unpad" -> replayUnpad(tag, input, exp)
                    "packet_encode" -> replayPacketEncode(tag, input, exp)
                    "packet_reencode_preserves_wire" -> replayPacketReencode(tag, input, exp)
                    "packet_decode_reject" -> replayPacketDecodeReject(tag, input, exp)
                    "announce_tlv" -> replayAnnounce(tag, input, exp)
                    "capabilities" -> replayCapabilities(tag, input, exp)
                    "private_message_tlv" -> replayPrivateMessage(tag, input, exp)
                    "noise_payload" -> replayNoisePayload(tag, input, exp)
                    "file_packet_tlv" -> replayFilePacket(tag, input, exp)
                    "fragment_payload" -> replayFragment(tag, input, exp)
                    "peer_id" -> replayPeerId(tag, input, exp)
                    else -> failures += "$tag: unknown case kind '$kind' (add a replay for it)"
                }
            } catch (e: Exception) {
                failures += "$tag: threw ${e::class.simpleName}: ${e.message}"
            }
            replayed[kind] = (replayed[kind] ?: 0) + 1
        }

        println("BitchatVectorReplayTest replayed ${cases.size()} cases from ${root.get("source")?.asString}:")
        replayed.forEach { (k, n) -> println("  $k: $n") }
        if (failures.isNotEmpty()) {
            fail("${failures.size} vector mismatch(es):\n" + failures.joinToString("\n"))
        }
    }

    // ---------- per-kind replays ----------

    private fun replayShouldCompress(tag: String, input: JsonObject, exp: JsonObject) {
        val payload = input.bytes("payload")!!
        check(tag, "result", exp.bool("result"), CompressionUtil.shouldCompress(payload))
    }

    private fun replayCompress(tag: String, input: JsonObject, exp: JsonObject) {
        val payload = input.bytes("payload")!!
        val expected = exp.bytes("compressed")
        val actual = CompressionUtil.compress(payload)
        if (expected == null || actual == null) {
            check(tag, "compressed", expected, actual)
            return
        }
        // Inflate direction: every peer must accept the recorded stream.
        val inflated = CompressionUtil.decompress(expected, payload.size)
        check(tag, "inflate(recorded) == payload", payload, inflated)
        val lenient = System.getenv("SONAR_VECTOR_DEFLATE_LENIENT") == "1"
        if (lenient) {
            check(tag, "inflate(ours) == payload", payload, CompressionUtil.decompress(actual, payload.size))
        } else {
            check(tag, "compressed (byte-exact; set SONAR_VECTOR_DEFLATE_LENIENT=1 if the JDK's zlib changed)", expected, actual)
        }
        exp.checkBool(tag, "roundtrip_ok", inflated?.contentEquals(payload))
    }

    private fun replayPadding(tag: String, input: JsonObject, exp: JsonObject) {
        val data = input.bytes("data")!!
        val target = input.int("target")!!
        check(tag, "padded", exp.bytes("padded"), MessagePadding.pad(data, target))
    }

    private fun replayOptimalBlockSize(tag: String, input: JsonObject, exp: JsonObject) {
        check(tag, "block", exp.int("block"), MessagePadding.optimalBlockSize(input.int("size")!!))
    }

    private fun replayUnpad(tag: String, input: JsonObject, exp: JsonObject) {
        val data = input.bytes("data")!!
        val actual = MessagePadding.unpad(data)
        check(tag, "unpadded", exp.bytes("unpadded"), actual)
        exp.checkBool(tag, "changed", !actual.contentEquals(data))
    }

    private fun packetFromInput(input: JsonObject): BitchatPacket = BitchatPacket(
        version = input.int("version")!!.toUByte(),
        type = input.int("type")!!.toUByte(),
        senderID = input.bytes("sender_id")!!,
        recipientID = input.bytes("recipient_id"),
        timestamp = input.long("timestamp")!!.toULong(),
        payload = input.bytes("payload")!!,
        signature = input.bytes("signature"),
        ttl = input.int("ttl")!!.toUByte(),
        route = input.strList("route")?.map { unhex(it) }
    )

    private fun replayPacketEncode(tag: String, input: JsonObject, exp: JsonObject) {
        val pkt = packetFromInput(input)
        val padded = BinaryProtocol.encode(pkt, padding = true)
        val unpadded = BinaryProtocol.encode(pkt, padding = false)
        check(tag, "padded", exp.bytes("padded"), padded)
        check(tag, "unpadded", exp.bytes("unpadded"), unpadded)
        exp.checkBytes(tag, "signing_preimage", pkt.toBinaryDataForSigning())

        val decoded = padded?.let { BinaryProtocol.decode(it) }
        check(tag, "decodes", exp.bool("decodes"), decoded != null)
        exp.checkBytes(tag, "decoded_payload", decoded?.payload)
        exp.checkBool(tag, "decoded_payload_matches_input", decoded?.payload?.contentEquals(pkt.payload))
        if (exp.has("decoded_flags")) check(tag, "decoded_flags", exp.int("decoded_flags"), unpadded?.get(11)?.toInt()?.and(0xff))
        exp.checkBool(tag, "decoded_wire_compressed", decoded?.wirePayload?.compressed)
        exp.checkBytes(tag, "decoded_wire_bytes", decoded?.wirePayload?.bytes)
        if (exp.has("decoded_route")) check(tag, "decoded_route", exp.strList("decoded_route"), decoded?.route?.map { hex(it) })
        exp.checkBytes(tag, "decoded_recipient_id", decoded?.recipientID)
        exp.checkBytes(tag, "decoded_signature", decoded?.signature)
        exp.checkLong(tag, "decoded_ttl", decoded?.ttl?.toLong())
        exp.checkLong(tag, "decoded_timestamp", decoded?.timestamp?.toLong())
        if (exp.has("unpadded_decodes")) check(tag, "unpadded_decodes", exp.bool("unpadded_decodes"), unpadded?.let { BinaryProtocol.decode(it) } != null)
    }

    private fun replayPacketReencode(tag: String, input: JsonObject, exp: JsonObject) {
        val padded = input.bytes("padded")!!
        val decoded = BinaryProtocol.decode(padded)
        if (decoded == null) {
            failures += "$tag: recorded padded packet did not decode"
            return
        }
        val rePadded = BinaryProtocol.encode(decoded, padding = true)
        val reUnpadded = BinaryProtocol.encode(decoded, padding = false)
        check(tag, "reencoded", exp.bytes("reencoded"), reUnpadded)
        exp.checkBytes(tag, "reencoded_padded", rePadded)
        exp.checkBool(tag, "equals_original_padded", rePadded?.contentEquals(padded))
        exp.checkBool(tag, "decoded_wire_compressed", decoded.wirePayload?.compressed)
        exp.checkBytes(tag, "decoded_wire_bytes", decoded.wirePayload?.bytes)
        // The decoded packet must not carry the compressed bytes as its payload.
        check(tag, "decoded payload differs from wire bytes when compressed",
            true, decoded.wirePayload?.compressed != true || !decoded.payload.contentEquals(decoded.wirePayload.bytes))
        // Same packet without the wire payload: a fresh compression.
        val fresh = BinaryProtocol.encode(decoded.copy(wirePayload = null), padding = false)
        exp.checkBytes(tag, "fresh_reencode_without_wirepayload", fresh)
        if (exp.bool("equals_original_unpadded") == true) {
            // Then `reencoded` is the original unpadded frame, so compare the fresh encode against it.
            exp.checkBool(tag, "fresh_equals_original_unpadded", fresh?.contentEquals(reUnpadded!!))
        }
    }

    private fun replayPacketDecodeReject(tag: String, input: JsonObject, exp: JsonObject) {
        check(tag, "decodes", exp.bool("decodes"), BinaryProtocol.decode(input.bytes("bytes")!!) != null)
    }

    private fun unknownTlvs(dec: IdentityAnnouncement?): List<Map<String, Any>>? =
        dec?.unknownTLVs?.map { mapOf("type" to it.type, "value" to hex(it.value)!!) }

    private fun expectedUnknownTlvs(exp: JsonObject): List<Map<String, Any>>? =
        exp.opt("unknown_tlvs")?.asJsonArray?.map { e ->
            val o = e.asJsonObject
            mapOf("type" to o.int("type")!!, "value" to o.str("value")!!)
        }

    private fun replayAnnounce(tag: String, input: JsonObject, exp: JsonObject) {
        if (input.has("nickname")) {
            // Encode shape.
            val ann = IdentityAnnouncement(
                nickname = input.str("nickname")!!,
                noisePublicKey = input.bytes("noise_key")!!,
                signingPublicKey = input.bytes("signing_key")!!,
                capabilities = input.long("capabilities")?.let { PeerCapabilities(it) }
            )
            val enc = ann.encode()
            check(tag, "encoded", exp.bytes("encoded"), enc)
            val dec = enc?.let { IdentityAnnouncement.decode(it) }
            exp.checkLong(tag, "decoded_capabilities_raw", dec?.capabilities?.rawValue)
            exp.checkStr(tag, "decoded_nickname", dec?.nickname)
        } else {
            // Decode / re-encode shape.
            val dec = IdentityAnnouncement.decode(input.bytes("encoded")!!)
            check(tag, "decodes", exp.bool("decodes"), dec != null)
            exp.checkStr(tag, "nickname", dec?.nickname)
            exp.checkBytes(tag, "noise_key", dec?.noisePublicKey)
            exp.checkBytes(tag, "signing_key", dec?.signingPublicKey)
            exp.checkLong(tag, "capabilities_raw", dec?.capabilities?.rawValue)
            if (exp.has("unknown_tlvs")) check(tag, "unknown_tlvs", expectedUnknownTlvs(exp), unknownTlvs(dec))
            exp.checkBytes(tag, "reencoded", dec?.encode())
        }
    }

    private fun replayCapabilities(tag: String, input: JsonObject, exp: JsonObject) {
        if (input.has("raw")) {
            check(tag, "encoded", exp.bytes("encoded"), PeerCapabilities(input.long("raw")!!).encoded())
        } else {
            val dec = PeerCapabilities.decode(input.bytes("encoded")!!)
            check(tag, "raw", exp.long("raw"), dec.rawValue)
            exp.checkStr(tag, "raw_u64_hex", "%016x".format(dec.rawValue))
            exp.checkBytes(tag, "reencoded", dec.encoded())
        }
    }

    private fun replayPrivateMessage(tag: String, input: JsonObject, exp: JsonObject) {
        if (input.has("message_id")) {
            val enc = PrivateMessagePacket(input.str("message_id")!!, input.str("content")!!).encode()
            check(tag, "encoded", exp.bytes("encoded"), enc)
            val dec = enc?.let { PrivateMessagePacket.decode(it) }
            exp.checkBool(tag, "decodes", dec != null)
            exp.checkStr(tag, "decoded_message_id", dec?.messageID)
            exp.checkLong(tag, "decoded_content_len", dec?.content?.length?.toLong())
        } else {
            val dec = PrivateMessagePacket.decode(input.bytes("encoded")!!)
            check(tag, "decodes", exp.bool("decodes"), dec != null)
            exp.checkStr(tag, "decoded_message_id", dec?.messageID)
            exp.checkStr(tag, "decoded_content", dec?.content)
        }
    }

    private fun replayNoisePayload(tag: String, input: JsonObject, exp: JsonObject) {
        if (input.has("type")) {
            val type = NoisePayloadType.fromValue(input.int("type")!!.toUByte())
                ?: error("vector uses a NoisePayloadType this build does not know: ${input.int("type")}")
            check(tag, "encoded", exp.bytes("encoded"), NoisePayload(type, input.bytes("data")!!).encode())
        } else {
            val dec = NoisePayload.decode(input.bytes("encoded")!!)
            check(tag, "decodes", exp.bool("decodes"), dec != null)
            exp.checkStr(tag, "type_name", dec?.type?.name)
            exp.checkLong(tag, "type_value", dec?.type?.value?.toLong())
            exp.checkBytes(tag, "data", dec?.data)
            exp.checkBytes(tag, "reencoded", dec?.encode())
        }
    }

    private fun replayFilePacket(tag: String, input: JsonObject, exp: JsonObject) {
        if (input.has("file_name")) {
            val f = BitchatFilePacket(input.str("file_name")!!, input.long("file_size")!!, input.str("mime_type")!!, input.bytes("content")!!)
            val enc = f.encode()
            check(tag, "encoded", exp.bytes("encoded"), enc)
            val dec = enc?.let { BitchatFilePacket.decode(it) }
            exp.checkBool(tag, "decodes", dec != null)
            exp.checkLong(tag, "decoded_file_size", dec?.fileSize)
            exp.checkBool(tag, "decoded_content_matches", dec?.content?.contentEquals(f.content))
        } else {
            val dec = BitchatFilePacket.decode(input.bytes("encoded")!!)
            check(tag, "decodes", exp.bool("decodes"), dec != null)
            exp.checkStr(tag, "file_name", dec?.fileName)
            exp.checkLong(tag, "file_size", dec?.fileSize)
            exp.checkStr(tag, "mime_type", dec?.mimeType)
            exp.checkBytes(tag, "content", dec?.content)
            exp.checkBytes(tag, "reencoded", dec?.encode())
        }
    }

    private fun replayFragment(tag: String, input: JsonObject, exp: JsonObject) {
        val fp = FragmentPayload(
            fragmentID = input.bytes("fragment_id")!!,
            index = input.int("index")!!,
            total = input.int("total")!!,
            originalType = input.int("original_type")!!.toUByte(),
            data = input.bytes("data")!!
        )
        val enc = fp.encode()
        check(tag, "encoded", exp.bytes("encoded"), enc)
        val dec = FragmentPayload.decode(enc)
        exp.checkBool(tag, "decodes", dec != null)
        exp.checkLong(tag, "decoded_index", dec?.index?.toLong())
        exp.checkLong(tag, "decoded_total", dec?.total?.toLong())
    }

    private fun replayPeerId(tag: String, input: JsonObject, exp: JsonObject) {
        check(tag, "peer_id", exp.str("peer_id"), NoisePeerIdentity.derivePeerID(input.bytes("noise_static_pubkey")!!))
    }
}
