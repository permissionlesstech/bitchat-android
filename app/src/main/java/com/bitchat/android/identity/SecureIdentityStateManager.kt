package com.bitchat.android.identity

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import android.util.Base64
import android.util.Log
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.model.VouchAttestation
import com.bitchat.android.util.hexEncodedString
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Manages persistent identity storage and peer ID rotation - 100% compatible with iOS implementation
 * 
 * Handles:
 * - Static identity key persistence across app sessions
 * - Secure storage using Android EncryptedSharedPreferences
 * - Fingerprint calculation and identity validation
 */
class SecureIdentityStateManager {
    
    companion object {
        private const val TAG = "SecureIdentityStateManager"
        private const val PREFS_NAME = "bitchat_identity"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"
        private const val KEY_VERIFIED_FINGERPRINTS = "verified_fingerprints"
        private const val KEY_CACHED_PEER_FINGERPRINTS = "cached_peer_fingerprints"
        private const val KEY_CACHED_PEER_NOISE_KEYS = "cached_peer_noise_keys"
        private const val KEY_CACHED_NOISE_FINGERPRINTS = "cached_noise_fingerprints"
        private const val KEY_CACHED_FINGERPRINT_NICKNAMES = "cached_fingerprint_nicknames"
        private const val KEY_PRIVATE_MEDIA_CAPABILITY_PINS = "private_media_capability_pins_v1"
        private const val KEY_AUTHENTICATED_PEER_STATES = "authenticated_peer_states_v1"
        private const val KEY_VOUCH_RECORDS = "vouch_records_v1"
        private const val KEY_VOUCH_BATCH_SENT_AT = "vouch_batch_sent_at_v1"
        private const val KEY_VERIFIED_AT = "verified_at_v1"
        const val MAX_VOUCHERS_PER_VOUCHEE = 8
        private const val VOUCH_RECORD_FIELD_COUNT = 4
        private const val RECORD_SEPARATOR = ':'
        private const val RECORD_SEPARATOR_LENGTH = 1
        private const val VOUCHEE_FIELD_INDEX = 0
        private const val VOUCHER_FIELD_INDEX = 1
        private const val VOUCHEE_SIGNING_KEY_FIELD_INDEX = 2
        private const val INDEX_NOT_FOUND = -1
        private const val IDENTITY_EVENT_BUFFER_CAPACITY = 1
        private const val EXPIRY_TRANSITION_OFFSET_MS = 1L

        // BLE, Wi-Fi Aware, and Noise services each hold their own manager
        // instance over the same encrypted preferences. Serialize pin updates
        // process-wide so concurrent promotions cannot lose one another or
        // race a panic wipe.
        private val identityPersistenceLock = Any()
        private var identityPersistenceEpoch = 0L
        private val identityChanges =
            MutableSharedFlow<Unit>(extraBufferCapacity = IDENTITY_EVENT_BUFFER_CAPACITY)
        val changes = identityChanges.asSharedFlow()
    }
    
    private val prefs: SharedPreferences
    private val lock = Any()
    private var identityPersistenceEpochAtCreation: Long

    constructor(context: Context) {
        identityPersistenceEpochAtCreation = synchronized(identityPersistenceLock) {
            identityPersistenceEpoch
        }
        // Create master key for encryption
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        // Create encrypted shared preferences
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Test-only storage injection; production always uses encrypted prefs. */
    internal constructor(prefs: SharedPreferences, testOnly: Boolean) {
        require(testOnly) { "Plain SharedPreferences are test-only" }
        identityPersistenceEpochAtCreation = synchronized(identityPersistenceLock) {
            identityPersistenceEpoch
        }
        this.prefs = prefs
    }
    
    // MARK: - Static Key Management
    
    /**
     * Load saved static key pair
     * Returns (privateKey, publicKey) or null if none exists
     */
    fun loadStaticKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = prefs.getString(KEY_STATIC_PRIVATE_KEY, null)
            val publicKeyString = prefs.getString(KEY_STATIC_PUBLIC_KEY, null)
            
            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = android.util.Base64.decode(privateKeyString, android.util.Base64.DEFAULT)
                val publicKey = android.util.Base64.decode(publicKeyString, android.util.Base64.DEFAULT)
                
                // Validate key sizes
                if (privateKey.size == 32 && publicKey.size == 32) {
                    Log.d(TAG, "Loaded static identity key from secure storage")
                    Pair(privateKey, publicKey)
                } else {
                    Log.w(TAG, "Invalid key sizes in storage, returning null")
                    null
                }
            } else {
                Log.d(TAG, "No static identity key found in storage")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static key: ${e.message}")
            null
        }
    }
    
    /**
     * Save static key pair to secure storage
     */
    fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            // Validate key sizes
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }
            
            val privateKeyString = android.util.Base64.encodeToString(privateKey, android.util.Base64.DEFAULT)
            val publicKeyString = android.util.Base64.encodeToString(publicKey, android.util.Base64.DEFAULT)
            
            prefs.edit()
                .putString(KEY_STATIC_PRIVATE_KEY, privateKeyString)
                .putString(KEY_STATIC_PUBLIC_KEY, publicKeyString)
                .apply()
            
            Log.d(TAG, "Saved static identity key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save static key: ${e.message}")
            throw e
        }
    }

    // MARK: - Signing Key Management

    /**
     * Load saved signing key pair
     * Returns (privateKey, publicKey) or null if none exists
     */
    fun loadSigningKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = prefs.getString(KEY_SIGNING_PRIVATE_KEY, null)
            val publicKeyString = prefs.getString(KEY_SIGNING_PUBLIC_KEY, null)
            
            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = android.util.Base64.decode(privateKeyString, android.util.Base64.DEFAULT)
                val publicKey = android.util.Base64.decode(publicKeyString, android.util.Base64.DEFAULT)
                
                // Validate key sizes
                if (privateKey.size == 32 && publicKey.size == 32) {
                    Log.d(TAG, "Loaded Ed25519 signing key from secure storage")
                    Pair(privateKey, publicKey)
                } else {
                    Log.w(TAG, "Invalid signing key sizes in storage, returning null")
                    null
                }
            } else {
                Log.d(TAG, "No Ed25519 signing key found in storage")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load signing key: ${e.message}")
            null
        }
    }

    /**
     * Save signing key pair to secure storage
     */
    fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            // Validate key sizes
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid signing key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }
            
            val privateKeyString = android.util.Base64.encodeToString(privateKey, android.util.Base64.DEFAULT)
            val publicKeyString = android.util.Base64.encodeToString(publicKey, android.util.Base64.DEFAULT)
            
            prefs.edit()
                .putString(KEY_SIGNING_PRIVATE_KEY, privateKeyString)
                .putString(KEY_SIGNING_PUBLIC_KEY, publicKeyString)
                .apply()
            
            Log.d(TAG, "Saved Ed25519 signing key to secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save signing key: ${e.message}")
            throw e
        }
    }
    
    // MARK: - Fingerprint Generation
    
    /**
     * Generate fingerprint from public key (SHA-256 hash)
     */
    fun generateFingerprint(publicKeyData: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyData)
        return hash.hexEncodedString()
    }
    
    /**
     * Validate fingerprint format
     */
    fun isValidFingerprint(fingerprint: String): Boolean {
        // SHA-256 fingerprint should be 64 hex characters
        return fingerprint.matches(Regex("^[a-fA-F0-9]{64}$"))
    }

    // MARK: - Verified Fingerprints

    fun getVerifiedFingerprints(): Set<String> {
        return prefs.getStringSet(KEY_VERIFIED_FINGERPRINTS, emptySet())?.toSet() ?: emptySet()
    }

    fun isVerifiedFingerprint(fingerprint: String): Boolean {
        return getVerifiedFingerprints().contains(fingerprint)
    }

    @SuppressLint("UseKtx")
    fun setVerifiedFingerprint(fingerprint: String, verified: Boolean) {
        if (!isValidFingerprint(fingerprint)) return
        val normalizedFingerprint = fingerprint.lowercase()
        synchronized(identityPersistenceLock) {
            if (identityPersistenceEpochAtCreation != identityPersistenceEpoch) return
            val current = prefs.getStringSet(KEY_VERIFIED_FINGERPRINTS, emptySet())
                ?.mapTo(mutableSetOf()) { it.lowercase() } ?: mutableSetOf()
            if (verified) {
                current.add(normalizedFingerprint)
            } else {
                current.remove(normalizedFingerprint)
            }
            val verifiedAt = readTimestampMap(KEY_VERIFIED_AT).toMutableMap()
            if (verified) verifiedAt[normalizedFingerprint] = System.currentTimeMillis()
            else verifiedAt.remove(normalizedFingerprint)
            val committed = prefs.edit()
                .putStringSet(KEY_VERIFIED_FINGERPRINTS, current)
                .putStringSet(KEY_VERIFIED_AT, encodeTimestampMap(verifiedAt))
                .commit()
            if (!committed) return
            identityChanges.tryEmit(Unit)
        }
    }

    data class VouchRecord(
        val voucherFingerprint: String,
        val voucheeSigningKeyHex: String,
        val timestampMs: Long
    )

    @SuppressLint("UseKtx")
    fun recordVouch(
        voucheeFingerprint: String,
        voucherFingerprint: String,
        voucheeSigningKey: ByteArray,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val vouchee = voucheeFingerprint.lowercase()
        val voucher = voucherFingerprint.lowercase()
        if (!isValidFingerprint(vouchee) || !isValidFingerprint(voucher) ||
            voucheeSigningKey.size != VouchAttestation.SIGNING_KEY_SIZE
        ) return false
        synchronized(identityPersistenceLock) {
            if (identityPersistenceEpochAtCreation != identityPersistenceEpoch) return false
            val verified = getVerifiedFingerprints().mapTo(mutableSetOf()) { it.lowercase() }
            val age = nowMs - timestampMs
            if (vouchee == voucher || voucher !in verified || vouchee in verified ||
                age > VouchAttestation.MAX_AGE_MS ||
                age < -VouchAttestation.MAX_CLOCK_SKEW_MS
            ) return false

            val all = readVouchRecords().toMutableMap()
            val records = all[vouchee].orEmpty().toMutableList()
            val existing = records.indexOfFirst { it.voucherFingerprint == voucher }
            val proposed = VouchRecord(
                voucherFingerprint = voucher,
                voucheeSigningKeyHex = voucheeSigningKey.hexEncodedString(),
                timestampMs = timestampMs
            )
            val record = records.getOrNull(existing)
                ?.takeIf { it.timestampMs >= timestampMs }
                ?: proposed
            if (existing > INDEX_NOT_FOUND) records[existing] = record else records += record
            val capped = records.sortedByDescending { it.timestampMs }.take(MAX_VOUCHERS_PER_VOUCHEE)
            if (capped.none { it.voucherFingerprint == voucher }) return false
            all[vouchee] = capped
            val committed = prefs.edit()
                .putStringSet(KEY_VOUCH_RECORDS, encodeVouchRecords(all))
                .commit()
            if (!committed) return false
            identityChanges.tryEmit(Unit)
            return true
        }
    }

    fun validVouchers(
        fingerprint: String,
        nowMs: Long = System.currentTimeMillis()
    ): List<VouchRecord> {
        val normalized = fingerprint.lowercase()
        if (!isValidFingerprint(normalized)) return emptyList()
        synchronized(identityPersistenceLock) {
            if (identityPersistenceEpochAtCreation != identityPersistenceEpoch) return emptyList()
            val verified = getVerifiedFingerprints().mapTo(mutableSetOf()) { it.lowercase() }
            val authenticatedSigningKeyHex = getAuthenticatedSigningKey(normalized)
                ?.hexEncodedString() ?: return emptyList()
            return readVouchRecords()[normalized].orEmpty().filter {
                it.voucherFingerprint != normalized &&
                    it.voucherFingerprint in verified &&
                    it.voucheeSigningKeyHex == authenticatedSigningKeyHex &&
                    nowMs - it.timestampMs <= VouchAttestation.MAX_AGE_MS &&
                    nowMs - it.timestampMs >= -VouchAttestation.MAX_CLOCK_SKEW_MS
            }
        }
    }

    fun isVouched(fingerprint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val normalized = fingerprint.lowercase()
        val isExplicitlyVerified = getVerifiedFingerprints().any {
            it.equals(normalized, ignoreCase = true)
        }
        return !isExplicitlyVerified && validVouchers(normalized, nowMs).isNotEmpty()
    }

    fun getVouchedFingerprints(nowMs: Long = System.currentTimeMillis()): Set<String> =
        synchronized(identityPersistenceLock) {
            readVouchRecords().keys.filterTo(mutableSetOf()) { isVouched(it, nowMs) }
        }

    fun nextVouchExpiryMs(nowMs: Long = System.currentTimeMillis()): Long? =
        synchronized(identityPersistenceLock) {
            readVouchRecords().keys
                .flatMap { validVouchers(it, nowMs) }
                .minOfOrNull {
                    it.timestampMs +
                        VouchAttestation.MAX_AGE_MS +
                        EXPIRY_TRANSITION_OFFSET_MS
                }
        }

    fun mostRecentlyVerifiedFingerprints(limit: Int, excluding: String): List<String> {
        return synchronized(identityPersistenceLock) {
            val verifiedAt = readTimestampMap(KEY_VERIFIED_AT)
            getVerifiedFingerprints()
                .map { it.lowercase() }
                .filterNot { it == excluding.lowercase() }
                .sortedWith(compareByDescending<String> { verifiedAt[it] ?: Long.MIN_VALUE }.thenByDescending { it })
                .take(limit)
        }
    }

    fun lastVouchBatchSent(fingerprint: String): Long? =
        synchronized(identityPersistenceLock) {
            readTimestampMap(KEY_VOUCH_BATCH_SENT_AT)[fingerprint.lowercase()]
        }

    @SuppressLint("UseKtx")
    fun markVouchBatchSent(fingerprint: String, timestampMs: Long) {
        synchronized(identityPersistenceLock) {
            if (identityPersistenceEpochAtCreation != identityPersistenceEpoch) return
            val sent = readTimestampMap(KEY_VOUCH_BATCH_SENT_AT).toMutableMap()
            sent[fingerprint.lowercase()] = timestampMs
            if (!prefs.edit()
                    .putStringSet(KEY_VOUCH_BATCH_SENT_AT, encodeTimestampMap(sent))
                    .commit()
            ) {
                Log.e(TAG, "Vouch batch timestamp could not be committed")
            }
        }
    }

    private fun readTimestampMap(key: String): Map<String, Long> =
        prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { entry ->
            val split = entry.lastIndexOf(RECORD_SEPARATOR)
            if (split <= VOUCHEE_FIELD_INDEX) {
                null
            } else {
                entry.substring(split + RECORD_SEPARATOR_LENGTH).toLongOrNull()?.let {
                    entry.substring(VOUCHEE_FIELD_INDEX, split) to it
                }
            }
        }.toMap()

    private fun encodeTimestampMap(values: Map<String, Long>): Set<String> =
        values.mapTo(mutableSetOf()) { (fingerprint, timestamp) ->
            "$fingerprint$RECORD_SEPARATOR$timestamp"
        }

    private fun readVouchRecords(): Map<String, List<VouchRecord>> =
        prefs.getStringSet(KEY_VOUCH_RECORDS, emptySet()).orEmpty().mapNotNull { entry ->
            val fields = entry.split(RECORD_SEPARATOR)
            if (fields.size != VOUCH_RECORD_FIELD_COUNT) null else fields.last().toLongOrNull()?.let {
                fields[VOUCHEE_FIELD_INDEX] to VouchRecord(
                    voucherFingerprint = fields[VOUCHER_FIELD_INDEX],
                    voucheeSigningKeyHex = fields[VOUCHEE_SIGNING_KEY_FIELD_INDEX],
                    timestampMs = it
                )
            }
        }.groupBy({ it.first }, { it.second })

    private fun encodeVouchRecords(values: Map<String, List<VouchRecord>>): Set<String> =
        values.flatMapTo(mutableSetOf()) { (vouchee, records) ->
            records.map {
                "$vouchee$RECORD_SEPARATOR${it.voucherFingerprint}" +
                    "$RECORD_SEPARATOR${it.voucheeSigningKeyHex}" +
                    "$RECORD_SEPARATOR${it.timestampMs}"
            }
        }

    fun getCachedPeerFingerprint(peerID: String): String? {
        val pid = peerID.lowercase()
        // Reading is safe without lock for SharedPreferences, but synchronizing ensures memory visibility
        // if we are paranoid, but SharedPreferences is generally thread-safe for reads.
        // However, to ensure we don't read a partial update (unlikely with SP), we can leave it.
        // The critical part is the write.
        val entries = prefs.getStringSet(KEY_CACHED_PEER_FINGERPRINTS, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$pid:") } ?: return null
        return entry.substringAfter(':').takeIf { isValidFingerprint(it) }
    }

    fun cachePeerFingerprint(peerID: String, fingerprint: String) {
        if (!isValidFingerprint(fingerprint)) return
        val pid = peerID.lowercase()
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_PEER_FINGERPRINTS, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$pid:") }
            current.add("$pid:$fingerprint")
            prefs.edit { putStringSet(KEY_CACHED_PEER_FINGERPRINTS, current) }
        }
    }

    fun getCachedNoiseKey(peerID: String): String? {
        val pid = peerID.lowercase()
        val entries = prefs.getStringSet(KEY_CACHED_PEER_NOISE_KEYS, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$pid=") } ?: return null
        return entry.substringAfter('=').takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) }
    }

    fun cachePeerNoiseKey(peerID: String, noiseKeyHex: String) {
        if (!noiseKeyHex.matches(Regex("^[a-fA-F0-9]{64}$"))) return
        val pid = peerID.lowercase()
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_PEER_NOISE_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$pid=") }
            current.add("$pid=${noiseKeyHex.lowercase()}")
            prefs.edit { putStringSet(KEY_CACHED_PEER_NOISE_KEYS, current) }
        }
    }

    fun getCachedNoiseFingerprint(noiseKeyHex: String): String? {
        val key = noiseKeyHex.lowercase()
        val entries = prefs.getStringSet(KEY_CACHED_NOISE_FINGERPRINTS, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$key=") } ?: return null
        return entry.substringAfter('=').takeIf { isValidFingerprint(it) }
    }

    fun cacheNoiseFingerprint(noiseKeyHex: String, fingerprint: String) {
        if (!isValidFingerprint(fingerprint)) return
        if (!noiseKeyHex.matches(Regex("^[a-fA-F0-9]{64}$"))) return
        val key = noiseKeyHex.lowercase()
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_NOISE_FINGERPRINTS, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$key=") }
            current.add("$key=$fingerprint")
            prefs.edit { putStringSet(KEY_CACHED_NOISE_FINGERPRINTS, current) }
        }
    }

    fun getCachedFingerprintNickname(fingerprint: String): String? {
        if (!isValidFingerprint(fingerprint)) return null
        val key = fingerprint.lowercase()
        val entries = prefs.getStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES, emptySet()) ?: return null
        val entry = entries.firstOrNull { it.startsWith("$key=") } ?: return null
        val encoded = entry.substringAfter('=')
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    fun cacheFingerprintNickname(fingerprint: String, nickname: String) {
        if (!isValidFingerprint(fingerprint)) return
        val key = fingerprint.lowercase()
        val encoded = Base64.encodeToString(nickname.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        synchronized(lock) {
            val current = prefs.getStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
            current.removeAll { it.startsWith("$key=") }
            current.add("$key=$encoded")
            prefs.edit { putStringSet(KEY_CACHED_FINGERPRINT_NICKNAMES, current) }
        }
    }

    // MARK: - Authenticated private-media capability pins

    fun isPrivateMediaCapable(fingerprint: String): Boolean {
        if (!isValidFingerprint(fingerprint)) return false
        return synchronized(identityPersistenceLock) {
            if (identityPersistenceEpochAtCreation != identityPersistenceEpoch) {
                return@synchronized false
            }
            prefs.getStringSet(KEY_PRIVATE_MEDIA_CAPABILITY_PINS, emptySet())
                ?.any { it.equals(fingerprint, ignoreCase = true) } == true
        }
    }

    /** Persist capabilities and Ed25519 key from a decoded Noise 0x21 proof in one edit. */
    @SuppressLint("UseKtx")
    fun storeAuthenticatedPeerState(
        fingerprint: String,
        state: AuthenticatedPeerState,
        onCommitted: () -> Unit = {}
    ): Boolean {
        if (!isValidFingerprint(fingerprint) ||
            state.signingPublicKey.size != VouchAttestation.SIGNING_KEY_SIZE
        ) return false
        val normalizedFingerprint = fingerprint.lowercase()
        return synchronized(identityPersistenceLock) {
            // A controller that survived panic must not republish pre-wipe proof state.
            if (identityPersistenceEpochAtCreation != identityPersistenceEpoch) return@synchronized false
            val records = prefs.getStringSet(KEY_AUTHENTICATED_PEER_STATES, emptySet())
                ?.toMutableSet() ?: mutableSetOf()
            records.removeAll { it.startsWith("$normalizedFingerprint:") }
            val capabilitiesHex = java.lang.Long.toUnsignedString(state.capabilities.rawValue, 16)
            records.add(
                "$normalizedFingerprint:$capabilitiesHex:${state.signingPublicKey.hexEncodedString()}"
            )

            val editor = prefs.edit().putStringSet(KEY_AUTHENTICATED_PEER_STATES, records)
            if (state.capabilities.contains(PeerCapabilities.PRIVATE_MEDIA)) {
                val pins = prefs.getStringSet(KEY_PRIVATE_MEDIA_CAPABILITY_PINS, emptySet())
                    ?.mapTo(mutableSetOf()) { it.lowercase() } ?: mutableSetOf()
                pins.add(normalizedFingerprint)
                editor.putStringSet(KEY_PRIVATE_MEDIA_CAPABILITY_PINS, pins)
            }
            // This result is a security boundary: do not publish the Ed key in memory unless the
            // encrypted identity record and its HSTS pin were durably committed together.
            editor.commit().also { committed ->
                if (committed) {
                    identityChanges.tryEmit(Unit)
                    onCommitted()
                }
            }
        }
    }

    fun getAuthenticatedPeerState(fingerprint: String): AuthenticatedPeerState? {
        if (!isValidFingerprint(fingerprint)) return null
        return synchronized(identityPersistenceLock) {
            if (identityPersistenceEpochAtCreation != identityPersistenceEpoch) return@synchronized null
            val prefix = "${fingerprint.lowercase()}:"
            val record = prefs.getStringSet(KEY_AUTHENTICATED_PEER_STATES, emptySet())
                ?.firstOrNull { it.startsWith(prefix) } ?: return@synchronized null
            val fields = record.split(':', limit = 3)
            if (fields.size != 3) return@synchronized null
            val capabilities = runCatching {
                PeerCapabilities(java.lang.Long.parseUnsignedLong(fields[1], 16))
            }.getOrNull() ?: return@synchronized null
            val signingKeyHex = fields[2]
            if (!signingKeyHex.matches(Regex("^[0-9a-f]{64}$"))) return@synchronized null
            val signingKey = runCatching {
                signingKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.getOrNull() ?: return@synchronized null
            AuthenticatedPeerState(capabilities, signingKey)
        }
    }

    fun getAuthenticatedSigningKey(fingerprint: String): ByteArray? =
        getAuthenticatedPeerState(fingerprint)?.signingPublicKey?.copyOf()
    
    // MARK: - Peer ID Rotation Management (removed)
    // Android now derives peer ID from the persisted Noise identity fingerprint.
    // No timed peer ID rotation is performed here.
    
    // MARK: - Identity Validation
    
    /**
     * Validate that a public key is valid for Curve25519
     */
    fun validatePublicKey(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        
        // Check for all-zero key (invalid point)
        if (publicKey.all { it == 0.toByte() }) return false
        
        // Check for other known invalid points
        val invalidPoints = setOf(
            ByteArray(32) { 0x00.toByte() }, // All zeros
            ByteArray(32) { 0xFF.toByte() }, // All ones
            // Add other known invalid Curve25519 points if needed
        )
        
        return !invalidPoints.any { it.contentEquals(publicKey) }
    }
    
    /**
     * Validate that a private key is valid for Curve25519
     */
    fun validatePrivateKey(privateKey: ByteArray): Boolean {
        if (privateKey.size != 32) return false
        
        // Check for all-zero key
        if (privateKey.all { it == 0.toByte() }) return false
        
        // Check that clamping bits are correct for Curve25519
        val clampedKey = privateKey.clone()
        clampedKey[0] = (clampedKey[0].toInt() and 248).toByte()
        clampedKey[31] = (clampedKey[31].toInt() and 127).toByte()
        clampedKey[31] = (clampedKey[31].toInt() or 64).toByte()
        
        // After clamping, the key should not be all zeros
        return !clampedKey.all { it == 0.toByte() }
    }
    
    // MARK: - Debug Information
    
    /**
     * Get debug information about identity state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Identity State Manager Debug ===")
        
        val hasIdentity = prefs.contains(KEY_STATIC_PRIVATE_KEY)
        appendLine("Has identity: $hasIdentity")
        
        if (hasIdentity) {
            try {
                val keyPair = loadStaticKey()
                if (keyPair != null) {
                    val fingerprint = generateFingerprint(keyPair.second)
                    appendLine("Identity fingerprint: ${fingerprint.take(16)}...")
                    appendLine("Key validation: private=${validatePrivateKey(keyPair.first)}, public=${validatePublicKey(keyPair.second)}")
                }
            } catch (e: Exception) {
                appendLine("Key validation failed: ${e.message}")
            }
        }
    }
    
    // MARK: - Emergency Clear
    
    /**
     * Clear all identity data (for panic mode)
     */
    @SuppressLint("UseKtx")
    fun clearIdentityData() {
        try {
            synchronized(identityPersistenceLock) {
                identityPersistenceEpoch += 1
                identityPersistenceEpochAtCreation = identityPersistenceEpoch
                if (!prefs.edit().clear().commit()) {
                    Log.e(TAG, "Identity preference wipe could not be committed")
                }
                identityChanges.tryEmit(Unit)
            }
            Log.w(TAG, "All identity data cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear identity data: ${e.message}")
        }
    }
    
    /**
     * Check if identity data exists
     */
    fun hasIdentityData(): Boolean {
        return prefs.contains(KEY_STATIC_PRIVATE_KEY) && prefs.contains(KEY_STATIC_PUBLIC_KEY)
    }
    
    // MARK: - Public SharedPreferences Access (for favorites and Nostr data)
    
    /**
     * Store a string value in secure preferences
     */
    fun storeSecureValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    
    /**
     * Retrieve a string value from secure preferences
     */
    fun getSecureValue(key: String): String? {
        return prefs.getString(key, null)
    }
    
    /**
     * Remove a value from secure preferences
     */
    fun removeSecureValue(key: String) {
        prefs.edit().remove(key).apply()
    }
    
    /**
     * Check if a key exists in secure preferences
     */
    fun hasSecureValue(key: String): Boolean {
        return prefs.contains(key)
    }
    
    /**
     * Clear specific keys from secure preferences
     */
    fun clearSecureValues(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { key ->
            editor.remove(key)
        }
        editor.apply()
    }

    /** Use for panic paths that must finish the disk mutation before identity reset continues. */
    fun clearSecureValuesSynchronously(vararg keys: String): Boolean {
        val editor = prefs.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }
}
