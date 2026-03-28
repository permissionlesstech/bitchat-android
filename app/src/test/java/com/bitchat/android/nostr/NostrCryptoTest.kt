package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

class NostrCryptoTest {

    // =========================================================================
    // Well-known secp256k1 test vectors.
    //
    // Private key = 1  →  public key = generator point G (x-coordinate).
    // Private key = 2  →  public key = 2·G (x-coordinate).
    //
    // These values are deterministic and universally agreed-upon for secp256k1,
    // making them ideal for asserting that scalar multiplication and x-only
    // public key extraction are implemented correctly.
    // =========================================================================

    private val PRIV_KEY_ONE = "0000000000000000000000000000000000000000000000000000000000000001"
    private val PUB_KEY_ONE = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

    private val PRIV_KEY_TWO = "0000000000000000000000000000000000000000000000000000000000000002"
    private val PUB_KEY_TWO = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"

    /** A small private key (0xFF) that requires 31 bytes of left-zero-padding. */
    private val PRIV_KEY_SMALL = "00000000000000000000000000000000000000000000000000000000000000ff"

    /** secp256k1 curve order n. Any valid private key must be strictly less than this. */
    private val SECP256K1_ORDER = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"

    // =========================================================================
    // region Section 1 — Key Generation & Derivation
    // =========================================================================

    /**
     * generateKeyPair returns two valid 32-byte hex strings.
     *
     * The most basic contract of key generation: the private key must be
     * a valid secp256k1 scalar (0 < d < n) and the public key must be a
     * valid x-only coordinate that can be recovered to a curve point.
     *
     * Also exercises the BigInteger-to-32-byte padding logic which handles
     * the case where BigInteger.toByteArray() returns fewer than 32 bytes
     * (small scalar) or 33 bytes (leading sign byte).
     */
    @Test
    fun `generateKeyPair returns valid 32-byte hex strings`() {
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()

        assertEquals("private key must be 64 hex chars", 64, privateKey.length)
        assertEquals("public key must be 64 hex chars", 64, publicKey.length)
        assertTrue("private key must be valid secp256k1 scalar",
            NostrCrypto.isValidPrivateKey(privateKey))
        assertTrue("public key must be valid x-only coordinate",
            NostrCrypto.isValidPublicKey(publicKey))
    }

    /**
     * generateKeyPair produces unique keypairs on every call.
     *
     * Verifies that the SecureRandom-backed key generation does not repeat.
     * If the PRNG were seeded identically or the generation logic collapsed
     * to a constant, this test would catch it.
     *
     * Ten iterations strike a balance between confidence and test speed.
     */
    @Test
    fun `generateKeyPair produces unique keypairs`() {
        val keys = (1..10).map { NostrCrypto.generateKeyPair() }
        val uniquePrivateKeys = keys.map { it.first }.toSet()

        assertEquals("all 10 private keys must be distinct", 10, uniquePrivateKeys.size)
    }

    /**
     * generateKeyPair public key matches derivePublicKey for the same private key.
     *
     * Cross-method consistency check. generateKeyPair uses BouncyCastle's
     * ECKeyPairGenerator while derivePublicKey uses manual scalar multiplication.
     * Both must agree on the x-only public key for the same private scalar.
     *
     * A mismatch here would mean one of the two code paths has a padding bug,
     * normalization issue, or is extracting the wrong coordinate.
     */
    @Test
    fun `generateKeyPair public key matches derivePublicKey`() {
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        val derived = NostrCrypto.derivePublicKey(privateKey)

        assertEquals("generated pubkey must equal derived pubkey", publicKey, derived)
    }

    /**
     * derivePublicKey with private key = 1 returns the generator point G.
     *
     * The simplest possible known-answer test for scalar multiplication:
     * 1 · G = G. The x-coordinate of the secp256k1 generator is a
     * universally known constant. Any error in the multiply→normalize→
     * xCoord extraction chain will produce the wrong value.
     */
    @Test
    fun `derivePublicKey with privkey 1 returns generator x-coord`() {
        val pubkey = NostrCrypto.derivePublicKey(PRIV_KEY_ONE)

        assertEquals("1·G must equal generator x-coordinate", PUB_KEY_ONE, pubkey)
    }

    /**
     * derivePublicKey with private key = 2 returns the known 2·G x-coordinate.
     *
     * A second independent known-answer vector. If privkey=1 happened to pass
     * due to a lucky coincidence (e.g. identity function returning the input),
     * privkey=2 would catch it since 2·G has a completely different x-coordinate.
     */
    @Test
    fun `derivePublicKey with privkey 2 returns 2G x-coord`() {
        val pubkey = NostrCrypto.derivePublicKey(PRIV_KEY_TWO)

        assertEquals("2·G must equal known x-coordinate", PUB_KEY_TWO, pubkey)
    }

    /**
     * derivePublicKey with a small private key preserves left-zero-padding.
     *
     * Private key 0x00...FF (decimal 255) is only 1 byte of significant data.
     * BigInteger(1, bytes) correctly handles this, but the output x-coordinate
     * must still be returned as a full 64-char hex string with leading zeros
     * if the x-coordinate happens to be small.
     *
     * This primarily validates that derivePublicKey returns a consistently
     * formatted 64-character hex string regardless of the scalar magnitude.
     */
    @Test
    fun `derivePublicKey with small privkey returns valid 64-char hex`() {
        val pubkey = NostrCrypto.derivePublicKey(PRIV_KEY_SMALL)

        assertEquals("public key must be 64 hex chars", 64, pubkey.length)
        assertTrue("derived pubkey must be valid", NostrCrypto.isValidPublicKey(pubkey))
    }

    // endregion

    // =========================================================================
    // region Section 2 — ECDH Key Agreement
    // =========================================================================

    /**
     * performECDH produces a 32-byte shared secret.
     *
     * The output of ECDH must always be exactly 32 bytes regardless of the
     * internal BigInteger representation. This validates the padding/truncation
     * logic that normalizes the raw agreement output.
     */
    @Test
    fun `performECDH produces 32-byte shared secret`() {
        val secret = NostrCrypto.performECDH(PRIV_KEY_ONE, PUB_KEY_TWO)

        assertEquals("shared secret must be 32 bytes", 32, secret.size)
    }

    /**
     * ECDH is symmetric: ECDH(a, B) must equal ECDH(b, A).
     *
     * This is the fundamental property that makes Diffie-Hellman work.
     * If Alice computes a·B and Bob computes b·A, both must arrive at the
     * same shared point (and therefore the same 32-byte secret).
     *
     * A failure here would indicate a problem with point recovery from
     * x-only coordinates, parity handling, or the agreement calculation.
     */
    @Test
    fun `performECDH is symmetric`() {
        val secretAB = NostrCrypto.performECDH(PRIV_KEY_ONE, PUB_KEY_TWO)
        val secretBA = NostrCrypto.performECDH(PRIV_KEY_TWO, PUB_KEY_ONE)

        assertTrue("ECDH(a,B) must equal ECDH(b,A)", secretAB.contentEquals(secretBA))
    }

    /**
     * ECDH with known keys is deterministic.
     *
     * The same private/public key pair must always produce the same shared
     * secret. Non-determinism would indicate stale state or a random component
     * leaking into the agreement (there should be none in ECDH itself).
     */
    @Test
    fun `performECDH with known keys is deterministic`() {
        val secret1 = NostrCrypto.performECDH(PRIV_KEY_ONE, PUB_KEY_TWO)
        val secret2 = NostrCrypto.performECDH(PRIV_KEY_ONE, PUB_KEY_TWO)

        assertTrue("same inputs must produce same secret", secret1.contentEquals(secret2))
    }

    /**
     * ECDH symmetry with dynamically generated keypairs.
     *
     * Repeats the symmetry test with fresh random keys to exercise the
     * BigInteger padding paths statistically. Approximately 50% of ECDH
     * outputs will have a leading high bit set, triggering the >32-byte
     * branch in at least some iterations.
     */
    @Test
    fun `performECDH is symmetric with generated keypairs`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()
        val (privB, pubB) = NostrCrypto.generateKeyPair()

        val secretAB = NostrCrypto.performECDH(privA, pubB)
        val secretBA = NostrCrypto.performECDH(privB, pubA)

        assertEquals("shared secret must be 32 bytes", 32, secretAB.size)
        assertTrue("ECDH(a,B) must equal ECDH(b,A) for random keys",
            secretAB.contentEquals(secretBA))
    }

    /**
     * ECDH with different counterparties produces different secrets.
     *
     * Sanity check that the public key actually influences the result.
     * If ECDH ignored the public key and returned a constant or self-derived
     * value, this test would catch it.
     */
    @Test
    fun `performECDH with different keys gives different secrets`() {
        val (_, pubC) = NostrCrypto.generateKeyPair()
        val secretAB = NostrCrypto.performECDH(PRIV_KEY_ONE, PUB_KEY_TWO)
        val secretAC = NostrCrypto.performECDH(PRIV_KEY_ONE, pubC)

        assertFalse("ECDH with different pubkeys must differ",
            secretAB.contentEquals(secretAC))
    }

    /**
     * ECDH output is exactly 32 bytes regardless of BigInteger representation.
     *
     * BigInteger.toByteArray() returns a variable-length array: 33 bytes
     * when the leading bit is set (sign byte prepended), or fewer than 32
     * when leading bytes are zero. The normalization logic must handle both.
     * Over 50 random keypairs, both cases are exercised with high probability
     * (~50% chance per iteration), complementing the single-pair test above.
     */
    @Test
    fun `performECDH normalizes to 32 bytes across many keypairs`() {
        repeat(50) { i ->
            val (privA, _) = NostrCrypto.generateKeyPair()
            val (_, pubB) = NostrCrypto.generateKeyPair()

            val secret = NostrCrypto.performECDH(privA, pubB)

            assertEquals("shared secret must be 32 bytes on iteration $i", 32, secret.size)
            assertFalse("shared secret must not be all zeros on iteration $i",
                secret.all { it == 0.toByte() })
        }
    }

    /**
     * performECDH rejects a wrong-length public key.
     *
     * When a non-32-byte public key hex is passed, the internal
     * recoverPublicKeyPoint call enforces x-only keys must be exactly
     * 32 bytes. This must surface as an exception rather than silently
     * producing a garbage shared secret.
     */
    @Test
    fun `performECDH rejects wrong-length public key`() {
        try {
            NostrCrypto.performECDH(PRIV_KEY_ONE, "ab".repeat(31))
            fail("31-byte public key should be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            NostrCrypto.performECDH(PRIV_KEY_ONE, "ab".repeat(33))
            fail("33-byte public key should be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    // endregion

    // =========================================================================
    // region Section 3 — NIP-44 Key Derivation (HKDF)
    // =========================================================================

    /**
     * deriveNIP44Key produces a 32-byte key.
     *
     * Validates the HKDF-SHA256 extract+expand pipeline returns exactly
     * 32 bytes. The expand phase uses a single counter byte (0x01) and
     * truncates to the requested length.
     */
    @Test
    fun `deriveNIP44Key produces 32-byte key`() {
        val sharedSecret = ByteArray(32) { it.toByte() }
        val key = NostrCrypto.deriveNIP44Key(sharedSecret)

        assertEquals("NIP-44 key must be 32 bytes", 32, key.size)
    }

    /**
     * deriveNIP44Key is deterministic.
     *
     * Same shared secret must always produce the same derived key.
     * HKDF is a deterministic function with no random component.
     */
    @Test
    fun `deriveNIP44Key is deterministic`() {
        val sharedSecret = ByteArray(32) { (it * 7).toByte() }
        val key1 = NostrCrypto.deriveNIP44Key(sharedSecret)
        val key2 = NostrCrypto.deriveNIP44Key(sharedSecret)

        assertTrue("same input must produce same key", key1.contentEquals(key2))
    }

    /**
     * deriveNIP44Key with different inputs produces different keys.
     *
     * Two different shared secrets must derive different encryption keys.
     * If the HKDF implementation ignored its input (e.g. returned a constant),
     * this test would catch it.
     */
    @Test
    fun `deriveNIP44Key with different inputs produces different keys`() {
        val secret1 = ByteArray(32) { 0x01.toByte() }
        val secret2 = ByteArray(32) { 0x02.toByte() }

        val key1 = NostrCrypto.deriveNIP44Key(secret1)
        val key2 = NostrCrypto.deriveNIP44Key(secret2)

        assertFalse("different inputs must produce different keys",
            key1.contentEquals(key2))
    }

    // endregion

    // =========================================================================
    // region Section 4 — NIP-44 Encryption / Decryption
    // =========================================================================

    /**
     * encryptNIP44 produces a "v2:"-prefixed output.
     *
     * The NIP-44 v2 wire format requires the ciphertext to begin with "v2:"
     * followed by base64url-encoded data. This prefix is checked during
     * decryption and by other Nostr clients for version dispatch.
     */
    @Test
    fun `encryptNIP44 produces v2-prefixed output`() {
        val (privA, _) = NostrCrypto.generateKeyPair()
        val (_, pubB) = NostrCrypto.generateKeyPair()

        val ciphertext = NostrCrypto.encryptNIP44("hello", pubB, privA)

        assertTrue("ciphertext must start with v2:", ciphertext.startsWith("v2:"))
    }

    /**
     * encryptNIP44 payload is valid base64url (no padding, URL-safe alphabet).
     *
     * After stripping the "v2:" prefix, the remaining string must use only
     * the base64url alphabet: [A-Za-z0-9_-]. Standard base64 characters
     * (+, /, =) must not appear. This validates the base64UrlNoPad helper
     * which replaces + with -, / with _, and strips padding.
     */
    @Test
    fun `encryptNIP44 output is valid base64url after prefix`() {
        val (privA, _) = NostrCrypto.generateKeyPair()
        val (_, pubB) = NostrCrypto.generateKeyPair()

        val ciphertext = NostrCrypto.encryptNIP44("test message", pubB, privA)
        val payload = ciphertext.removePrefix("v2:")

        assertTrue("payload must not be empty", payload.isNotEmpty())
        assertTrue("payload must be valid base64url",
            payload.matches(Regex("[A-Za-z0-9_-]+")))
    }

    /**
     * NIP-44 encrypt-then-decrypt round-trip recovers the original plaintext.
     *
     * The core correctness test for the NIP-44 v2 encryption pipeline.
     * Alice encrypts with (privA, pubB) and Bob decrypts with (pubA, privB).
     *
     * This exercises the full chain: ECDH shared point computation with
     * even-Y parity, compressed point derivation, HKDF key derivation,
     * XChaCha20-Poly1305 AEAD encryption, base64url encoding, and the
     * reverse path through decryption including the dual-parity retry loop.
     */
    @Test
    fun `NIP44 encrypt then decrypt round-trips plaintext`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()
        val (privB, pubB) = NostrCrypto.generateKeyPair()

        val plaintext = "Hello, Nostr world!"
        val ciphertext = NostrCrypto.encryptNIP44(plaintext, pubB, privA)
        val decrypted = NostrCrypto.decryptNIP44(ciphertext, pubA, privB)

        assertEquals("decrypted text must match original", plaintext, decrypted)
    }

    /**
     * NIP-44 round-trip with Unicode plaintext (emoji, CJK, mixed scripts).
     *
     * Verifies that the UTF-8 encoding (toByteArray) and decoding
     * (String(pt, Charsets.UTF_8)) correctly handle
     * multi-byte characters. A naive ASCII assumption or incorrect charset
     * would corrupt these characters.
     */
    @Test
    fun `NIP44 round-trip with unicode plaintext`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()
        val (privB, pubB) = NostrCrypto.generateKeyPair()

        val plaintext = "\u2764\uFE0F Nostr \u4F60\u597D \uD83D\uDE80 \u00E9\u00E8\u00EA"
        val ciphertext = NostrCrypto.encryptNIP44(plaintext, pubB, privA)
        val decrypted = NostrCrypto.decryptNIP44(ciphertext, pubA, privB)

        assertEquals("unicode plaintext must survive round-trip", plaintext, decrypted)
    }

    /**
     * NIP-44 round-trip with empty string.
     *
     * Edge case for XChaCha20-Poly1305 AEAD with zero-length plaintext.
     * The ciphertext will consist of only the 24-byte nonce and 16-byte
     * Poly1305 tag (no encrypted body). The decrypt path must handle this
     * gracefully and return an empty string, not throw.
     */
    @Test
    fun `NIP44 round-trip with empty string`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()
        val (privB, pubB) = NostrCrypto.generateKeyPair()

        val ciphertext = NostrCrypto.encryptNIP44("", pubB, privA)
        val decrypted = NostrCrypto.decryptNIP44(ciphertext, pubA, privB)

        assertEquals("empty plaintext must survive round-trip", "", decrypted)
    }

    /**
     * NIP-44 round-trip with large plaintext (10 KB).
     *
     * Exercises the encryption pipeline with a payload significantly larger
     * than typical chat messages. XChaCha20 has no practical size limit,
     * but this catches any buffer allocation bugs, off-by-one errors in
     * the base64 encoding/decoding, or truncation issues.
     */
    @Test
    fun `NIP44 round-trip with large plaintext`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()
        val (privB, pubB) = NostrCrypto.generateKeyPair()

        val plaintext = "A".repeat(10_000)
        val ciphertext = NostrCrypto.encryptNIP44(plaintext, pubB, privA)
        val decrypted = NostrCrypto.decryptNIP44(ciphertext, pubA, privB)

        assertEquals("large plaintext must survive round-trip", plaintext, decrypted)
    }

    /**
     * NIP-44 round-trip where the base64url payload length is divisible by 4.
     *
     * A 2-char plaintext produces 42 encrypted bytes → 56 base64 chars.
     * (4 - 56 % 4) % 4 == 0, so the padding branch in base64UrlDecode is skipped.
     */
    @Test
    fun `NIP44 round-trip with base64url payload needing no padding`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()
        val (privB, pubB) = NostrCrypto.generateKeyPair()

        val plaintext = "ab"
        val ciphertext = NostrCrypto.encryptNIP44(plaintext, pubB, privA)
        val decrypted = NostrCrypto.decryptNIP44(ciphertext, pubA, privB)

        assertEquals("plaintext must survive round-trip with pad=0 base64", plaintext, decrypted)
    }

    /**
     * NIP-44 encryption is non-deterministic (unique per call).
     *
     * XChaCha20-Poly1305 uses a random 24-byte nonce generated by Tink
     * internally. Two encryptions of the same plaintext with the same keys
     * must produce different ciphertexts. If they were identical, nonce
     * reuse would catastrophically compromise the encryption.
     */
    @Test
    fun `NIP44 encryption produces different ciphertext each time`() {
        val (privA, _) = NostrCrypto.generateKeyPair()
        val (_, pubB) = NostrCrypto.generateKeyPair()

        val ct1 = NostrCrypto.encryptNIP44("same message", pubB, privA)
        val ct2 = NostrCrypto.encryptNIP44("same message", pubB, privA)

        assertNotEquals("two encryptions of same plaintext must differ", ct1, ct2)
    }

    /**
     * decryptNIP44 rejects ciphertext without the "v2:" version prefix.
     *
     * The require check enforces that only NIP-44 v2 formatted
     * ciphertexts are accepted. Passing a bare base64 string or a different
     * version prefix must throw a RuntimeException wrapping the require failure.
     */
    @Test
    fun `decryptNIP44 rejects missing v2 prefix`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()

        try {
            NostrCrypto.decryptNIP44("notV2prefixed", pubA, privA)
            fail("should have thrown RuntimeException for missing v2: prefix")
        } catch (e: RuntimeException) {
            assertTrue("error message should mention version prefix",
                e.message?.contains("v2") == true || e.message?.contains("prefix") == true)
        }
    }

    /**
     * decryptNIP44 rejects invalid base64url payload.
     *
     * When the payload after "v2:" is not valid base64url, the base64UrlDecode
     * helper returns null, triggering an IllegalArgumentException. This is
     * wrapped in the outer RuntimeException.
     */
    @Test
    fun `decryptNIP44 rejects invalid base64url payload`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()

        try {
            NostrCrypto.decryptNIP44("v2:!!!not-valid-base64!!!", pubA, privA)
            fail("should have thrown RuntimeException for invalid payload")
        } catch (e: RuntimeException) {
            // Expected — either base64 decode failure or AEAD decrypt failure
        }
    }

    /**
     * decryptNIP44 with the wrong recipient key fails.
     *
     * Alice encrypts for Bob, but Charlie (a third party) tries to decrypt.
     * Charlie's private key will compute a different ECDH shared point,
     * deriving a different encryption key. The Poly1305 authentication tag
     * will not verify, and decryption must fail.
     *
     * This exercises both iterations of the parity retry loop:
     * neither even-Y nor odd-Y will produce a matching key, so both attempts
     * fail and the method throws the last error.
     */
    @Test
    fun `decryptNIP44 with wrong key fails`() {
        val (privA, pubA) = NostrCrypto.generateKeyPair()
        val (_, pubB) = NostrCrypto.generateKeyPair()
        val (privC, _) = NostrCrypto.generateKeyPair()

        val ciphertext = NostrCrypto.encryptNIP44("secret", pubB, privA)

        try {
            NostrCrypto.decryptNIP44(ciphertext, pubA, privC)
            fail("decryption with wrong key must throw")
        } catch (e: RuntimeException) {
            // Expected — AEAD tag mismatch after both parity attempts
        }
    }

    /**
     * encryptNIP44 wraps errors in RuntimeException for invalid keys.
     *
     * When the recipient public key is malformed (wrong length), the
     * internal point recovery enforces 32-byte x-only keys. The outer
     * catch wraps this in a RuntimeException with a descriptive message.
     */
    @Test
    fun `encryptNIP44 throws RuntimeException for invalid recipient key`() {
        val (privA, _) = NostrCrypto.generateKeyPair()

        try {
            NostrCrypto.encryptNIP44("hello", "ab".repeat(31), privA)
            fail("invalid recipient key should throw RuntimeException")
        } catch (e: RuntimeException) {
            assertTrue("error should mention encryption failure",
                e.message?.contains("encryption failed") == true)
        }
    }

    // endregion

    // =========================================================================
    // region Section 5 — BIP-340 Schnorr Signatures
    // =========================================================================

    /**
     * schnorrSign produces a 128-character hex string (64 bytes: r || s).
     *
     * BIP-340 Schnorr signatures are exactly 64 bytes: the first 32 bytes
     * are the x-coordinate of the nonce point R, and the second 32 bytes
     * are the scalar s. Both components are padded to 32 bytes.
     */
    @Test
    fun `schnorrSign produces 128-char hex string`() {
        val (privKey, _) = NostrCrypto.generateKeyPair()
        val msgHash = sha256("test message".toByteArray())

        val signature = NostrCrypto.schnorrSign(msgHash, privKey)

        assertEquals("signature must be 128 hex chars (64 bytes)", 128, signature.length)
        assertTrue("signature must be valid hex",
            signature.matches(Regex("[0-9a-f]+")))
    }

    /**
     * schnorrSign then schnorrVerify returns true.
     *
     * The fundamental sign→verify round-trip. Exercises the full BIP-340
     * pipeline: even-Y adjustment of the private key, nonce generation,
     * challenge computation via tagged hash, signature computation
     * s = k + e·d mod n, and then the verification equation R = s·G - e·P
     * with the even-Y check on R and x-coordinate comparison.
     */
    @Test
    fun `schnorrSign then schnorrVerify returns true`() {
        val (privKey, pubKey) = NostrCrypto.generateKeyPair()
        val msgHash = sha256("verify this".toByteArray())

        val signature = NostrCrypto.schnorrSign(msgHash, privKey)
        val valid = NostrCrypto.schnorrVerify(msgHash, signature, pubKey)

        assertTrue("signature must verify against signer's public key", valid)
    }

    /**
     * Schnorr sign-verify round-trip with multiple keypairs.
     *
     * Runs the sign→verify cycle across several random keypairs to exercise
     * both branches of the even-Y private key adjustment.
     * Statistically, ~50% of keys will have an even-Y public point (no
     * adjustment) and ~50% will have odd Y (d is negated mod n).
     */
    @Test
    fun `schnorrSign and verify with multiple keypairs`() {
        repeat(5) { i ->
            val (privKey, pubKey) = NostrCrypto.generateKeyPair()
            val msgHash = sha256("message $i".toByteArray())

            val signature = NostrCrypto.schnorrSign(msgHash, privKey)
            val valid = NostrCrypto.schnorrVerify(msgHash, signature, pubKey)

            assertTrue("signature $i must verify", valid)
        }
    }

    /**
     * schnorrVerify rejects a modified message.
     *
     * A valid signature for message M1 must not verify against a different
     * message M2. The challenge hash e = H(r || P || m) changes when m
     * changes, so the verification equation R = s·G - e·P produces a
     * different R whose x-coordinate won't match r in the signature.
     */
    @Test
    fun `schnorrVerify rejects modified message`() {
        val (privKey, pubKey) = NostrCrypto.generateKeyPair()
        val msgHash1 = sha256("original".toByteArray())
        val msgHash2 = sha256("tampered".toByteArray())

        val signature = NostrCrypto.schnorrSign(msgHash1, privKey)
        val valid = NostrCrypto.schnorrVerify(msgHash2, signature, pubKey)

        assertFalse("signature must not verify for different message", valid)
    }

    /**
     * schnorrVerify rejects a modified signature.
     *
     * Flipping a single byte in the signature must cause verification to fail.
     * This tests both the Poly1305-like integrity of the scheme and the
     * specific byte-level sensitivity of the r and s components.
     */
    @Test
    fun `schnorrVerify rejects modified signature`() {
        val (privKey, pubKey) = NostrCrypto.generateKeyPair()
        val msgHash = sha256("integrity check".toByteArray())

        val signature = NostrCrypto.schnorrSign(msgHash, privKey)
        val sigBytes = hexToBytes(signature)
        sigBytes[0] = (sigBytes[0].toInt() xor 0xFF).toByte() // flip first byte
        val tamperedSig = sigBytes.joinToString("") { "%02x".format(it) }

        val valid = NostrCrypto.schnorrVerify(msgHash, tamperedSig, pubKey)

        assertFalse("tampered signature must not verify", valid)
    }

    /**
     * schnorrVerify rejects a wrong public key.
     *
     * A signature valid under key A must not verify under a different key B.
     * This ensures the public key is actually bound into the challenge hash
     * e = H(r || P || m) and affects the verification equation.
     */
    @Test
    fun `schnorrVerify rejects wrong public key`() {
        val (privA, _) = NostrCrypto.generateKeyPair()
        val (_, pubB) = NostrCrypto.generateKeyPair()
        val msgHash = sha256("key binding".toByteArray())

        val signature = NostrCrypto.schnorrSign(msgHash, privA)
        val valid = NostrCrypto.schnorrVerify(msgHash, signature, pubB)

        assertFalse("signature must not verify under different public key", valid)
    }

    /**
     * schnorrSign rejects a non-32-byte message hash.
     *
     * BIP-340 requires the message to be exactly 32 bytes (typically SHA-256).
     * The require check enforces this. Passing 31 or 33 bytes
     * must throw IllegalArgumentException.
     */
    @Test
    fun `schnorrSign rejects non-32-byte message hash`() {
        val (privKey, _) = NostrCrypto.generateKeyPair()

        try {
            NostrCrypto.schnorrSign(ByteArray(31), privKey)
            fail("31-byte hash should be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            NostrCrypto.schnorrSign(ByteArray(33), privKey)
            fail("33-byte hash should be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    /**
     * schnorrSign rejects an invalid private key.
     *
     * The require checks enforce that the private key is exactly 32 bytes
     * and within the valid range (0 < d < n). A wrong-length key or an
     * out-of-range scalar must throw IllegalArgumentException.
     */
    @Test
    fun `schnorrSign rejects invalid private key`() {
        val msgHash = sha256("test".toByteArray())

        // Wrong length (31 bytes = 62 hex chars)
        try {
            NostrCrypto.schnorrSign(msgHash, "ab".repeat(31))
            fail("31-byte private key should be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        // Zero key (valid length, but d == 0)
        try {
            NostrCrypto.schnorrSign(msgHash, "00".repeat(32))
            fail("zero private key should be rejected")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        // Key equal to curve order n (valid length, but d >= n)
        try {
            NostrCrypto.schnorrSign(msgHash, SECP256K1_ORDER)
            fail("curve order n should be rejected as private key")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    /**
     * schnorrVerify returns false (not exception) for malformed inputs.
     *
     * The outer try-catch ensures that any parsing or
     * arithmetic error during verification is caught and returns false
     * rather than propagating an exception to the caller. This is critical
     * for robustness — the app processes signatures from untrusted peers
     * and must never crash on malformed data.
     */
    @Test
    fun `schnorrVerify returns false for malformed inputs`() {
        val msgHash = sha256("test".toByteArray())
        val validSig = "ab".repeat(64)  // 128 hex chars
        val validPub = PUB_KEY_ONE

        // Wrong-size signature (63 bytes)
        assertFalse("63-byte signature must return false",
            NostrCrypto.schnorrVerify(msgHash, "ab".repeat(63), validPub))

        // Wrong-size public key (31 bytes)
        assertFalse("31-byte pubkey must return false",
            NostrCrypto.schnorrVerify(msgHash, validSig, "ab".repeat(31)))

        // Wrong-size message hash (31 bytes)
        assertFalse("31-byte message must return false",
            NostrCrypto.schnorrVerify(ByteArray(31), validSig, validPub))
    }

    /**
     * schnorrVerify rejects signatures with out-of-range r or s values.
     *
     * BIP-340 requires r < field prime (p) and s < curve order (n).
     * These crafted inputs exercise the early-return guards that reject
     * signatures before any EC point arithmetic is attempted. Also tests
     * the liftX null-return path for an invalid public key x-coordinate.
     */
    @Test
    fun `schnorrVerify rejects out-of-range r and s values`() {
        val msgHash = sha256("range check".toByteArray())

        // r >= field prime p (all 0xFF is larger than secp256k1 p)
        val rTooBig = "ff".repeat(32)
        val sValid = "00".repeat(32)
        assertFalse("r >= field prime must return false",
            NostrCrypto.schnorrVerify(msgHash, rTooBig + sValid, PUB_KEY_ONE))

        // s >= curve order n
        val rValid = "00".repeat(31) + "01"
        val sTooBig = "ff".repeat(32)
        assertFalse("s >= curve order must return false",
            NostrCrypto.schnorrVerify(msgHash, rValid + sTooBig, PUB_KEY_ONE))

        // Invalid public key x-coordinate (all 0xFF, not on curve)
        val validSig = "ab".repeat(64)
        assertFalse("invalid pubkey x-coord must return false",
            NostrCrypto.schnorrVerify(msgHash, validSig, "ff".repeat(32)))
    }

    /**
     * schnorrSign with known test vectors produces verifiable signatures.
     *
     * Uses the well-known privkey=1 (generator point) to sign a deterministic
     * message hash. While the signature itself is non-deterministic (due to
     * the random nonce), it must always pass verification with
     * the corresponding public key (generator x-coordinate).
     */
    @Test
    fun `schnorrSign with known privkey 1 produces verifiable signature`() {
        val msgHash = sha256("BIP-340 test".toByteArray())

        val signature = NostrCrypto.schnorrSign(msgHash, PRIV_KEY_ONE)
        val valid = NostrCrypto.schnorrVerify(msgHash, signature, PUB_KEY_ONE)

        assertTrue("signature with privkey=1 must verify against G_x", valid)
    }

    /**
     * Schnorr sign+verify with privkey=2 exercises the odd-Y branch in liftX.
     *
     * PUB_KEY_TWO (2G) has an odd Y coordinate. In schnorrVerify → liftX,
     * recoverPublicKeyPoint returns the even-Y lift, and the hasEvenY check
     * determines whether to negate. This exercises the branch opposite to
     * the privkey=1 test above.
     */
    @Test
    fun `schnorrSign with known privkey 2 produces verifiable signature`() {
        val msgHash = sha256("BIP-340 test".toByteArray())

        val signature = NostrCrypto.schnorrSign(msgHash, PRIV_KEY_TWO)
        val valid = NostrCrypto.schnorrVerify(msgHash, signature, PUB_KEY_TWO)

        assertTrue("signature with privkey=2 must verify against 2G_x", valid)
    }

    // endregion

    // =========================================================================
    // region Section 6 — Key Validation
    // =========================================================================

    /**
     * isValidPrivateKey accepts a known valid key.
     *
     * The simplest positive case: a key that is exactly 32 bytes and
     * whose numeric value satisfies 0 < d < n.
     */
    @Test
    fun `isValidPrivateKey accepts valid key`() {
        assertTrue("privkey=1 must be valid", NostrCrypto.isValidPrivateKey(PRIV_KEY_ONE))
        assertTrue("privkey=2 must be valid", NostrCrypto.isValidPrivateKey(PRIV_KEY_TWO))
    }

    /**
     * isValidPrivateKey accepts a freshly generated key.
     *
     * Verifies that generateKeyPair always produces a valid private key
     * according to isValidPrivateKey. If the generation and validation
     * logic disagree, this cross-check catches it.
     */
    @Test
    fun `isValidPrivateKey accepts generated key`() {
        val (privKey, _) = NostrCrypto.generateKeyPair()

        assertTrue("generated key must be valid", NostrCrypto.isValidPrivateKey(privKey))
    }

    /**
     * isValidPrivateKey rejects the zero scalar.
     *
     * Zero is not a valid secp256k1 private key because 0·G = point at
     * infinity (not a valid public key). The validation requires
     * d > BigInteger.ZERO.
     */
    @Test
    fun `isValidPrivateKey rejects zero`() {
        assertFalse("zero key must be invalid",
            NostrCrypto.isValidPrivateKey("00".repeat(32)))
    }

    /**
     * isValidPrivateKey rejects the curve order n and values above it.
     *
     * The secp256k1 private key must be strictly less than the curve order n.
     * A key equal to n produces 0·G = point at infinity (since n·G = O).
     * A key equal to n+1 is equivalent to privkey=1 but is out of the
     * canonical range. The validation requires d < n.
     */
    @Test
    fun `isValidPrivateKey rejects curve order and above`() {
        assertFalse("curve order n must be invalid",
            NostrCrypto.isValidPrivateKey(SECP256K1_ORDER))

        // n + 1
        val nPlusOne = BigInteger(SECP256K1_ORDER, 16).add(BigInteger.ONE)
        val nPlusOneHex = nPlusOne.toString(16).padStart(64, '0')
        assertFalse("n+1 must be invalid",
            NostrCrypto.isValidPrivateKey(nPlusOneHex))
    }

    /**
     * isValidPrivateKey rejects wrong-length hex strings.
     *
     * The size check (privateKeyBytes.size != 32) catches keys that are
     * too short or too long before any arithmetic validation.
     */
    @Test
    fun `isValidPrivateKey rejects wrong-length key`() {
        assertFalse("30-byte key must be invalid",
            NostrCrypto.isValidPrivateKey("ab".repeat(30)))
        assertFalse("34-byte key must be invalid",
            NostrCrypto.isValidPrivateKey("ab".repeat(34)))
    }

    /**
     * isValidPrivateKey rejects non-hex and odd-length strings.
     *
     * The hexToByteArray extension throws for invalid hex, which is caught
     * by the try-catch. The method must return false
     * rather than propagating the exception.
     */
    @Test
    fun `isValidPrivateKey rejects malformed hex`() {
        assertFalse("odd-length hex must be invalid",
            NostrCrypto.isValidPrivateKey("abc"))
        assertFalse("non-hex chars must be invalid",
            NostrCrypto.isValidPrivateKey("zz".repeat(32)))
    }

    /**
     * isValidPublicKey accepts a known valid x-only public key.
     *
     * Uses public keys derived from known private keys. These x-coordinates
     * are guaranteed to lie on the secp256k1 curve.
     */
    @Test
    fun `isValidPublicKey accepts valid pubkey`() {
        assertTrue("G_x must be valid", NostrCrypto.isValidPublicKey(PUB_KEY_ONE))
        assertTrue("2G_x must be valid", NostrCrypto.isValidPublicKey(PUB_KEY_TWO))
    }

    /**
     * isValidPublicKey rejects invalid inputs.
     *
     * Tests three categories of invalid public keys:
     * - Wrong length (31 or 33 bytes): caught by the size check.
     * - All zeros (x=0): not a valid x-coordinate on secp256k1, so
     *   recoverPublicKeyPoint throws, caught by the outer try-catch.
     * - Value >= field prime (all 0xFF): not a valid field element, so
     *   point decompression fails.
     */
    @Test
    fun `isValidPublicKey rejects invalid inputs`() {
        assertFalse("31-byte key must be invalid",
            NostrCrypto.isValidPublicKey("ab".repeat(31)))
        assertFalse("33-byte key must be invalid",
            NostrCrypto.isValidPublicKey("ab".repeat(33)))
        assertFalse("all-zeros must be invalid",
            NostrCrypto.isValidPublicKey("00".repeat(32)))
        assertFalse("all-0xFF must be invalid",
            NostrCrypto.isValidPublicKey("ff".repeat(32)))
    }

    // endregion

    // =========================================================================
    // region Section 7 — Timestamp Randomization
    // =========================================================================

    /**
     * randomizeTimestamp returns values within ±900 seconds of the base.
     *
     * The implementation generates a random offset in [-900, 899]
     * and adds it to the base timestamp. Over 200 iterations, all results
     * must fall within this bound. The statistical chance of hitting only
     * one extreme in 200 tries is negligible, so this also implicitly checks
     * that the range is roughly uniform and not collapsed to a constant.
     */
    @Test
    fun `randomizeTimestamp within bounds of base`() {
        val base = 1_700_000_000L

        repeat(200) {
            val result = NostrCrypto.randomizeTimestamp(base)
            assertTrue("result must be >= base - 900, was ${result - base.toInt()}",
                result >= (base - 900).toInt())
            assertTrue("result must be <= base + 900, was ${result - base.toInt()}",
                result <= (base + 900).toInt())
        }
    }

    /**
     * randomizeTimestamp with no argument uses a value near current time.
     *
     * The default parameter is System.currentTimeMillis() / 1000. The result
     * must be within ~1000 seconds of the current time (900 for the random
     * offset plus a small margin for test execution time).
     */
    @Test
    fun `randomizeTimestamp default uses current time`() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val result = NostrCrypto.randomizeTimestamp()

        val diff = kotlin.math.abs(result - now)
        assertTrue("result must be within 1000s of now, diff was $diff", diff <= 1000)
    }

    /**
     * randomizeTimestampUpToPast returns values between (now - max) and now.
     *
     * With maxPastSeconds=3600, every result must be in [now-3600, now].
     * This validates both the range calculation and that the
     * offset is always non-negative (subtracted from now).
     */
    @Test
    fun `randomizeTimestampUpToPast within range`() {
        val maxPast = 3600
        val before = (System.currentTimeMillis() / 1000).toInt()

        repeat(200) {
            val result = NostrCrypto.randomizeTimestampUpToPast(maxPast)
            val after = (System.currentTimeMillis() / 1000).toInt()

            assertTrue("result must be >= now - maxPast (with 2s margin)",
                result >= before - maxPast - 2)
            assertTrue("result must be <= now (with 2s margin)",
                result <= after + 2)
        }
    }

    /**
     * randomizeTimestampUpToPast with default argument (2 days) returns a
     * value within the last 172800 seconds.
     *
     * Exercises the default parameter path that callers like NostrProtocol
     * use when no explicit maxPastSeconds is provided.
     */
    @Test
    fun `randomizeTimestampUpToPast with default uses 2-day window`() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val result = NostrCrypto.randomizeTimestampUpToPast()

        assertTrue("result must be <= now (with 2s margin)", result <= now + 2)
        assertTrue("result must be >= now - 172800 (with 2s margin)",
            result >= now - 172800 - 2)
    }

    /**
     * randomizeTimestampUpToPast with maxPastSeconds=0 returns approximately now.
     *
     * When maxPastSeconds is 0, the else branch sets offset=0,
     * so the result should equal the current time. We allow ±2 seconds for
     * test execution overhead.
     */
    @Test
    fun `randomizeTimestampUpToPast with zero max returns now`() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val result = NostrCrypto.randomizeTimestampUpToPast(0)

        val diff = kotlin.math.abs(result - now)
        assertTrue("result must be within 2s of now, diff was $diff", diff <= 2)
    }

    // endregion

    // =========================================================================
    // region Helpers
    // =========================================================================

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // endregion
}
