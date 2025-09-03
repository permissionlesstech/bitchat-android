package com.bitchat.android.services.geohash

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.JsonParser
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reverse-resolves geohash -> human-readable location name.
 * - Prefers Android Geocoder when available
 * - Falls back to Nominatim (OpenStreetMap)
 * - Caches results across precisions (stores under all parent prefixes)
 */
object GeohashLocationNameService {
    private const val TAG = "GeohashLocationNameService"

    // Simple LRU cache
    private const val MAX_ENTRIES = 256
    private val cache: MutableMap<String, String> = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    /**
     * Resolve a short human name (City or Country) for the geohash.
     * Returns city if available; otherwise country. Caches across prefixes.
     */
    suspend fun resolveCityOrCountry(
        context: Context,
        geohash: String,
        acceptLanguage: String? = null,
        emailForNominatim: String? = null
    ): String? {
        val normalized = geohash.trim().lowercase(Locale.ROOT)
        getCachedName(normalized)?.let { return it }

        val (lat, lon) = try { decodeGeohashToLatLon(normalized) } catch (e: Exception) {
            Log.w(TAG, "Invalid geohash '$geohash': ${e.message}")
            return null
        }

        // Prefer Android Geocoder
        val fromGeocoder = if (Geocoder.isPresent()) {
            tryCityOrCountryWithAndroidGeocoder(context, lat, lon)
        } else null
        if (!fromGeocoder.isNullOrBlank()) {
            putCacheForPrefixes(normalized, fromGeocoder)
            return fromGeocoder
        }

        // Fallback to Nominatim
        val fromNominatim = tryCityOrCountryWithNominatim(context, lat, lon, acceptLanguage, emailForNominatim)
        if (!fromNominatim.isNullOrBlank()) {
            putCacheForPrefixes(normalized, fromNominatim)
            return fromNominatim
        }

        return null
    }

    private suspend fun tryCityOrCountryWithAndroidGeocoder(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lon, 1)
            if (!results.isNullOrEmpty()) {
                val addr = results[0]
                val city = addr.locality?.takeIf { it.isNotEmpty() }
                val country = addr.countryName?.takeIf { it.isNotEmpty() }
                city ?: country
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Android Geocoder (city/country) failed: ${e.message}")
            null
        }
    }

    private suspend fun tryCityOrCountryWithNominatim(
        context: Context,
        lat: Double,
        lon: Double,
        acceptLanguage: String?,
        emailForNominatim: String?
    ): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("nominatim.openstreetmap.org")
                .addPathSegment("reverse")
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("lat", lat.toString())
                .addQueryParameter("lon", lon.toString())
                .addQueryParameter("zoom", "10")
                .addQueryParameter("addressdetails", "1")
                .build()

            val userAgent = buildString {
                append("bitchat-android/")
                append(getAppVersionSafe(context))
                append(" (")
                append(getAppIdSafe(context))
                append(")")
                emailForNominatim?.let { append(" (+$it)") }
            }

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", acceptLanguage ?: Locale.getDefault().language)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val json = JsonParser.parseString(body).asJsonObject
                if (!json.has("address")) return@use null
                val addr = json.getAsJsonObject("address")
                val city = listOf("city", "town", "village").firstNotNullOfOrNull { k -> addr.get(k)?.asString }
                val country = addr.get("country")?.asString
                return@use city ?: country
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim (city/country) failed: ${e.message}")
            null
        }
    }

    private val client: OkHttpClient
        get() = com.bitchat.android.net.OkHttpProvider.httpClient()

    /**
     * Return a cached name for the geohash if present.
     */
    fun getCachedName(geohash: String): String? {
        val key = geohash.lowercase(Locale.ROOT)
        // Prefer exact match; if not found, try parent prefixes from longest to shortest
        cache[key]?.let { return it }
        for (len in key.length - 1 downTo 2) {
            val prefix = key.substring(0, len)
            val cached = cache[prefix]
            if (cached != null) return cached
        }
        return null
    }

    /**
     * Decode geohash to center latitude/longitude.
     */
    fun decodeGeohashToLatLon(geohash: String): Pair<Double, Double> {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var isEven = true
        var lat = doubleArrayOf(-90.0, 90.0)
        var lon = doubleArrayOf(-180.0, 180.0)

        for (c in geohash.lowercase(Locale.ROOT)) {
            val cd = base32.indexOf(c)
            if (cd == -1) throw IllegalArgumentException("Invalid geohash char: $c")
            for (mask in intArrayOf(16, 8, 4, 2, 1)) {
                if (isEven) {
                    val mid = (lon[0] + lon[1]) / 2
                    if (cd and mask != 0) lon[0] = mid else lon[1] = mid
                } else {
                    val mid = (lat[0] + lat[1]) / 2
                    if (cd and mask != 0) lat[0] = mid else lat[1] = mid
                }
                isEven = !isEven
            }
        }
        val latitude = (lat[0] + lat[1]) / 2.0
        val longitude = (lon[0] + lon[1]) / 2.0
        return Pair(latitude, longitude)
    }

    /**
     * Resolve a human-friendly name for the geohash, using Geocoder first, then Nominatim.
     * Results are cached under the geohash and all its parent prefixes (length >= 2).
     */
    suspend fun resolveName(
        context: Context,
        geohash: String,
        acceptLanguage: String? = null,
        emailForNominatim: String? = null
    ): String? {
        val normalized = geohash.trim().lowercase(Locale.ROOT)
        getCachedName(normalized)?.let { return it }

        val (lat, lon) = try { decodeGeohashToLatLon(normalized) } catch (e: Exception) {
            Log.w(TAG, "Invalid geohash '$geohash': ${e.message}")
            return null
        }

        // 1) Try Android Geocoder if present
        val geocoderName = if (Geocoder.isPresent()) {
            tryReverseWithAndroidGeocoder(context, lat, lon)
        } else null
        if (!geocoderName.isNullOrBlank()) {
            putCacheForPrefixes(normalized, geocoderName)
            return geocoderName
        }

        // 2) Fallback to Nominatim
        val nominatimName = tryReverseWithNominatim(context, lat, lon, acceptLanguage, emailForNominatim)
        if (!nominatimName.isNullOrBlank()) {
            putCacheForPrefixes(normalized, nominatimName)
            return nominatimName
        }

        return null
    }

    private fun putCacheForPrefixes(geohash: String, name: String) {
        cache[geohash] = name
        // Also store under parent prefixes so different precisions benefit
        for (len in geohash.length - 1 downTo 2) {
            val prefix = geohash.substring(0, len)
            cache.putIfAbsent(prefix, name)
        }
    }

    private suspend fun tryReverseWithAndroidGeocoder(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lon, 1)
            if (!results.isNullOrEmpty()) {
                val addr = results[0]
                val parts = mutableListOf<String>()
                addr.locality?.let { if (it.isNotEmpty()) parts.add(it) }
                addr.subLocality?.let { if (it.isNotEmpty() && !parts.contains(it)) parts.add(it) }
                addr.thoroughfare?.let { if (it.isNotEmpty() && !parts.contains(it)) parts.add(it) }
                addr.adminArea?.let { if (it.isNotEmpty()) parts.add(it) }
                addr.countryName?.let { if (it.isNotEmpty()) parts.add(it) }
                parts.joinToString(", ").ifBlank { null }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Android Geocoder failed: ${e.message}")
            null
        }
    }

    private suspend fun tryReverseWithNominatim(
        context: Context,
        lat: Double,
        lon: Double,
        acceptLanguage: String?,
        emailForNominatim: String?
    ): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("nominatim.openstreetmap.org")
                .addPathSegment("reverse")
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("lat", lat.toString())
                .addQueryParameter("lon", lon.toString())
                .addQueryParameter("zoom", "10")
                .addQueryParameter("addressdetails", "1")
                .build()

            val userAgent = buildString {
                append("bitchat-android/")
                append(getAppVersionSafe(context))
                append(" (")
                append(getAppIdSafe(context))
                append(")")
                emailForNominatim?.let { append(" (+$it)") }
            }

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", acceptLanguage ?: Locale.getDefault().language)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val json = JsonParser.parseString(body).asJsonObject
                if (json.has("display_name")) {
                    return@use json.get("display_name").asString
                }
                if (json.has("address")) {
                    val addr = json.getAsJsonObject("address")
                    val keys = listOf("city", "town", "village", "county", "state", "country")
                    val parts = keys.mapNotNull { k -> if (addr.has(k)) addr.get(k).asString else null }
                    if (parts.isNotEmpty()) return@use parts.joinToString(", ")
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim failed: ${e.message}")
            null
        }
    }

    private fun getAppIdSafe(context: Context): String = try {
        context.packageName
    } catch (_: Throwable) { "com.bitchat.android" }

    private fun getAppVersionSafe(context: Context): String = try {
        val pm = context.packageManager
        val pInfo = pm.getPackageInfo(context.packageName, 0)
        pInfo.versionName ?: "unknown"
    } catch (_: Throwable) { "unknown" }

    /**
     * Convenience prefetch that does not suspend; runs on IO.
     */
    fun prefetch(context: Context, geohash: String, acceptLanguage: String? = null, emailForNominatim: String? = null): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            try {
                resolveName(context, geohash, acceptLanguage, emailForNominatim)
            } catch (_: Exception) { }
        }
    }
}

