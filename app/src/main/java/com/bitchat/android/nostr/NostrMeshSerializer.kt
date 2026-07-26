package com.bitchat.android.nostr

import android.util.Log
import com.bitchat.android.protocol.CompressionUtil
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * NostrMeshSerializer
 * - Serializa eventos Nostr a payloads optimizados para transporte sobre Bluetooth Mesh
 * - Aplica compresión (preferencia: raw deflate via CompressionUtil, fallback GZIP)
 * - Añade un header simple para identificar paquetes tipo TYPE_NOSTR_RELAY_REQUEST
 *
 * Formato de paquete (simple):
 * [HEADER (1 byte)] [ORIG_SIZE (4 bytes, BE)] [BODY (bytes...)]
 * HEADER: 0x7E = TYPE_NOSTR_RELAY_REQUEST, 0x00 = uncompressed body
 */
object NostrMeshSerializer {
    private const val TAG = "NostrMeshSerializer"

    // Header identifiers
    const val TYPE_NOSTR_RELAY_REQUEST: Byte = 0x7E
    const val TYPE_NOSTR_PLAINTEXT: Byte = 0x00

    /**
     * Serializa y comprime un evento Nostr listo para ser enviado sobre la Mesh
     * - Devuelve el payload listo para enviar por BLE
     */
    fun serializeEventForMesh(event: NostrEvent): ByteArray {
        val json = event.toJsonString()
        val bytes = json.toByteArray(Charsets.UTF_8)

        // Preferir compresión compatible con el proyecto (CompressionUtil -> raw deflate)
        try {
            val compressed = CompressionUtil.compress(bytes)
            if (compressed != null) {
                Log.d(TAG, "Using raw deflate compression: ${compressed.size} < ${bytes.size}")
                return buildPacket(TYPE_NOSTR_RELAY_REQUEST, bytes.size, compressed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "CompressionUtil.compress failed: ${e.message}")
        }

        // Fallback: try GZIP if CompressionUtil didn't compress
        try {
            val gzipped = gzip(bytes)
            if (gzipped.size < bytes.size) {
                Log.d(TAG, "Using GZIP compression as fallback: ${gzipped.size} < ${bytes.size}")
                return buildPacket(TYPE_NOSTR_RELAY_REQUEST, bytes.size, gzipped)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GZIP fallback failed: ${e.message}")
        }

        // No compression beneficial -> send plaintext but still mark type
        return buildPacket(TYPE_NOSTR_PLAINTEXT, bytes.size, bytes)
    }

    /**
     * Reconstruye el evento Nostr desde el payload Mesh
     * - El caller decide si debe intentar descompress o validar
     */
    fun deserializeEventFromMesh(payload: ByteArray): String? {
        if (payload.isEmpty()) return null

        val header = payload[0]
        if (header != TYPE_NOSTR_RELAY_REQUEST && header != TYPE_NOSTR_PLAINTEXT) {
            Log.w(TAG, "Unknown packet header: $header")
            return null
        }

        if (payload.size < 5) {
            Log.w(TAG, "Payload too small to contain original size")
            return null
        }

        val origSize = ((payload[1].toInt() and 0xFF) shl 24) or
                ((payload[2].toInt() and 0xFF) shl 16) or
                ((payload[3].toInt() and 0xFF) shl 8) or
                (payload[4].toInt() and 0xFF)

        val body = payload.copyOfRange(5, payload.size)

        return when (header) {
            TYPE_NOSTR_RELAY_REQUEST -> {
                // Try raw deflate first (CompressionUtil expects raw deflate)
                CompressionUtil.decompress(body, origSize)?.let { bytes ->
                    return String(bytes, Charsets.UTF_8)
                }

                // Fallback try GZIP
                try {
                    val inflated = tryGzipDecompress(body)
                    if (inflated != null) return String(inflated, Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.w(TAG, "GZIP fallback failed during decompress: ${e.message}")
                }

                Log.w(TAG, "Failed to decompress body for Nostr payload")
                null
            }
            TYPE_NOSTR_PLAINTEXT -> {
                // Plaintext body
                String(body, Charsets.UTF_8)
            }
            else -> null
        }
    }

    private fun buildPacket(header: Byte, originalSize: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(5 + body.size)
        out.write(byteArrayOf(header))
        // Original size big-endian 4 bytes
        out.write((originalSize ushr 24) and 0xFF)
        out.write((originalSize ushr 16) and 0xFF)
        out.write((originalSize ushr 8) and 0xFF)
        out.write((originalSize) and 0xFF)
        out.write(body)
        return out.toByteArray()
    }

    private fun gzip(input: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(input) }
        return baos.toByteArray()
    }

    private fun tryGzipDecompress(input: ByteArray): ByteArray? {
        return try {
            val inflater = java.util.zip.GZIPInputStream(input.inputStream())
            inflater.readBytes()
        } catch (e: Exception) {
            null
        }
    }
}
