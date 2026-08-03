package com.bitchat.android.ui.globe

import android.content.Context
import com.bitchat.android.BuildConfig
import com.bitchat.android.net.OkHttpProvider
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streams only the visible Shortbread vector tiles from OpenStreetMap.
 *
 * The disk cache follows server cache headers (with a seven-day fallback), while the small
 * decoded LRU prevents repeated protobuf work during globe rotation. No map dataset is
 * bundled with or permanently stored by the app.
 */
internal class StreamedGlobeRepository(
    context: Context,
    private val tileUrl: (GlobeTileKey) -> String = { tile ->
        "$DEFAULT_TILE_ROOT/${tile.zoom}/${tile.x}/${tile.y}.mvt"
    },
    private val decoder: MvtDecoder = MvtDecoder()
) : Closeable {
    private val tileCache = Cache(
        directory = context.cacheDir.resolve(CACHE_DIRECTORY),
        maxSize = DISK_CACHE_BYTES
    )
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)
    private val decodeSemaphore = Semaphore(MAX_CONCURRENT_DECODES)
    private val decodedTiles = object :
        LinkedHashMap<GlobeTileKey, DecodedGlobeTile>(
            MEMORY_CACHE_ENTRIES,
            0.75f,
            true
        ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<GlobeTileKey, DecodedGlobeTile>?
        ): Boolean = size > MEMORY_CACHE_ENTRIES
    }

    suspend fun load(
        request: GlobeTileRequest,
        onProgress: suspend (GlobeMapLoadResult) -> Unit = {}
    ): GlobeMapLoadResult {
        val priorityTiles = buildList {
            add(GLOBAL_OCEAN_TILE)
            request.priorityTiles
                .asSequence()
                .filterNot { it == GLOBAL_OCEAN_TILE }
                .forEach(::add)
        }
        val remainingTiles = request.detailTiles
            .asSequence()
            .filterNot { it == GLOBAL_OCEAN_TILE || it in request.priorityTiles }
            .sortedWith(compareBy(GlobeTileKey::zoom, GlobeTileKey::y, GlobeTileKey::x))
            .toList()
        val client = currentTileClient()
        val outcomes = ArrayList<TileOutcome>(priorityTiles.size + remainingTiles.size)
        outcomes += loadTiles(client, priorityTiles)

        if (remainingTiles.isNotEmpty()) {
            onProgress(assembleResult(request, outcomes))
            outcomes += loadTiles(client, remainingTiles)
        }
        return assembleResult(request, outcomes)
    }

    private suspend fun loadTiles(
        client: OkHttpClient,
        tiles: List<GlobeTileKey>
    ): List<TileOutcome> = coroutineScope {
        tiles.map { tile ->
            async {
                try {
                    TileOutcome(tile, fetchTile(client, tile), null)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    TileOutcome(tile, null, error)
                }
            }
        }.awaitAll()
    }

    private suspend fun assembleResult(
        request: GlobeTileRequest,
        outcomes: List<TileOutcome>
    ): GlobeMapLoadResult = withContext(Dispatchers.Default) {
        val successful = outcomes.filter { it.decoded != null }
        if (successful.isEmpty()) {
            throw IOException("OpenStreetMap vector tiles are unavailable")
        }

        val globalOcean = successful
            .firstOrNull { it.tile == GLOBAL_OCEAN_TILE }
            ?.decoded
            ?.oceanPolygons
            .orEmpty()
        if (globalOcean.isEmpty()) {
            throw IOException("The global OpenStreetMap ocean layer is unavailable")
        }
        val detailTiles = successful.filter { it.tile != GLOBAL_OCEAN_TILE }
        val boundaryLabels = deduplicateLabels(
            detailTiles.flatMap { it.decoded?.boundaryLabels.orEmpty() }
        )
        val placeLabels = deduplicateLabels(
            detailTiles.flatMap { it.decoded?.placeLabels.orEmpty() }
        )

        GlobeMapLoadResult(
            data = GlobeMapData(
                oceanPolygons = globalOcean,
                borders = detailTiles.flatMap { it.decoded?.borders.orEmpty() },
                boundaryLabels = boundaryLabels,
                placeLabels = placeLabels,
                detailZoom = request.detailZoom
            ),
            requestedTileCount = outcomes.size,
            failedTileCount = outcomes.count { it.error != null }
        )
    }

    private fun currentTileClient(): OkHttpClient {
        return OkHttpProvider.httpClient()
            .newBuilder()
            .cache(tileCache)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.header("Cache-Control") != null) {
                    response
                } else {
                    response.newBuilder()
                        .header("Cache-Control", "public, max-age=$FALLBACK_CACHE_SECONDS")
                        .build()
                }
            }
            .build()
    }

    private suspend fun fetchTile(
        client: OkHttpClient,
        tile: GlobeTileKey
    ): DecodedGlobeTile {
        synchronized(decodedTiles) {
            decodedTiles[tile]?.let { return it }
        }

        val bytes = fetchSemaphore.withPermit {
            runInterruptible(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(tileUrl(tile))
                    .header("Accept", VECTOR_TILE_MEDIA_TYPE)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            "OpenStreetMap tile request failed (${response.code})"
                        )
                    }
                    response.body.readBytesWithLimit(MvtDecoder.MAX_TILE_BYTES)
                }
            }
        }
        val decoded = decodeSemaphore.withPermit {
            withContext(Dispatchers.Default) {
                decoder.decode(bytes, tile)
            }
        }
        synchronized(decodedTiles) {
            decodedTiles[tile] = decoded
        }
        return decoded
    }

    override fun close() {
        tileCache.close()
        synchronized(decodedTiles) {
            decodedTiles.clear()
        }
    }

    private fun okhttp3.ResponseBody.readBytesWithLimit(maximumBytes: Int): ByteArray {
        contentLength().takeIf { it >= 0L }?.let { length ->
            if (length > maximumBytes) {
                throw IOException("OpenStreetMap tile response is too large")
            }
        }
        byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximumBytes) {
                    throw IOException("OpenStreetMap tile response is too large")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun deduplicateLabels(labels: List<MapLabel>): List<MapLabel> {
        return labels
            .sortedWith(
                compareBy<MapLabel> { it.rank }
                    .thenByDescending { it.importance }
                    .thenBy { it.name }
            )
            .distinctBy { label ->
                LabelIdentity(
                    name = label.name,
                    roundedLatitude = (label.lat * LABEL_DEDUPLICATION_SCALE).toInt(),
                    roundedLongitude = (label.lon * LABEL_DEDUPLICATION_SCALE).toInt()
                )
            }
    }

    private data class TileOutcome(
        val tile: GlobeTileKey,
        val decoded: DecodedGlobeTile?,
        val error: Exception?
    )

    private data class LabelIdentity(
        val name: String,
        val roundedLatitude: Int,
        val roundedLongitude: Int
    )

    companion object {
        private const val DEFAULT_TILE_ROOT =
            "https://vector.openstreetmap.org/shortbread_v1"
        private const val CACHE_DIRECTORY = "openstreetmap_globe_tiles"
        private const val DISK_CACHE_BYTES = 32L * 1024L * 1024L
        private const val MEMORY_CACHE_ENTRIES = 64
        private const val MAX_CONCURRENT_FETCHES = 4
        private const val MAX_CONCURRENT_DECODES = 2
        private const val FALLBACK_CACHE_SECONDS = 7L * 24L * 60L * 60L
        private const val LABEL_DEDUPLICATION_SCALE = 100
        private const val VECTOR_TILE_MEDIA_TYPE = "application/vnd.mapbox-vector-tile"
        private val GLOBAL_OCEAN_TILE = GlobeTileKey(0, 0, 0)
        private val USER_AGENT =
            "Bitchat-Android/${BuildConfig.VERSION_NAME} " +
                "(+https://github.com/permissionlesstech/bitchat-android)"
    }
}
