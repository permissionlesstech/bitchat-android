package com.bitchat.android.services.bridge

import android.content.Context
import android.util.Base64
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.PrekeyBundle
import com.bitchat.android.noise.CourierNoiseCrypto
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Owns local one-time prekeys and verified peer bundles for courier v2.
 *
 * Local private keys use EncryptedSharedPreferences through
 * [SecureIdentityStateManager]. Peer bundles contain public material only,
 * but their consumption assignments are persisted so retries of one message
 * never spend additional prekeys.
 */
class PrekeyManager internal constructor(
    private val identity: PrekeyIdentity,
    private val localStore: LocalPrekeyStore,
    private val peerStore: PeerPrekeyStore,
    private val randomBytes: () -> ByteArray
) {
    data class Sealed(
        val ciphertext: ByteArray,
        val prekeyId: Long?
    )

    data class Opened(
        val payload: ByteArray,
        val senderStaticKey: ByteArray,
        val consumedPrekey: Boolean
    )

    private val lock = Any()
    private var local: LocalPrekeyState? = null
    private var peerBundles: MutableMap<String, StoredPeerPrekeyBundle>? = null

    fun currentSignedBundle(nowMs: Long = System.currentTimeMillis()): PrekeyBundle? = synchronized(lock) {
        val staticKey = identity.staticKey()?.second ?: return@synchronized null
        val signingPrivateKey = identity.signingKey()?.first ?: return@synchronized null
        val state = loadLocalLocked()
        replenishLocked(state, nowMs)
        val prekeys = state.records
            .asSequence()
            .filter { it.consumedAt == null }
            .sortedBy { it.id }
            .mapNotNull { record ->
                decode(record.privateKey)?.let { privateKey ->
                    PrekeyBundle.Prekey(record.id, CourierNoiseCrypto.publicKey(privateKey))
                }
            }
            .toList()
        if (prekeys.isEmpty()) return@synchronized null

        val unsigned = PrekeyBundle(
            noiseStaticPublicKey = staticKey,
            prekeys = prekeys,
            generatedAt = state.generatedAt,
            signature = ByteArray(PrekeyBundle.SIGNATURE_LENGTH)
        )
        val signature = signEd25519(unsigned.signableBytes(), signingPrivateKey) ?: return@synchronized null
        unsigned.copy(signature = signature)
    }

    fun verifyAndIngest(
        bundle: PrekeyBundle,
        expectedNoiseKey: ByteArray,
        announceBoundSigningKey: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!bundle.noiseStaticPublicKey.contentEquals(expectedNoiseKey) ||
            announceBoundSigningKey.size != PrekeyBundle.KEY_LENGTH ||
            !verifyEd25519(bundle.signature, bundle.signableBytes(), announceBoundSigningKey)
        ) {
            return false
        }

        synchronized(lock) {
            val bundles = loadPeerBundlesLocked()
            val key = encode(bundle.noiseStaticPublicKey)
            val existing = bundles[key]
            if (existing != null && existing.generatedAt >= bundle.generatedAt) return false

            val freshIds = bundle.prekeys.map { it.id }.toSet()
            bundles[key] = StoredPeerPrekeyBundle(
                noiseKey = key,
                generatedAt = bundle.generatedAt,
                prekeyIds = bundle.prekeys.map { it.id },
                prekeyPublicKeys = bundle.prekeys.map { encode(it.publicKey) },
                usedIds = existing?.usedIds?.filterTo(mutableSetOf()) { it in freshIds } ?: mutableSetOf(),
                assignments = existing?.assignments
                    ?.filterValues { it in freshIds }
                    ?.toMutableMap() ?: mutableMapOf(),
                updatedAt = nowMs
            )
            while (bundles.size > MAX_PEERS) {
                bundles.minByOrNull { it.value.updatedAt }?.key?.let(bundles::remove)
            }
            persistPeerBundlesLocked(bundles)
            return true
        }
    }

    fun seal(
        payload: ByteArray,
        messageId: String,
        recipientNoiseKey: ByteArray,
        recipientAdvertisesPrekeys: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Sealed {
        val senderPrivateKey = identity.staticKey()?.first
            ?: throw IllegalStateException("Noise static identity is unavailable")
        val assigned = if (recipientAdvertisesPrekeys) {
            assignPrekey(messageId, recipientNoiseKey, nowMs)
        } else {
            null
        }
        return if (assigned != null) {
            Sealed(
                CourierNoiseCrypto.sealToPrekey(payload, senderPrivateKey, assigned),
                assigned.id
            )
        } else {
            Sealed(
                CourierNoiseCrypto.seal(payload, senderPrivateKey, recipientNoiseKey),
                null
            )
        }
    }

    fun open(
        ciphertext: ByteArray,
        prekeyId: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): Opened {
        if (prekeyId == null) {
            val staticPrivateKey = identity.staticKey()?.first
                ?: throw IllegalStateException("Noise static identity is unavailable")
            val opened = CourierNoiseCrypto.open(ciphertext, staticPrivateKey)
            return Opened(opened.payload, opened.senderStaticKey, false)
        }

        synchronized(lock) {
            val state = loadLocalLocked()
            pruneLocked(state, nowMs)
            val record = state.records.firstOrNull { it.id == prekeyId }
                ?: throw IllegalArgumentException("Unknown or expired courier prekey")
            val consumedAt = record.consumedAt
            if (consumedAt != null && nowMs - consumedAt > CONSUMED_GRACE_MS) {
                throw IllegalArgumentException("Courier prekey grace window expired")
            }
            val privateKey = decode(record.privateKey)
                ?: throw IllegalArgumentException("Invalid courier prekey")
            val opened = CourierNoiseCrypto.openWithPrekey(ciphertext, privateKey, prekeyId)
            val newlyConsumed = record.consumedAt == null
            if (newlyConsumed) {
                record.consumedAt = nowMs
                advanceGeneratedAtLocked(state, nowMs)
                replenishLocked(state, nowMs)
                persistLocalLocked(state)
            }
            return Opened(opened.payload, opened.senderStaticKey, newlyConsumed)
        }
    }

    fun hasUsableBundle(
        recipientNoiseKey: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        val bundle = loadPeerBundlesLocked()[encode(recipientNoiseKey)] ?: return@synchronized false
        isFresh(bundle, nowMs) && bundle.prekeyIds.any { it !in bundle.usedIds }
    }

    fun wipe() = synchronized(lock) {
        local = LocalPrekeyState()
        peerBundles = mutableMapOf()
        localStore.clear()
        peerStore.clear()
    }

    private fun assignPrekey(
        messageId: String,
        recipientNoiseKey: ByteArray,
        nowMs: Long
    ): PrekeyBundle.Prekey? = synchronized(lock) {
        val bundles = loadPeerBundlesLocked()
        val key = encode(recipientNoiseKey)
        val bundle = bundles[key] ?: return@synchronized null
        if (!isFresh(bundle, nowMs)) return@synchronized null

        bundle.assignments[messageId]?.let { assigned ->
            val index = bundle.prekeyIds.indexOf(assigned)
            if (index >= 0) {
                return@synchronized decode(bundle.prekeyPublicKeys[index])
                    ?.let { PrekeyBundle.Prekey(assigned, it) }
            }
        }

        val index = bundle.prekeyIds.indices
            .filter { bundle.prekeyIds[it] !in bundle.usedIds }
            .minByOrNull { bundle.prekeyIds[it] }
            ?: return@synchronized null
        val id = bundle.prekeyIds[index]
        val publicKey = decode(bundle.prekeyPublicKeys[index]) ?: return@synchronized null
        bundle.usedIds += id
        bundle.assignments[messageId] = id
        bundle.updatedAt = nowMs
        persistPeerBundlesLocked(bundles)
        PrekeyBundle.Prekey(id, publicKey)
    }

    private fun replenishLocked(state: LocalPrekeyState, nowMs: Long): Boolean {
        val beforeRecords = state.records.size
        val beforeUnconsumed = state.records.count { it.consumedAt == null }
        pruneLocked(state, nowMs)
        val unconsumed = state.records.count { it.consumedAt == null }
        var changed = unconsumed != beforeUnconsumed
        if (unconsumed < REPLENISH_THRESHOLD) {
            repeat(PrekeyBundle.MAX_PREKEYS - unconsumed) {
                val privateKey = randomBytes()
                require(privateKey.size == PrekeyBundle.KEY_LENGTH)
                state.records += LocalPrekeyRecord(
                    id = state.nextId and 0xFFFF_FFFFL,
                    privateKey = encode(privateKey),
                    createdAt = nowMs
                )
                state.nextId = (state.nextId + 1) and 0xFFFF_FFFFL
            }
            advanceGeneratedAtLocked(state, nowMs)
            changed = true
        }
        if (changed || state.records.size != beforeRecords) persistLocalLocked(state)
        return changed
    }

    private fun pruneLocked(state: LocalPrekeyState, nowMs: Long) {
        state.records.removeAll { record ->
            record.consumedAt?.let { nowMs - it > CONSUMED_GRACE_MS }
                ?: (nowMs - record.createdAt > UNCONSUMED_RETENTION_MS)
        }
    }

    private fun advanceGeneratedAtLocked(state: LocalPrekeyState, nowMs: Long) {
        state.generatedAt = maxOf(nowMs.coerceAtLeast(0), state.generatedAt + 1)
    }

    private fun loadLocalLocked(): LocalPrekeyState {
        local?.let { return it }
        val loaded = localStore.load()
        local = loaded
        return loaded
    }

    private fun persistLocalLocked(state: LocalPrekeyState) {
        localStore.save(state)
    }

    private fun loadPeerBundlesLocked(): MutableMap<String, StoredPeerPrekeyBundle> {
        peerBundles?.let { return it }
        return peerStore.load().also { peerBundles = it }
    }

    private fun persistPeerBundlesLocked(bundles: Map<String, StoredPeerPrekeyBundle>) {
        peerStore.save(bundles)
    }

    private fun isFresh(bundle: StoredPeerPrekeyBundle, nowMs: Long): Boolean =
        nowMs - bundle.generatedAt <= MAX_BUNDLE_AGE_MS

    private fun signEd25519(data: ByteArray, privateKey: ByteArray): ByteArray? = runCatching {
        Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(privateKey, 0))
            update(data, 0, data.size)
        }.generateSignature()
    }.getOrNull()

    private fun verifyEd25519(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
        runCatching {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(data, 0, data.size)
            }.verifySignature(signature)
        }.getOrDefault(false)

    private fun encode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()

    companion object {
        private const val PEER_PREFS = "bitchat_prekey_bundles"
        private const val REPLENISH_THRESHOLD = 3
        private const val CONSUMED_GRACE_MS = 48L * 60 * 60 * 1000
        private const val UNCONSUMED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_BUNDLE_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_PEERS = 200

        @Volatile
        private var instance: PrekeyManager? = null

        fun getInstance(context: Context): PrekeyManager =
            instance ?: synchronized(this) {
                instance ?: run {
                    val application = context.applicationContext
                    val identityState = SecureIdentityStateManager(application)
                    val random = SecureRandom()
                    PrekeyManager(
                        identity = AndroidPrekeyIdentity(identityState),
                        localStore = SecureLocalPrekeyStore(identityState),
                        peerStore = SharedPreferencesPeerPrekeyStore(
                            application.getSharedPreferences(PEER_PREFS, Context.MODE_PRIVATE)
                        ),
                        randomBytes = {
                            ByteArray(PrekeyBundle.KEY_LENGTH).also(random::nextBytes)
                        }
                    ).also { instance = it }
                }
            }
    }
}
