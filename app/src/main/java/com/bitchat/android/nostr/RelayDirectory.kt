package com.bitchat.android.nostr

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loads relay coordinates from assets and provides nearest-relay lookup by geohash.
 */
object RelayDirectory {

    private const val TAG = "RelayDirectory"
    private const val ASSET_FILE_URL = "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv"
    private const val ASSET_FILE = "nostr_relays.csv"
    private const val DOWNLOADED_FILE = "nostr_relays_reviewed.csv"
    private const val PREFS_NAME = "relay_directory_prefs"
    private const val KEY_LAST_UPDATE_MS = "reviewed_last_update_ms"
    private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient: OkHttpClient
        get() = com.bitchat.android.net.OkHttpProvider.httpClient()

    data class RelayInfo(
        val url: String,
        val latitude: Double,
        val longitude: Double
    )

    @Volatile
    private var initialized: Boolean = false

    private val relays: MutableList<RelayInfo> = mutableListOf()
    private val relaysLock = Any()
    private val changeListeners = CopyOnWriteArraySet<() -> Unit>()

    internal fun addChangeListener(listener: () -> Unit) { changeListeners.add(listener) }
    internal fun removeChangeListener(listener: () -> Unit) { changeListeners.remove(listener) }

    internal fun replaceRelays(entries: List<RelayInfo>) {
        val changed = synchronized(relaysLock) {
            if (relays == entries) false else {
                relays.clear()
                relays.addAll(entries)
                true
            }
        }
        if (changed) changeListeners.forEach { it() }
    }

    /** Warm backups must be used in both directions, including by healthy peers. */
    fun routingRelaysForGeohash(geohash: String, nRelays: Int = 5): List<String> =
        closestRelaysForGeohash(geohash, nRelays.coerceIn(0, 5) * 2)

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val downloaded = getDownloadedFile(application)
                val loadedFromDownloaded = if (downloaded.exists() && downloaded.canRead()) {
                    loadFromFile(downloaded, sourceLabel = "downloaded")
                } else {
                    false
                }

                if (!loadedFromDownloaded) {
                    loadFromAssets(application)
                }

                initialized = true

                // Trigger an immediate fetch if the data is stale (older than 24h)
                ioScope.launch {
                    if (isStale(application)) {
                        fetchAndMaybeSwap(application)
                    }
                }

                // Start periodic staleness check every minute
                startPeriodicRefresh(application)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize RelayDirectory: ${e.message}")
            }
        }
    }

    /**
     * Return up to nRelays closest relay URLs to the geohash center.
     */
    fun closestRelaysForGeohash(geohash: String, nRelays: Int): List<String> {
        val snapshot = synchronized(relaysLock) { relays.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val center = try {
            val c = com.bitchat.android.geohash.Geohash.decodeToCenter(geohash)
            c
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode geohash")
            return emptyList()
        }

        val (lat, lon) = center
        return snapshot
            .asSequence()
            .distinctBy { it.url }
            .sortedWith(compareBy<RelayInfo> {
                haversineMeters(lat, lon, it.latitude, it.longitude)
            }.thenBy { it.url })
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

    private fun normalizeRelayUrl(raw: String): String? {
        return runCatching {
            val value = raw.trim()
            val uri = URI(if ("://" in value) value else "wss://$value")
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme.lowercase() !in setOf("wss", "https") || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.path.orEmpty() !in setOf("", "/") ||
                (uri.port != -1 && uri.port !in 1..65535)
            ) return null
            val labels = host.split('.')
            if (host.length > 253 || labels.size < 2 || labels.all { it.all(Char::isDigit) } ||
                host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") ||
                labels.any { it.length !in 1..63 || it.startsWith('-') || it.endsWith('-') ||
                    !it.matches(Regex("[a-z0-9-]+")) }
            ) return null
            "wss://$host" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        }.getOrNull()
    }

    // ===== Implementation details =====

    private fun getPrefs(application: Application): SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)

    private fun getDownloadedFile(application: Application): File =
        File(application.filesDir, DOWNLOADED_FILE)

    private fun isStale(application: Application): Boolean {
        val last = getPrefs(application).getLong(KEY_LAST_UPDATE_MS, 0L)
        val now = System.currentTimeMillis()
        return now - last >= ONE_DAY_MS
    }

    private fun startPeriodicRefresh(application: Application) {
        ioScope.launch {
            while (true) {
                try {
                    if (isStale(application)) {
                        fetchAndMaybeSwap(application)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic refresh encountered an error: ${e.message}")
                }
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        }
    }

    private fun fetchAndMaybeSwap(application: Application) {
        try {
            val tmpFile = File.createTempFile("relays_", ".csv", application.cacheDir)
            val ok = downloadToFile(ASSET_FILE_URL, tmpFile)
            if (!ok) {
                Log.w(TAG, "Failed to fetch latest relays; keeping current list (will fallback to bundled if none)")
                tmpFile.delete()
                return
            }

            val parsed = parseCsv(FileInputStream(tmpFile))
            if (parsed.isEmpty()) {
                Log.w(TAG, "Downloaded relay CSV parsed to 0 entries; ignoring")
                tmpFile.delete()
                return
            }

            val dest = getDownloadedFile(application)
            tmpFile.inputStream().use { input ->
                FileOutputStream(dest, false).use { output ->
                    input.copyTo(output)
                }
            }
            tmpFile.delete()

            val hash = fileSha256Hex(dest)
            val entries = parsed.size

            replaceRelays(parsed)

            getPrefs(application).edit().putLong(KEY_LAST_UPDATE_MS, System.currentTimeMillis()).apply()

            Log.i(TAG, "✅ Using downloaded relay list (${dest.absolutePath}), entries=$entries, sha256=$hash, updatedAtMs=${getPrefs(application).getLong(KEY_LAST_UPDATE_MS, 0L)}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch and swap relay list: ${e.message}")
        }
    }

    private fun downloadToFile(url: String, dest: File): Boolean {
        return try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${'$'}{resp.code} when fetching $url")
                    return false
                }
                val body = resp.body ?: return false
                FileOutputStream(dest).use { out ->
                    body.byteStream().use { input ->
                        input.copyTo(out)
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Download error: ${e.message}")
            false
        }
    }

    private fun loadFromFile(file: File, sourceLabel: String): Boolean {
        return try {
            val list = parseCsv(FileInputStream(file))
            if (list.isEmpty()) {
                Log.w(TAG, "${sourceLabel} relay CSV has 0 entries; ignoring")
                false
            } else {
                replaceRelays(list)
                val hash = fileSha256Hex(file)
                Log.i(TAG, "📄 Loaded ${list.size} relay entries from ${sourceLabel} file (${file.absolutePath}), sha256=$hash")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading ${sourceLabel} relay file: ${e.message}")
            false
        }
    }

    private fun loadFromAssets(application: Application) {
        val list = try {
            parseCsv(application.assets.open(ASSET_FILE))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open asset $ASSET_FILE: ${e.message}")
            emptyList()
        }
        replaceRelays(list)
        // Compute asset hash for logging
        val hash = try {
            application.assets.open(ASSET_FILE).use { input ->
                streamSha256Hex(input)
            }
        } catch (e: Exception) {
            "error:${'$'}{e.message}"
        }
        Log.i(TAG, "📦 Loaded ${list.size} relay entries from assets/$ASSET_FILE, sha256=$hash")
    }

    internal fun parseCsv(input: InputStream): List<RelayInfo> {
        val entries = linkedMapOf<String, RelayInfo>()
        BufferedReader(InputStreamReader(input)).use { reader ->
            var headerSeen = false
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split(",").map(String::trim)
                if (!headerSeen) {
                    val header = parts.map(String::lowercase)
                    if (header !in listOf(listOf("relay url", "latitude", "longitude"), listOf("relay url", "lat", "lon"))) {
                        return emptyList()
                    }
                    headerSeen = true
                    return@forEach
                }
                if (parts.size != 3) return emptyList()
                val url = normalizeRelayUrl(parts[0]) ?: return emptyList()
                val lat = parts[1].toDoubleOrNull() ?: return emptyList()
                val lon = parts[2].toDoubleOrNull() ?: return emptyList()
                if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0) return emptyList()
                val entry = RelayInfo(url, lat, lon)
                if (entries[url]?.let { it != entry } == true) return emptyList()
                entries[url] = entry
            }
        }
        return entries.values.sortedBy { it.url }
    }

    private fun fileSha256Hex(file: File): String = try {
        FileInputStream(file).use { input ->
            streamSha256Hex(input)
        }
    } catch (_: Exception) { "error" }

    private fun streamSha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var read: Int
        while (true) {
            read = input.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
        val bytes = digest.digest()
        return bytes.joinToString("") { b ->
            val v = b.toInt() and 0xff
            val s = Integer.toHexString(v)
            if (s.length == 1) "0$s" else s
        }
    }
}
