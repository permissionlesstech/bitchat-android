package com.bitchat.android.nostr

import android.util.Base64
import android.util.Log
import com.bitchat.android.util.AppConstants

/**
 * Decode a `bitchat1:` base64url payload with an explicit size ceiling.
 *
 * iOS rejects oversized embedded packets before allocating
 * (`NostrInboundPipeline.decodeEmbeddedBitChatPacket`). Without a bound,
 * a malicious gift wrap can force an arbitrarily large Base64 decode.
 */
object NostrEmbeddedPacketDecoder {
    private const val TAG = "NostrEmbeddedPacketDecoder"

    fun decodeBounded(
        base64Url: String,
        maxBytes: Int = AppConstants.Protocol.MAX_PAYLOAD_LENGTH
    ): ByteArray? {
        if (maxBytes <= 0) return null
        // Base64 expands 3 bytes -> 4 chars; reject oversized encodings first.
        val maxEncoded = ((maxBytes.toLong() + 2L) / 3L) * 4L
        if (base64Url.length.toLong() > maxEncoded) {
            Log.w(TAG, "Rejecting embedded packet encoding longer than $maxEncoded chars")
            return null
        }
        val decoded = base64URLDecode(base64Url) ?: return null
        if (decoded.size > maxBytes) {
            Log.w(TAG, "Rejecting embedded packet of ${decoded.size} bytes (max $maxBytes)")
            return null
        }
        return decoded
    }

    internal fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            Base64.decode(padded, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
}
