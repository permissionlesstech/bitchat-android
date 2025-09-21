package com.bitchat.android.geohash

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Stores a user-maintained list of bookmarked geohash channels.
 * - Persistence: SharedPreferences (JSON string array)
 * - Semantics: geohashes are normalized to lowercase base32 and de-duplicated
 */
class GeohashBookmarksStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GeohashBookmarksStore"
        private const val STORE_KEY = "locationChannel.bookmarks"
        private const val NAMES_STORE_KEY = "locationChannel.bookmarkNames"

        @Volatile private var INSTANCE: GeohashBookmarksStore? = null
        fun getInstance(context: Context): GeohashBookmarksStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeohashBookmarksStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        private val allowedChars = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()
        fun normalize(raw: String): String {
            return raw.trim().lowercase(Locale.US)
                .replace("#", "")
                .filter { allowedChars.contains(it) }
        }
    }

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("geohash_prefs", Context.MODE_PRIVATE)

    private val membership = mutableSetOf<String>()

    private val _bookmarks = MutableLiveData<List<String>>(emptyList())
    val bookmarks: LiveData<List<String>> = _bookmarks

    private val _bookmarkNames = MutableLiveData<Map<String, String>>(emptyMap())
    val bookmarkNames: LiveData<Map<String, String>> = _bookmarkNames

    // For throttling / preventing duplicate geocode lookups
    private val resolving = mutableSetOf<String>()

    init { load() }

    fun isBookmarked(geohash: String): Boolean = membership.contains(normalize(geohash))

    fun toggle(geohash: String) {
        val gh = normalize(geohash)
        if (membership.contains(gh)) remove(gh) else add(gh)
    }

    fun add(geohash: String) {
        val gh = normalize(geohash)
        if (gh.isEmpty() || membership.contains(gh)) return
        membership.add(gh)
        val updated = listOf(gh) + (_bookmarks.value ?: emptyList())
        _bookmarks.postValue(updated)
        persist(updated)
        // Resolve friendly name asynchronously
        resolveNameIfNeeded(gh)
    }

    fun remove(geohash: String) {
        val gh = normalize(geohash)
        if (!membership.contains(gh)) return
        membership.remove(gh)
        val updated = (_bookmarks.value ?: emptyList()).filterNot { it == gh }
        _bookmarks.postValue(updated)
        // Remove stored name to avoid stale cache growth
        val names = _bookmarkNames.value?.toMutableMap() ?: mutableMapOf()
        if (names.remove(gh) != null) {
            _bookmarkNames.postValue(names)
            persistNames(names)
        }
        persist(updated)
    }

    // MARK: - Persistence

    private fun load() {
        try {
            val arrJson = prefs.getString(STORE_KEY, null)
            if (!arrJson.isNullOrEmpty()) {
                val listType = object : TypeToken<List<String>>() {}.type
                val arr = gson.fromJson<List<String>>(arrJson, listType)
                val seen = mutableSetOf<String>()
                val ordered = mutableListOf<String>()
                arr.forEach { raw ->
                    val gh = normalize(raw)
                    if (gh.isNotEmpty() && !seen.contains(gh)) {
                        seen.add(gh)
                        ordered.add(gh)
                    }
                }
                membership.clear(); membership.addAll(seen)
                _bookmarks.postValue(ordered)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bookmarks: ${e.message}")
        }
        try {
            val namesJson = prefs.getString(NAMES_STORE_KEY, null)
            if (!namesJson.isNullOrEmpty()) {
                val mapType = object : TypeToken<Map<String, String>>() {}.type
                val dict = gson.fromJson<Map<String, String>>(namesJson, mapType)
                _bookmarkNames.postValue(dict)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bookmark names: ${e.message}")
        }
    }

    private fun persist() {
        try {
            val json = gson.toJson(_bookmarks.value ?: emptyList<String>())
            prefs.edit().putString(STORE_KEY, json).apply()
        } catch (_: Exception) {}
    }

    private fun persistNames() {
        try {
            val json = gson.toJson(_bookmarkNames.value ?: emptyMap<String, String>())
            prefs.edit().putString(NAMES_STORE_KEY, json).apply()
        } catch (_: Exception) {}
    }

    // MARK: - Destructive Reset

    fun clearAll() {
        try {
            membership.clear()
            _bookmarks.postValue(emptyList())
            _bookmarkNames.postValue(emptyMap())
            prefs.edit()
                .remove(STORE_KEY)
                .remove(NAMES_STORE_KEY)
                .apply()
            // Clear any in-flight resolutions to avoid repopulating
            resolving.clear()
            Log.i(TAG, "Cleared all geohash bookmarks and names")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear geohash bookmarks: ${e.message}")
        }
    }


    // MARK: - Friendly Name Resolution

    fun resolveNameIfNeeded(geohash: String) {
        val gh = normalize(geohash)
        if (gh.isEmpty()) return
        if (_bookmarkNames.value?.containsKey(gh) == true) return
        if (resolving.contains(gh)) return
        if (!Geocoder.isPresent()) return

        resolving.add(gh)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val name: String? = if (gh.length <= 2) {
                    // Composite admin name from multiple points
                    val b = Geohash.decodeToBounds(gh)
                    val points = listOf(
                        Location(LocationManager.GPS_PROVIDER).apply { latitude = (b.latMin + b.latMax) / 2; longitude = (b.lonMin + b.lonMax) / 2 },
                        Location(LocationManager.GPS_PROVIDER).apply { latitude = b.latMin; longitude = b.lonMin },
                        Location(LocationManager.GPS_PROVIDER).apply { latitude = b.latMin; longitude = b.lonMax },
                        Location(LocationManager.GPS_PROVIDER).apply { latitude = b.latMax; longitude = b.lonMin },
                        Location(LocationManager.GPS_PROVIDER).apply { latitude = b.latMax; longitude = b.lonMax }
                    )
                    val admins = linkedSetOf<String>()
                    for (loc in points) {
                        try {
                            @Suppress("DEPRECATION")
                            val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                            val a = list?.firstOrNull()
                            val admin = a?.adminArea?.takeIf { !it.isNullOrEmpty() }
                            val country = a?.countryName?.takeIf { !it.isNullOrEmpty() }
                            if (admin != null) admins.add(admin)
                            else if (country != null) admins.add(country)
                        } catch (_: Exception) {}
                        if (admins.size >= 2) break
                    }
                    when (admins.size) {
                        0 -> null
                        1 -> admins.first()
                        else -> admins.elementAt(0) + " and " + admins.elementAt(1)
                    }
                } else {
                    val center = Geohash.decodeToCenter(gh)
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocation(center.first, center.second, 1)
                    val a = list?.firstOrNull()
                    pickNameForLength(gh.length, a)
                }

                if (!name.isNullOrEmpty()) {
                    val current = _bookmarkNames.value?.toMutableMap() ?: mutableMapOf()
                    current[gh] = name
                    _bookmarkNames.postValue(current)
                    persistNames(current)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Name resolution failed for #$gh: ${e.message}")
            } finally {
                resolving.remove(gh)
            }
        }
    }

    private fun pickNameForLength(len: Int, address: android.location.Address?): String? {
        if (address == null) return null
        return when (len) {
            in 0..2 -> address.adminArea ?: address.countryName
            in 3..4 -> address.adminArea ?: address.subAdminArea ?: address.countryName
            5 -> address.locality ?: address.subAdminArea ?: address.adminArea
            in 6..7 -> address.subLocality ?: address.locality ?: address.adminArea
            else -> address.subLocality ?: address.locality ?: address.adminArea ?: address.countryName
        }
    }

    private fun persist(list: List<String>) {
        try {
            val json = gson.toJson(list)
            prefs.edit().putString(STORE_KEY, json).apply()
        } catch (_: Exception) {}
    }

    private fun persistNames(map: Map<String, String>) {
        try {
            val json = gson.toJson(map)
            prefs.edit().putString(NAMES_STORE_KEY, json).apply()
        } catch (_: Exception) {}
    }
}
