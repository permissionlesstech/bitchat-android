package com.bitchat.android.services

import android.net.Uri
import android.util.Base64
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import androidx.core.net.toUri
import java.lang.ref.WeakReference

/**
 * QR verification helpers: schema, signing, and basic challenge/response helpers.
 */
object VerificationService {
    private const val CONTEXT = "bitchat-verify-v1"
    private const val RESPONSE_CONTEXT = "bitchat-verify-resp-v1"

    private var encryptionServiceRef: WeakReference<EncryptionService>? = null

    fun configure(encryptionService: EncryptionService) {
        this.encryptionServiceRef = WeakReference(encryptionService)
    }

    data class VerificationQR(
        val v: Int,
        val noiseKeyHex: String,
        val signKeyHex: String,
        val npub: String?,
        val nickname: String,
        val ts: Long,
        val nonceB64: String,
        val sigHex: String
    ) {
        fun canonicalBytes(): ByteArray {
            val out = ByteArrayOutputStream()

            fun appendField(value: String) {
                val data = value.toByteArray(Charsets.UTF_8)
                val len = minOf(data.size, 255)
                out.write(len)
                out.write(data, 0, len)
            }

            appendField(CONTEXT)
            appendField(v.toString())
            appendField(noiseKeyHex.lowercase())
            appendField(signKeyHex.lowercase())
            appendField(npub ?: "")
            appendField(nickname)
            appendField(ts.toString())
            appendField(nonceB64)
            return out.toByteArray()
        }

        fun toUrlString(): String {
            val builder = Uri.Builder()
                .scheme("bitchat")
                .authority("verify")
                .appendQueryParameter("v", v.toString())
                .appendQueryParameter("noise", noiseKeyHex)
                .appendQueryParameter("sign", signKeyHex)
                .appendQueryParameter("nick", nickname)
                .appendQueryParameter("ts", ts.toString())
                .appendQueryParameter("nonce", nonceB64)
                .appendQueryParameter("sig", sigHex)
            if (npub != null) {
                builder.appendQueryParameter("npub", npub)
            }
            return builder.build().toString()
        }

        companion object {
            fun fromUrlString(urlString: String): VerificationQR? {
                val uri = runCatching { urlString.toUri() }.getOrNull() ?: return null
                if (uri.scheme != "bitchat" || uri.host != "verify") return null

                val vStr = uri.getQueryParameter("v") ?: return null
                val v = vStr.toIntOrNull() ?: return null
                val noise = uri.getQueryParameter("noise") ?: return null
                val sign = uri.getQueryParameter("sign") ?: return null
                val nick = uri.getQueryParameter("nick") ?: return null
                val tsStr = uri.getQueryParameter("ts") ?: return null
                val ts = tsStr.toLongOrNull() ?: return null
                val nonce = uri.getQueryParameter("nonce") ?: return null
                val sig = uri.getQueryParameter("sig") ?: return null
                val npub = uri.getQueryParameter("npub")

                return VerificationQR(
                    v = v,
                    noiseKeyHex = noise,
                    signKeyHex = sign,
                    npub = npub,
                    nickname = nick,
                    ts = ts,
                    nonceB64 = nonce,
                    sigHex = sig
                )
            }
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String? {
        val service = encryptionServiceRef?.get() ?: return null
        val cache = Cache.last
        if (cache != null && cache.nickname == nickname && cache.npub == npub) {
            if (System.currentTimeMillis() - cache.builtAtMs < 60_000L) {
                return cache.value
            }
        }

        val noiseKey = service.getStaticPublicKey()?.hexEncodedString() ?: return null
        val signKey = service.getSigningPublicKey()?.hexEncodedString() ?: return null
        val ts = System.currentTimeMillis() / 1000L
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        val nonceB64 = Base64.encodeToString(
            nonce,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val payload = VerificationQR(
            v = 1,
            noiseKeyHex = noiseKey,
            signKeyHex = signKey,
            npub = npub,
            nickname = nickname,
            ts = ts,
            nonceB64 = nonceB64,
            sigHex = ""
        )

        val signature = service.signData(payload.canonicalBytes()) ?: return null
        val signed = payload.copy(sigHex = signature.hexEncodedString())
        val out = signed.toUrlString()
        Cache.last = CacheEntry(nickname, npub, System.currentTimeMillis(), out)
        return out
    }

    fun verifyScannedQR(
        urlString: String,
        maxAgeSeconds: Long = AppConstants.Verification.QR_MAX_AGE_SECONDS
    ): VerificationQR? {
        val service = encryptionServiceRef?.get() ?: return null
        val qr = VerificationQR.fromUrlString(urlString) ?: return null
        val now = System.currentTimeMillis() / 1000L
        if (now - qr.ts > maxAgeSeconds) return null

        val sig = qr.sigHex.dataFromHexString() ?: return null
        val signKey = qr.signKeyHex.dataFromHexString() ?: return null
        val ok = service.verifyEd25519Signature(sig, qr.canonicalBytes(), signKey)
        return if (ok) qr else null
    }

    fun buildVerifyChallenge(noiseKeyHex: String, nonceA: ByteArray): ByteArray {
        val noiseData = noiseKeyHex.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(minOf(noiseData.size, 255))
        out.write(noiseData, 0, minOf(noiseData.size, 255))
        out.write(0x02)
        out.write(minOf(nonceA.size, 255))
        out.write(nonceA, 0, minOf(nonceA.size, 255))
        return out.toByteArray()
    }

    fun buildVerifyResponse(noiseKeyHex: String, nonceA: ByteArray): ByteArray? {
        val service = encryptionServiceRef?.get() ?: return null
        val noiseData = noiseKeyHex.toByteArray(Charsets.UTF_8)
        val msg = ByteArrayOutputStream()
        msg.write(RESPONSE_CONTEXT.toByteArray(Charsets.UTF_8))
        msg.write(minOf(noiseData.size, 255))
        msg.write(noiseData, 0, minOf(noiseData.size, 255))
        msg.write(nonceA)
        val sig = service.signData(msg.toByteArray()) ?: return null

        val out = ByteArrayOutputStream()
        out.write(0x01)
        out.write(minOf(noiseData.size, 255))
        out.write(noiseData, 0, minOf(noiseData.size, 255))
        out.write(0x02)
        out.write(minOf(nonceA.size, 255))
        out.write(nonceA, 0, minOf(nonceA.size, 255))
        out.write(0x03)
        out.write(minOf(sig.size, 255))
        out.write(sig, 0, minOf(sig.size, 255))
        return out.toByteArray()
    }

    fun parseVerifyChallenge(data: ByteArray): Pair<String, ByteArray>? {
        var idx = 0

        fun take(n: Int): ByteArray? {
            if (idx + n > data.size) return null
            val out = data.copyOfRange(idx, idx + n)
            idx += n
            return out
        }

        val t1 = take(1) ?: return null
        if (t1[0].toInt() != 0x01) return null
        val l1 = take(1)?.get(0)?.toInt() ?: return null
        val noiseBytes = take(l1) ?: return null
        val noise = noiseBytes.toString(Charsets.UTF_8)

        val t2 = take(1) ?: return null
        if (t2[0].toInt() != 0x02) return null
        val l2 = take(1)?.get(0)?.toInt() ?: return null
        val nonce = take(l2) ?: return null

        return noise to nonce
    }

    data class VerifyResponse(val noiseKeyHex: String, val nonceA: ByteArray, val signature: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as VerifyResponse

            if (noiseKeyHex != other.noiseKeyHex) return false
            if (!nonceA.contentEquals(other.nonceA)) return false
            if (!signature.contentEquals(other.signature)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = noiseKeyHex.hashCode()
            result = 31 * result + nonceA.contentHashCode()
            result = 31 * result + signature.contentHashCode()
            return result
        }
    }

    fun parseVerifyResponse(data: ByteArray): VerifyResponse? {
        var idx = 0

        fun take(n: Int): ByteArray? {
            if (idx + n > data.size) return null
            val out = data.copyOfRange(idx, idx + n)
            idx += n
            return out
        }

        val t1 = take(1) ?: return null
        if (t1[0].toInt() != 0x01) return null
        val l1 = take(1)?.get(0)?.toInt() ?: return null
        val noiseBytes = take(l1) ?: return null
        val noise = noiseBytes.toString(Charsets.UTF_8)

        val t2 = take(1) ?: return null
        if (t2[0].toInt() != 0x02) return null
        val l2 = take(1)?.get(0)?.toInt() ?: return null
        val nonce = take(l2) ?: return null

        val t3 = take(1) ?: return null
        if (t3[0].toInt() != 0x03) return null
        val l3 = take(1)?.get(0)?.toInt() ?: return null
        val sig = take(l3) ?: return null

        return VerifyResponse(noise, nonce, sig)
    }

    fun verifyResponseSignature(
        noiseKeyHex: String,
        nonceA: ByteArray,
        signature: ByteArray,
        signerPublicKeyHex: String
    ): Boolean {
        val service = encryptionServiceRef?.get() ?: return false
        val noiseData = noiseKeyHex.toByteArray(Charsets.UTF_8)
        val msg = ByteArrayOutputStream()
        msg.write(RESPONSE_CONTEXT.toByteArray(Charsets.UTF_8))
        msg.write(minOf(noiseData.size, 255))
        msg.write(noiseData, 0, minOf(noiseData.size, 255))
        msg.write(nonceA)
        val signerKey = signerPublicKeyHex.dataFromHexString() ?: return false
        return service.verifyEd25519Signature(signature, msg.toByteArray(), signerKey)
    }

    private data class CacheEntry(
        val nickname: String,
        val npub: String?,
        val builtAtMs: Long,
        val value: String
    )

    private object Cache {
        var last: CacheEntry? = null
    }
}
