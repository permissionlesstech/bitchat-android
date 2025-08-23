package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.*

/**
 * Loads relay coordinates from assets and provides nearest-relay lookup by geohash.
 */
object RelayDirectory {

    private const val TAG = "RelayDirectory"
    private const val ASSET_FILE = "nostr_relays.csv"

    data class RelayInfo(
        val url: String,
        val latitude: Double,
        val longitude: Double
    )

    @Volatile
    private var initialized: Boolean = false

    private val relays: MutableList<RelayInfo> = mutableListOf()

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val input = application.assets.open(ASSET_FILE)
                BufferedReader(InputStreamReader(input)).use { reader ->
                    var line: String?
                    var numLoaded = 0
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        val trimmed = line!!.trim()
                        if (trimmed.isEmpty()) continue
                        // Skip header if present
                        if (trimmed.lowercase().startsWith("relay url")) continue
                        val parts = trimmed.split(",")
                        if (parts.size < 3) continue
                        val url = parts[0].trim()
                        val lat = parts[1].trim().toDoubleOrNull()
                        val lon = parts[2].trim().toDoubleOrNull()
                        if (url.isEmpty() || lat == null || lon == null) continue
                        relays.add(RelayInfo(url = url, latitude = lat, longitude = lon))
                        numLoaded += 1
                    }
                    Log.i(TAG, "📥 Loaded $numLoaded relay entries from assets/$ASSET_FILE")
                }
                initialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize RelayDirectory: ${e.message}")
            }
        }
    }

    /**
     * Return up to nRelays closest relay URLs to the geohash center.
     */
    fun closestRelaysForGeohash(geohash: String, nRelays: Int): List<String> {
        if (relays.isEmpty()) return emptyList()
        val center = try {
            val c = com.bitchat.android.geohash.Geohash.decodeToCenter(geohash)
            c
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode geohash '$geohash': ${e.message}")
            return emptyList()
        }

        val (lat, lon) = center
        return relays
            .asSequence()
            .sortedBy { haversineMeters(lat, lon, it.latitude, it.longitude) }
            .take(nRelays.coerceAtLeast(0))
            .map { it.url }
            .toList()
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}


