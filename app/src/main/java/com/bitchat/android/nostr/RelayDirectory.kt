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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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

    // The same file iOS reads (GeoRelayDirectory.swift). Both platforms take the 5
    // nearest relays from their directory and use them without the defaults, so a
    // geohash message crosses platforms only if the two selections share a relay.
    // Reading different files made the selections diverge. Selecting from the same
    // file, with rows keyed and ordered the same way, keeps them aligned.
    internal const val ASSET_FILE_URL = "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv"
    // Bundled snapshot and download cache of ASSET_FILE_URL above; the file names
    // predate the source's move to online_relays_gps.csv.
    private const val ASSET_FILE = "nostr_relays.csv"
    private const val DOWNLOADED_FILE = "nostr_relays_latest.csv"
    private const val PREFS_NAME = "relay_directory_prefs"
    private const val KEY_LAST_UPDATE_MS = "last_update_ms"
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
        return closestRelays(snapshot, lat, lon, nRelays)
    }

    // Distance ties are the directory's common case, not an edge: rows are geocoded
    // to city centroids, so whole groups of relays sit at one exact coordinate. iOS
    // breaks ties by host so every device with the same directory picks the same set
    // (GeoRelayDirectory.closestRelays). Urls here are canonical hosts behind a fixed
    // prefix, so ordering by url reproduces iOS's order.
    internal fun closestRelays(entries: List<RelayInfo>, lat: Double, lon: Double, nRelays: Int): List<String> =
        entries
            .map { it to haversineMeters(lat, lon, it.latitude, it.longitude) }
            .sortedWith(compareBy({ it.second }, { it.first.url }))
            .take(nRelays.coerceAtLeast(0))
            .map { it.first.url }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun normalizeRelayUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return if ("://" in trimmed) trimmed else "wss://$trimmed"
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

            synchronized(relaysLock) {
                relays.clear()
                relays.addAll(parsed)
            }

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
                synchronized(relaysLock) {
                    relays.clear()
                    relays.addAll(list)
                }
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
        synchronized(relaysLock) {
            relays.clear()
            relays.addAll(list)
        }
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
        val result = mutableListOf<RelayInfo>()
        // The directory lists some relays twice, once bare and once with an explicit
        // :443, which is the same server over wss. Without this check both copies can
        // land in a nearest-N selection, and one of its slots connects nowhere new.
        val seenEndpoints = HashSet<String>()
        BufferedReader(InputStreamReader(input)).use { reader ->
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == null) break
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.lowercase().startsWith("relay url")) continue
                val parts = trimmed.split(",")
                if (parts.size < 3) continue
                val raw = normalizeRelayUrl(parts[0].trim())
                val lat = parts[1].trim().toDoubleOrNull()
                val lon = parts[2].trim().toDoubleOrNull()
                if (raw.isEmpty() || lat == null || lon == null) continue
                val canonical = canonicalHost(raw)
                if (canonical.isEmpty() || !seenEndpoints.add(canonical)) continue
                result.add(RelayInfo(url = "wss://$canonical", latitude = lat, longitude = lon))
            }
        }
        return result
    }

    /**
     * The host string iOS builds for the same row (GeoRelayDirectory's
     * validatedDirectoryAddress): host lowercased, an explicit port kept unless it is
     * 443, the wss default. A relay on :8443 stays distinct from one on :443. Dedup
     * and tie ordering both key on this, which is what keeps the two platforms'
     * selections aligned row for row.
     */
    internal fun canonicalHost(url: String): String {
        val hostPort = url.substringAfter("://").substringBefore("/")
        val idx = hostPort.lastIndexOf(':')
        val hasPort = idx > 0 && idx < hostPort.length - 1 &&
            hostPort.substring(idx + 1).all { it.isDigit() }
        val host = (if (hasPort) hostPort.substring(0, idx) else hostPort).lowercase()
        val port = if (hasPort) hostPort.substring(idx + 1).toInt() else 443
        return if (port == 443) host else "$host:$port"
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
