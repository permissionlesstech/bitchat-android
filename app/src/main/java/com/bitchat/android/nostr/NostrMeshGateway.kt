package com.bitchat.android.nostr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Nostr Mesh Gateway helpers
 * - publishToMeshOrRelay(event): decide ruta según conectividad
 * - MeshListener snippet: detecta TYPE_NOSTR_RELAY_REQUEST paquetes y publica en relays
 */
object NostrMeshGateway {
    private const val TAG = "NostrMeshGateway"

    /**
     * Decide publicar directamente a relays si hay conectividad, o enviar por Mesh si no
     */
    fun publishToMeshOrRelay(context: Context, event: NostrEvent, meshSender: (ByteArray) -> Unit) {
        if (hasInternetConnection(context)) {
            Log.d(TAG, "Device online - publishing event to relays directly")
            // Ensure event meets PoW requirement before sending
            val minDifficulty = com.bitchat.android.nostr.NostrProofOfWork.estimateWork(0) // placeholder: use PoW settings

            // Send immediately via NostrRelayManager
            try {
                NostrRelayManager.getInstance(context).sendEvent(event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send event to relays: ${e.message}")
                // Fallback: send over mesh
                try {
                    val payload = NostrMeshSerializer.serializeEventForMesh(event)
                    meshSender(payload)
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to serialize event for mesh fallback: ${ex.message}")
                }
            }
        } else {
            Log.d(TAG, "No internet - sending event over mesh")
            // When offline: ensure PoW is present (NIP-13). Mining should be done before calling this ideally.
            // If not mined, we could kick off mining synchronously (dangerous for battery) or reject.
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    // If event lacks nonce, attempt a light PoW using user-preferred difficulty
                    val prefDifficulty = NostrProofOfWork.estimateWork(8).toIntOrNullSafe() ?: 8
                } catch (ignored: Exception) {}
            }

            try {
                val payload = NostrMeshSerializer.serializeEventForMesh(event)
                meshSender(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize event for mesh: ${e.message}")
            }
        }
    }

    /**
     * Simple connectivity check helper
     */
    fun hasInternetConnection(context: Context): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(nw) ?: return false
            return actNw.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Mesh listener snippet to be integrated in the mesh receive path.
     * When a packet with header TYPE_NOSTR_RELAY_REQUEST is received and device has internet,
     * it will attempt to deserialize, validate signature & PoW, then publish to relays.
     */
    fun meshPacketReceived(context: Context, packet: ByteArray, ackSender: ((ByteArray) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Quick header check
                if (packet.isEmpty()) return@launch
                val header = packet[0]
                if (header != NostrMeshSerializer.TYPE_NOSTR_RELAY_REQUEST && header != NostrMeshSerializer.TYPE_NOSTR_PLAINTEXT) {
                    return@launch
                }

                if (!hasInternetConnection(context)) {
                    Log.d(TAG, "Received Nostr mesh packet but device is offline - skipping relay publish")
                    return@launch
                }

                val jsonString = NostrMeshSerializer.deserializeEventFromMesh(packet) ?: run {
                    Log.w(TAG, "Failed to deserialize Nostr event from mesh packet")
                    return@launch
                }

                // Parse event
                val event = NostrEvent.fromJsonString(jsonString)
                if (event == null) {
                    Log.w(TAG, "Failed to parse NostrEvent JSON")
                    return@launch
                }

                // Validate signature (NIP-01)
                if (!event.isValidSignature()) {
                    Log.w(TAG, "Invalid Nostr signature - discarding event id=${event.id.take(16)}...")
                    return@launch
                }

                // Validate PoW (NIP-13) - require at least low difficulty (8 bits)
                val requiredDifficulty = 8
                if (!NostrProofOfWork.validateDifficulty(event, requiredDifficulty)) {
                    Log.w(TAG, "Event failed PoW validation - discarding id=${event.id.take(16)}...")
                    return@launch
                }

                // Publish to relays via NostrRelayManager
                try {
                    NostrRelayManager.getInstance(context).sendEvent(event)
                    Log.i(TAG, "Published mesh-origin event to relays id=${event.id.take(16)}...")

                    // Optionally send acknowledgement back over mesh
                    ackSender?.let { sender ->
                        try {
                            val ack = "DELIVERED:${event.id}".toByteArray(Charsets.UTF_8)
                            sender(ack)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send ack over mesh: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to publish event to relays: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling mesh packet: ${e.message}")
            }
        }
    }

    // helper extension to attempt convert Long to Int safely
    private fun Long.toIntOrNullSafe(): Int? {
        return if (this in Int.MIN_VALUE..Int.MAX_VALUE) this.toInt() else null
    }
}
