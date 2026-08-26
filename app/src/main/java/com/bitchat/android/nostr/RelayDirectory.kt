package com.bitchat.android.nostr

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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
    private const val KEY_SOURCE_URL = "source_url"
    private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)

    // GeoRelayDirectoryValidationPolicy.live, ported verbatim. The directory is an
    // unsigned third-party file (docs/security-review-jul-27.md M9); iOS bounds what
    // it will accept from it and rejects the rest, and both platforms must bound it
    // the same way or a file one side accepts and the other rejects splits their
    // relay selections at every geohash at once.
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

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                val downloaded = getDownloadedFile(application)
                invalidateCacheIfSourceChanged(getPrefs(application), downloaded)
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

    // ===== Implementation details =====

    private fun getPrefs(application: Application): SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)

    /**
     * An install upgraded across the source move still holds a cache fetched from
     * the old URL. Drop it and clear the update stamp, and the staleness check
     * refetches, rather than keep selecting from a file the current source no
     * longer matches. Returns whether a cache was dropped.
     */
    internal fun invalidateCacheIfSourceChanged(prefs: SharedPreferences, downloaded: File): Boolean {
        if (!downloaded.exists()) return false
        if (prefs.getString(KEY_SOURCE_URL, null) == ASSET_FILE_URL) return false
        downloaded.delete()
        prefs.edit().remove(KEY_LAST_UPDATE_MS).apply()
        Log.i(TAG, "Dropped cached relay list fetched from a previous source URL")
        return true
    }

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

            if (tmpFile.length() > MAX_DIRECTORY_BYTES) {
                Log.w(TAG, "Downloaded relay CSV exceeds $MAX_DIRECTORY_BYTES bytes; keeping current list")
                tmpFile.delete()
                return
            }
            // The current directory is the baseline: a rejected download keeps it,
            // in memory and on disk, the way iOS keeps its previous copy.
            val baseline = synchronized(relaysLock) { relays.toSet() }
            val parsed = validatedEntries(tmpFile.readBytes(), MIN_REMOTE_ENTRIES, baseline)
            if (parsed == null) {
                Log.w(TAG, "Downloaded relay CSV failed validation; keeping current list")
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

            getPrefs(application).edit()
                .putLong(KEY_LAST_UPDATE_MS, System.currentTimeMillis())
                .putString(KEY_SOURCE_URL, ASSET_FILE_URL)
                .apply()

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
            "error:${'$'}{e.message}"
        }
        Log.i(TAG, "📦 Loaded ${list.size} relay entries from assets/$ASSET_FILE, sha256=$hash")
    }

    /**
     * GeoRelayDirectory.validatedEntries, ported rule for rule. One malformed or
     * conflicting row rejects the complete dataset, and the caller keeps whatever
     * directory it already has: a partial parse would leave this client selecting
     * from a different row set than iOS, the failure #914 exists to close. Returns
     * null when the data is rejected.
     *
     * baselineEntries carries the current directory when validating a download. A
     * new file that keeps less than half of the known entries is rejected even when
     * well formed, matching iOS: a hijacked or truncated upstream cannot swap the
     * whole relay population in one fetch.
     */
    internal fun validatedEntries(
        data: ByteArray,
        minimumEntries: Int,
        baselineEntries: Set<RelayInfo>? = null
    ): List<RelayInfo>? {
        if (data.isEmpty() || data.size > MAX_DIRECTORY_BYTES) return null
        var text = decodeUtf8Strict(data) ?: return null
        // Foundation's UTF-8 decode strips one leading BOM before iOS's own BOM
        // check runs, so a single BOM passes on iOS and only a doubled one is
        // rejected. Mirror that exactly (verified against the real Swift code).
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
            // One endpoint cannot truthfully occupy two coordinates. Matching iOS,
            // row order does not get to choose which location clients trust. The
            // comparison is IEEE equality, not equals(): Swift's == calls -0.0 and
            // 0.0 the same coordinate, and the last equal row's value is kept, raw
            // bits included, the way Swift dictionary assignment keeps it.
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
     * GeoRelayDirectory.validatedDirectoryAddress, ported rule for rule: the host
     * key both platforms build for a row, or null when the address is one the
     * directory must not carry (non-ASCII, credentials, paths, queries, local and
     * internal names, malformed labels, out-of-range ports). An explicit port stays
     * in the key unless it is 443, the wss default, which keeps a relay on :8443
     * distinct and collapses the directory's bare and :443 duplicate rows. Dedup
     * and tie ordering both key on this, which is what keeps the two platforms'
     * selections aligned row for row.
     */
    internal fun validatedDirectoryAddress(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.isEmpty()) return null
        if (!value.all { it.code in 0x20..0x7E }) return null

        val encoded = if ("://" in value) value else "wss://$value"
        // URLComponents percent-decodes before iOS's checks run, so re%6Cay.example
        // is relay.example to iOS; java.net.URI does not decode. Decode the same
        // way first, and reject invalid escapes the way URLComponents rejects them.
        val candidate = percentDecodedOrNull(encoded) ?: return null
        val uri = try { java.net.URI(candidate) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "wss" && scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.query != null) return null
        if (uri.fragment != null) return null
        val path = uri.path ?: ""
        if (path.isNotEmpty() && path != "/") return null
        // java.net.URI follows RFC 2396, which requires the final host label to
        // start with a letter, and returns no host for names like b.08obllot
        // that iOS's RFC 3986 parser accepts. When URI refuses only for that
        // reason, a plain hostname[:port] authority is taken as the host and
        // the screens below judge it; fuzzing found this as the dominant
        // divergence class (Android rejecting what iOS accepts).
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
        // URLComponents IDNA-decodes xn-- labels: valid punycode decodes to
        // non-ASCII and fails iOS's screen, invalid punycode fails its parse, and
        // both reject the address (verified against the real validator). java.net
        // URI passes the label through, so the label form itself is refused here.
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
    // null on an invalid or truncated escape, matching URLComponents.
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
                // URLComponents decodes AFTER structural parsing, so a decoded
                // ':' stays inside the host and fails iOS's label screen; decoding
                // it here first would instead create a port. Only escapes that
                // decode to host-legal characters may pass (verified against the
                // real validator: %6C and %2E accept, %3A and the rest reject).
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

    // Two Swift-vs-Java parsing differences, both verified against the real
    // validator: Double.parseDouble takes trailing f/F/d/D suffixes that Swift
    // rejects (and in hex those characters are digits, not suffixes), and Swift
    // accepts hex like "0x10" without the binary exponent Java requires.
    // Infinity and NaN spellings parse on both and fail the finite check.
    private fun parseCoordinate(raw: String): Double? {
        if (raw.isEmpty()) return null
        val body = raw.removePrefix("+").removePrefix("-")
        val isHex = body.startsWith("0x") || body.startsWith("0X")
        if (!isHex) {
            val last = raw.last()
            if (last == 'f' || last == 'F' || last == 'd' || last == 'D') return null
        }
        val candidate = if (isHex && !raw.contains('p') && !raw.contains('P')) raw + "p0" else raw
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
