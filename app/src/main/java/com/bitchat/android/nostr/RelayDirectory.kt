package com.bitchat.android.nostr

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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

    // The source URL matches iOS GeoRelayDirectory. Download timing and parser
    // acceptance can leave clients with different loaded snapshots.
    internal const val ASSET_FILE_URL = "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv"
    // Download cache of ASSET_FILE_URL above, and the bundled list the weekly job
    // refreshes from it; the file names predate the source's move to online_relays_gps.csv.
    private const val ASSET_FILE = "nostr_relays.csv"
    private const val DOWNLOADED_FILE = "nostr_relays_latest.csv"
    private const val PREFS_NAME = "relay_directory_prefs"
    private const val KEY_LAST_UPDATE_MS = "last_update_ms"
    private const val KEY_SOURCE_URL = "source_url"
    private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)

    // Limits follow GeoRelayDirectoryValidationPolicy.live. The directory is unsigned;
    // these checks bound accepted data without authenticating it.
    internal const val MAX_DIRECTORY_BYTES = 512 * 1024
    internal const val MAX_DIRECTORY_ROWS = 5_000
    internal const val MAX_DIRECTORY_ENTRIES = 5_000
    internal const val MIN_REMOTE_ENTRIES = 50
    internal const val MIN_RETAINED_FRACTION = 0.5

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

    fun initialize(application: Application) =
        initialize(application, ioScope, System::currentTimeMillis)

    internal fun initialize(
        application: Application,
        refreshScope: CoroutineScope,
        nowMillis: () -> Long
    ) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                loadInitialDirectory(application, getDownloadedFile(application), getPrefs(application))

                initialized = true

                // One worker checks immediately, then waits a minute after each iteration.
                startPeriodicRefresh(application, refreshScope, nowMillis)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize RelayDirectory: ${e.message}")
            }
        }
    }

    internal fun loadInitialDirectory(application: Application, downloaded: File, prefs: SharedPreferences) {
        val sourceChanged = invalidateCacheIfSourceChanged(prefs, downloaded)
        val loadedFromDownloaded = if (!sourceChanged && downloaded.exists() && downloaded.canRead()) {
            loadFromFile(downloaded, sourceLabel = "downloaded")
        } else {
            false
        }

        if (!loadedFromDownloaded) {
            loadFromAssets(application)
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

    // City-level coordinates create distance ties. Break them by endpoint key,
    // following GeoRelayDirectory.closestRelays for the accepted directory rows.
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

    // ===== Implementation details =====

    private fun getPrefs(application: Application): SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)

    /**
     * Returns true when an existing cache has a missing or different source URL.
     * That cache is ineligible for loading when deletion returns false. Clearing
     * the timestamp makes the next staleness check eligible to attempt a fetch.
     */
    internal fun invalidateCacheIfSourceChanged(prefs: SharedPreferences, downloaded: File): Boolean {
        if (!downloaded.exists()) return false
        if (prefs.getString(KEY_SOURCE_URL, null) == ASSET_FILE_URL) return false
        val deleted = downloaded.delete()
        prefs.edit { remove(KEY_LAST_UPDATE_MS) }
        Log.i(TAG, "Invalidated cached relay list from a different or unknown source; deleted=$deleted")
        return true
    }

    private fun getDownloadedFile(application: Application): File =
        File(application.filesDir, DOWNLOADED_FILE)

    private fun isStale(application: Application, nowMillis: () -> Long): Boolean {
        val last = getPrefs(application).getLong(KEY_LAST_UPDATE_MS, 0L)
        val now = nowMillis()
        return now - last >= ONE_DAY_MS
    }

    private fun startPeriodicRefresh(
        application: Application,
        refreshScope: CoroutineScope,
        nowMillis: () -> Long
    ) {
        refreshScope.launch {
            while (true) {
                try {
                    if (isStale(application, nowMillis)) {
                        fetchAndMaybeSwap(application, nowMillis)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic refresh encountered an error: ${e.message}")
                }
                delay(TimeUnit.MINUTES.toMillis(1))
            }
        }
    }

    private fun fetchAndMaybeSwap(application: Application, nowMillis: () -> Long) {
        var temporaryDownload: File? = null
        try {
            val tmpFile = File.createTempFile("relays_", ".csv", application.cacheDir)
            temporaryDownload = tmpFile
            val ok = downloadToFile(ASSET_FILE_URL, tmpFile)
            if (!ok) {
                Log.w(TAG, "Failed to fetch latest relays; keeping current list")
                return
            }

            if (tmpFile.length() > MAX_DIRECTORY_BYTES) {
                Log.w(TAG, "Downloaded relay CSV exceeds $MAX_DIRECTORY_BYTES bytes; keeping current list")
                tmpFile.delete()
                return
            }
            // Validation rejection returns before updating loaded entries, the cache
            // file or the update timestamp.
            val baseline = synchronized(relaysLock) { relays.toSet() }
            val parsed = validatedEntries(tmpFile.readBytes(), MIN_REMOTE_ENTRIES, baseline)
            if (parsed == null) {
                Log.w(TAG, "Downloaded relay CSV failed validation; keeping current list")
                tmpFile.delete()
                return
            }

            val dest = getDownloadedFile(application)
            tmpFile.inputStream().use { input ->
                RelayDirectoryCache.replace(input, dest)
            }

            val hash = fileSha256Hex(dest)
            val entries = parsed.size

            synchronized(relaysLock) {
                relays.clear()
                relays.addAll(parsed)
            }

            getPrefs(application).edit {
                putLong(KEY_LAST_UPDATE_MS, nowMillis())
                putString(KEY_SOURCE_URL, ASSET_FILE_URL)
            }

            Log.i(TAG, "✅ Using downloaded relay list (${dest.absolutePath}), entries=$entries, sha256=$hash, updatedAtMs=${getPrefs(application).getLong(KEY_LAST_UPDATE_MS, 0L)}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch and swap relay list: ${e.message}")
        } finally {
            temporaryDownload?.let { file ->
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Could not remove temporary relay download")
                }
            }
        }
    }

    private fun downloadToFile(url: String, dest: File): Boolean {
        return try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code} when fetching $url")
                    return false
                }
                val body = resp.body
                if (body.contentLength() > MAX_DIRECTORY_BYTES) {
                    Log.w(TAG, "Relay CSV content length exceeds $MAX_DIRECTORY_BYTES bytes; aborting")
                    return false
                }
                FileOutputStream(dest).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_DIRECTORY_BYTES.toLong()) {
                                Log.w(TAG, "Relay CSV download exceeded $MAX_DIRECTORY_BYTES bytes; aborting")
                                return false
                            }
                            out.write(buf, 0, read)
                        }
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
            val list = validatedEntries(file.readBytes(), minimumEntries = 1)
            if (list == null) {
                Log.w(TAG, "${sourceLabel} relay CSV failed validation; ignoring it")
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
            val bytes = application.assets.open(ASSET_FILE).use { it.readBytes() }
            validatedEntries(bytes, minimumEntries = 1) ?: run {
                Log.e(TAG, "Bundled asset $ASSET_FILE failed validation")
                emptyList()
            }
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
            "error:${e.message}"
        }
        Log.i(TAG, "📦 Loaded ${list.size} relay entries from assets/$ASSET_FILE, sha256=$hash")
    }

    /**
     * Validates a complete directory before it replaces loaded entries. Null
     * means rejection. Downloads also check retention against the loaded rows;
     * bundled and cached startup loads use a one-entry floor without a baseline.
     */
    internal fun validatedEntries(
        data: ByteArray,
        minimumEntries: Int,
        baselineEntries: Set<RelayInfo>? = null
    ): List<RelayInfo>? {
        if (data.isEmpty() || data.size > MAX_DIRECTORY_BYTES) return null
        var text = decodeUtf8Strict(data) ?: return null
        // Kotlin trim treats these separators as whitespace; do not erase invalid CSV padding.
        if (text.any { it in '\u001C'..'\u001F' }) return null
        // Accept one UTF-8 BOM, as in the tested Foundation decoder behavior.
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        if (text.startsWith('\uFEFF')) return null

        val lines = text
            .split('\u000A', '\u000B', '\u000C', '\u000D', '\u0085', '\u2028', '\u2029')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val header = lines.firstOrNull() ?: return null
        if (lines.size - 1 > MAX_DIRECTORY_ROWS) return null

        val headerParts = header.split(",").map { it.trim().lowercase() }
        val supportedHeaders = listOf(
            listOf("relay url", "latitude", "longitude"),
            listOf("relay url", "lat", "lon")
        )
        if (headerParts !in supportedHeaders) return null

        val entriesByHost = LinkedHashMap<String, RelayInfo>()
        for (line in lines.drop(1)) {
            val parts = line.split(",").map { it.trim() }
            if (parts.size != 3) return null
            val host = validatedDirectoryAddress(parts[0]) ?: return null
            val latitude = parseCoordinate(parts[1]) ?: return null
            if (latitude !in -90.0..90.0) return null
            val longitude = parseCoordinate(parts[2]) ?: return null
            if (longitude !in -180.0..180.0) return null

            val entry = RelayInfo(url = "wss://$host", latitude = latitude, longitude = longitude)
            val existing = entriesByHost[host]
            // Conflicting coordinates reject the file. IEEE equality treats
            // signed zero as equal; the later equal row supplies the stored bits.
            if (existing != null && !sameEntry(existing, entry)) {
                return null
            }
            entriesByHost[host] = entry
            if (entriesByHost.size > MAX_DIRECTORY_ENTRIES) return null
        }

        val parsed = entriesByHost.values.toList()
        if (parsed.size < minimumEntries) return null

        if (baselineEntries != null) {
            val required = ceil(baselineEntries.size * MIN_RETAINED_FRACTION).toInt()
            val overlap = parsed.count { p -> baselineEntries.any { b -> sameEntry(p, b) } }
            if (overlap < required) return null
        }

        return parsed.sortedWith(compareBy({ it.url }, { it.latitude }, { it.longitude }))
    }

    /**
     * Screens directory addresses and returns the lowercase host key. Port 443
     * is omitted; other accepted ports remain part of the key. This bounded
     * compatibility implementation does not completely reproduce Foundation.
     */
    internal fun validatedDirectoryAddress(rawValue: String): String? {
        if (rawValue.any { it in '\u001C'..'\u001F' }) return null
        val value = rawValue.trim()
        if (value.isEmpty()) return null
        if (!value.all { it.code in 0x20..0x7E }) return null

        val encoded = if ("://" in value) {
            val literalScheme = value.substringBefore("://").lowercase()
            if (literalScheme != "wss" && literalScheme != "https") return null
            value
        } else {
            "wss://$value"
        }
        val authority = encoded.substringAfter("://").substringBefore('/')
            .substringBefore('?').substringBefore('#')
        // Port digits must be literal; decoding them would admit encoded ports.
        if (authority.substringAfterLast(':', "").contains('%')) return null
        // Decode allowed host escapes after checking the literal scheme.
        // Encoded delimiters are rejected to avoid changing the URL structure.
        val candidate = percentDecodedOrNull(encoded) ?: return null
        val uri = try { java.net.URI(candidate) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "wss" && scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.query != null) return null
        if (uri.fragment != null) return null
        val path = uri.path ?: ""
        if (path.isNotEmpty() && path != "/") return null
        // URI does not expose a host for some accepted plain authorities, such
        // as final labels starting with a digit. Apply our host/port screens to
        // that restricted authority form too.
        val rawHost = uri.host
            ?: plainAuthorityHost(candidate)
            ?: return null

        val host = rawHost.lowercase()
        if (host.isEmpty() || host.length > 253) return null
        if (!host.all { it.code <= 0x7F }) return null
        if (host.endsWith(".")) return null
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")) return null

        val labels = host.split(".")
        if (labels.size < 2) return null
        // Reject the punycode label form without IDNA conversion. This is
        // stricter than Foundation for some unusual xn-- spellings.
        if (labels.any { it.startsWith("xn--") }) return null
        if (labels.all { label -> label.all { it.isDigit() } }) return null
        if (!labels.all { label ->
                label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }
        ) return null

        val port = if (uri.host != null) uri.port else plainAuthorityPort(candidate)
        if (port != -1) {
            if (port !in 1..65535) return null
            if (port != 443) return "$host:$port"
        }
        return host
    }


    // The authority of the candidate when it is nothing but hostname[:port] in
    // the host charset. Anything with userinfo, brackets, escapes, or other
    // structure stays with java.net.URI's verdict.
    private fun plainAuthority(candidate: String): Pair<String, Int>? {
        val afterScheme = candidate.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.length != afterScheme.length) {
            val rest = afterScheme.substring(authority.length)
            if (rest != "/") return null
        }
        val colon = authority.lastIndexOf(':')
        val hostPart: String
        val port: Int
        if (colon >= 0) {
            val portPart = authority.substring(colon + 1)
            // RFC 3986 allows an empty port ("host:"), and Foundation treats it
            // as no port at all.
            if (portPart.isNotEmpty() && !portPart.all { it in '0'..'9' }) return null
            hostPart = authority.substring(0, colon)
            port = if (portPart.isEmpty()) -1 else portPart.toIntOrNull() ?: return null
        } else {
            hostPart = authority
            port = -1
        }
        if (hostPart.isEmpty()) return null
        if (!hostPart.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }) return null
        return hostPart to port
    }

    private fun plainAuthorityHost(candidate: String): String? = plainAuthority(candidate)?.first

    private fun plainAuthorityPort(candidate: String): Int = plainAuthority(candidate)?.second ?: -1

    // IEEE equality on the coordinates: -0.0 equals 0.0 here, as it does in the
    // Swift Entry's ==, where equals() would call them different.
    private fun sameEntry(a: RelayInfo, b: RelayInfo): Boolean =
        a.url == b.url && a.latitude == b.latitude && a.longitude == b.longitude

    // Strict %XX decoding with UTF-8 byte semantics and no '+' handling. Returns
    // null on an invalid or truncated escape.
    private fun percentDecodedOrNull(value: String): String? {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                val decoded = ((hi shl 4) or lo).toChar()
                // Decoding a delimiter before URI parsing could create a port,
                // path or other structure that was not literal in the input.
                if (!(decoded in 'A'..'Z' || decoded in 'a'..'z' ||
                        decoded in '0'..'9' || decoded == '.' || decoded == '-')) return null
                bytes.write(decoded.code)
                i += 3
            } else {
                bytes.write(c.code)
                i += 1
            }
        }
        return decodeUtf8Strict(bytes.toByteArray())
    }

    // iOS String(data:encoding:.utf8) fails on invalid UTF-8 where Kotlin's
    // String(bytes) substitutes replacement characters. Decode strictly so both
    // platforms reject the same bytes.
    private fun decodeUtf8Strict(data: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(data))
            .toString()
    } catch (_: Exception) {
        null
    }

    // Java accepts type suffixes after decimal numbers and hex exponents.
    // Without an exponent, d/f can be hex mantissa digits. Append the exponent
    // Java requires for unsuffixed forms such as 0x10, accepted by Swift.
    private fun parseCoordinate(raw: String): Double? {
        if (raw.isEmpty()) return null
        val body = raw.removePrefix("+").removePrefix("-")
        val isHex = body.startsWith("0x") || body.startsWith("0X")
        val hasExponent = raw.contains('p') || raw.contains('P')
        if (!isHex || hasExponent) {
            val last = raw.last()
            if (last == 'f' || last == 'F' || last == 'd' || last == 'D') return null
        }
        val candidate = if (isHex && !hasExponent) raw + "p0" else raw
        val value = candidate.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
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
