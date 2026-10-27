package com.bitchat.android.spam

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.min

/** Immutable, quantized Unicode bigrams with a unigram backoff for unobserved pairs. */
internal class CharacterMarkovModel private constructor(
    val threshold: Double,
    private val alphabet: IntArray,
    private val unigrams: IntArray,
    private val backoff: IntArray,
    private val pairs: IntArray,
    private val costs: IntArray,
    private val unknown: Int
) {
    fun score(points: IntArray): Double {
        require(points.isNotEmpty())
        var previous = alphabet.binarySearch(if (points.size == 1) ' '.code else points[0])
        var sum = 0L
        val start = if (points.size == 1) 0 else 1
        for (offset in start until points.size) {
            val next = alphabet.binarySearch(points[offset])
            val cost = if (previous < 0 || next < 0) {
                unknown
            } else {
                val index = pairs.binarySearch(previous * alphabet.size + next)
                if (index >= 0) costs[index] else min(MAX_COST, backoff[previous] + unigrams[next])
            }
            sum += cost
            previous = next
        }
        return sum.toDouble() / (points.size - start) / SCALE
    }

    companion object {
        private const val SCALE = 4096
        private const val MAX_COST = 65_495 // 15.99 nats, rounded to the nearest quantization step.
        private const val MAX_DECOMPRESSED_BYTES = 1_048_576

        /** Verify the fixed pack before exposing weights. Invalid bundled data must not admit text. */
        internal fun decode(digest: String, encoded: String): CharacterMarkovModel {
            require(encoded.length <= MAX_DECOMPRESSED_BYTES)
            val compressed = Base64.getDecoder().decode(encoded)
            val bytes = GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_DECOMPRESSED_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val expected = digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(expected.size == 32 && MessageDigest.isEqual(
                expected, MessageDigest.getInstance("SHA-256").digest(bytes)
            ))
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 0x42434d31) // BCM1
                val size = input.readUnsignedShort()
                require(size in 1..4096)
                val threshold = input.readFloat().toDouble()
                require(threshold.isFinite() && threshold > 0 && threshold <= 16)
                val unknown = input.readUnsignedShort()
                require(unknown in 1..MAX_COST && input.readUnsignedShort() == SCALE)
                val count = input.readInt()
                require(count in 1..(size * size))
                require(bytes.size == 18 + size * 8 + count * 6)
                val alphabet = IntArray(size)
                val unigrams = IntArray(size)
                val backoff = IntArray(size)
                repeat(size) { index ->
                    val point = input.readInt()
                    require(Character.isValidCodePoint(point) && point !in 0xd800..0xdfff)
                    require(index == 0 || point > alphabet[index - 1])
                    alphabet[index] = point
                    unigrams[index] = input.readUnsignedShort().also { require(it <= MAX_COST) }
                    backoff[index] = input.readUnsignedShort().also { require(it <= MAX_COST) }
                }
                val pairs = IntArray(count)
                val costs = IntArray(count)
                repeat(count) { index ->
                    val key = input.readInt()
                    require(key in 0 until size * size && (index == 0 || key > pairs[index - 1]))
                    pairs[index] = key
                    costs[index] = input.readUnsignedShort().also { require(it <= MAX_COST) }
                }
                require(input.available() == 0 && alphabet.binarySearch(' '.code) >= 0)
                CharacterMarkovModel(threshold, alphabet, unigrams, backoff, pairs, costs, unknown)
            }
        }
    }
}
