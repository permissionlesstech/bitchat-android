package com.bitchat.android.nostr

import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Time-lock tokens for geohash identities. Experimental: computing them is a debug switch.
 *
 * A token proves that [iterations] sequential squarings were done on a value derived from the
 * sender's pubkey and the geohash (a Wesolowski verifiable delay function in the RSA-2048 group,
 * whose factors nobody is known to hold). Each squaring needs the previous result, so a GPU
 * cannot speed up one token, and a server core is only a small factor faster than a phone. The
 * geohash is part of the input, so a token is worthless in any other channel. It leaves the
 * key unchanged and travels as a tag on the sender's geohash events:
 * `["vdf", iterations, y, proof]`.
 *
 * Checking a token costs a few hundred modular multiplications. Senders with a valid token skip
 * the spam filter's new-sender pool.
 */
object GeohashTimeLock {
    /** RSA-2048 from the RSA Factoring Challenge; unfactored, so nobody holds a shortcut. */
    val MODULUS = BigInteger("25195908475657893494027183240048398571429282126204032027777137836043662020707595556264018525880784406918290641249515082189298559149176184502808489120072844992687392807287776735971418347270261896375014971824691165077613379859095700097330459748808428401797429100642458691817195118746121515172654632282216869987549182422433637259085141865462043576798423387184774447920739934236584823824281198163815010674810451660377306056201619676256133844143603833904414952634432190114657544454178424020924616515723350778707749817125772467962926386356373289912154831438167899885040445364023527381951378636564391212010397122822120720357")

    const val TAG = "vdf"
    private val DOMAIN = "bitchat-geohash-vdf-v1".toByteArray(Charsets.UTF_8)
    private val CANONICAL_PUBKEY = Regex("[0-9a-f]{64}")
    private val TWO = BigInteger.valueOf(2)

    /** Debug experiment: when true, this device computes tokens for its geohash identities. */
    @Volatile
    var computeEnabled: Boolean = false

    @Volatile
    var requiredIterations: Long = 4_000_000L

    data class Token(val iterations: Long, val y: BigInteger, val proof: BigInteger) {
        fun toTag(): List<String> = listOf(TAG, iterations.toString(), y.toString(16), proof.toString(16))
    }

    /** Tokens this device holds, by "pubkey|geohash", so outgoing events can carry them. */
    private val ownTokens = ConcurrentHashMap<String, Token>()

    fun rememberOwnToken(pubkeyHex: String, geohash: String, token: Token) {
        ownTokens[ownKey(pubkeyHex, geohash)] = token
    }

    fun ownTokenTag(pubkeyHex: String, geohash: String): List<String>? = ownTokens[ownKey(pubkeyHex, geohash)]?.toTag()

    fun forgetOwnTokens() = ownTokens.clear()

    /**
     * Runs [iterations] squarings and builds the proof (about twice the squarings in total).
     * Returns null when [isActive] turns false first.
     */
    fun compute(pubkeyHex: String, geohash: String, iterations: Long = requiredIterations, isActive: () -> Boolean = { true }): Token? {
        val x = challenge(pubkeyHex, geohash) ?: return null
        var y = x
        for (i in 0 until iterations) {
            if ((i and 0xFFF) == 0L && !isActive()) return null
            y = y.multiply(y).mod(MODULUS)
        }
        val l = hashToPrime(x, y)
        // proof = x^floor(2^T / l), by long division of 2^T by l one bit at a time.
        var proof = BigInteger.ONE
        var r = BigInteger.ONE
        for (i in 0 until iterations) {
            if ((i and 0xFFF) == 0L && !isActive()) return null
            r = r.shiftLeft(1)
            proof = proof.multiply(proof).mod(MODULUS)
            if (r >= l) {
                r = r.subtract(l)
                proof = proof.multiply(x).mod(MODULUS)
            }
        }
        return Token(iterations, y, proof)
    }

    fun verify(pubkeyHex: String, geohash: String, token: Token, minIterations: Long = requiredIterations): Boolean {
        if (token.iterations < minIterations) return false
        if (token.y.signum() <= 0 || token.y >= MODULUS) return false
        if (token.proof.signum() <= 0 || token.proof >= MODULUS) return false
        val x = challenge(pubkeyHex, geohash) ?: return false
        val l = hashToPrime(x, token.y)
        val r = TWO.modPow(BigInteger.valueOf(token.iterations), l)
        val check = token.proof.modPow(l, MODULUS).multiply(x.modPow(r, MODULUS)).mod(MODULUS)
        return check == token.y
    }

    /** Reads a token from an event's tags; null when absent or malformed. */
    fun parseTag(tags: List<List<String>>): Token? {
        val tag = tags.firstOrNull { it.size >= 4 && it[0] == TAG } ?: return null
        return try {
            val iterations = tag[1].toLong()
            if (iterations <= 0 || tag[2].length > 512 || tag[3].length > 512) return null
            Token(iterations, BigInteger(tag[2], 16), BigInteger(tag[3], 16))
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** The squaring input: SHA-256 in counter mode over the domain, pubkey and geohash, reduced mod N. */
    private fun challenge(pubkeyHex: String, geohash: String): BigInteger? {
        if (!CANONICAL_PUBKEY.matches(pubkeyHex)) return null
        val md = MessageDigest.getInstance("SHA-256")
        val out = ByteArray(32 * 9)
        for (block in 0 until 9) {
            md.reset()
            md.update(DOMAIN)
            md.update(block.toByte())
            md.update(pubkeyHex.toByteArray(Charsets.UTF_8))
            md.update(geohash.lowercase().toByteArray(Charsets.UTF_8))
            md.digest().copyInto(out, block * 32)
        }
        val x = BigInteger(1, out).mod(MODULUS)
        return if (x.signum() == 0 || x == BigInteger.ONE) null else x
    }

    private fun hashToPrime(x: BigInteger, y: BigInteger): BigInteger {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(DOMAIN)
        md.update("prime".toByteArray(Charsets.UTF_8))
        md.update(x.toByteArray())
        md.update(y.toByteArray())
        val candidate = BigInteger(1, md.digest().copyOf(16)).setBit(127)
        return candidate.nextProbablePrime()
    }

    private fun ownKey(pubkeyHex: String, geohash: String) = pubkeyHex.lowercase() + "|" + geohash.lowercase()

    private val sharedVerifier = Verifier()

    /** True when [tags] carry a valid token for [pubkeyHex] in [geohash]; see [Verifier]. */
    fun isProven(tags: List<List<String>>, pubkeyHex: String, geohash: String): Boolean =
        sharedVerifier.isProven(tags, pubkeyHex, geohash)

    /**
     * Remembers senders already proven, and checks at most [perSecond] new tokens a second
     * (burst [burst]). A flood of fake tokens then costs a receiver a bounded amount of CPU; a
     * real token that misses the budget is checked again on the sender's next message.
     */
    class Verifier(
        private val burst: Double = 20.0,
        private val perSecond: Double = 10.0,
        private val clockMs: () -> Long = System::currentTimeMillis,
        private val minIterations: () -> Long = { requiredIterations }
    ) {
        private val proven = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 4_096
        }
        private var tokens = burst
        private var lastMs: Long? = null

        fun isProven(tags: List<List<String>>, pubkeyHex: String, geohash: String): Boolean {
            val key = ownKey(pubkeyHex, geohash)
            synchronized(this) {
                if (proven[key] == true) return true
            }
            val token = parseTag(tags) ?: return false
            if (token.iterations < minIterations()) return false
            synchronized(this) {
                val now = clockMs()
                lastMs?.let { tokens = minOf(burst, tokens + (now - it).coerceAtLeast(0) / 1000.0 * perSecond) }
                lastMs = now
                if (tokens < 1.0) return false
                tokens -= 1.0
            }
            val ok = verify(pubkeyHex, geohash, token, minIterations())
            if (ok) synchronized(this) { proven[key] = true }
            return ok
        }
    }
}
