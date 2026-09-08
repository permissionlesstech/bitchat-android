package com.bitchat.android.ui.globe

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

data class GlobeViewport(
    val centerLat: Double,
    val centerLon: Double,
    val globeRadiusPx: Float,
    val widthPx: Int,
    val heightPx: Int
)

data class GlobeTileKey(val zoom: Int, val x: Int, val y: Int) {
    init {
        require(zoom in 0..GlobeTileSelector.MAX_TILE_ZOOM)
        val dimension = 1 shl zoom
        require(x in 0 until dimension)
        require(y in 0 until dimension)
    }
}

data class GlobeTileRequest(
    val detailZoom: Int,
    val detailTiles: Set<GlobeTileKey>,
    /**
     * The small center-first subset that should be decoded and displayed before the
     * remaining visible tiles.
     */
    val priorityTiles: Set<GlobeTileKey> = emptySet()
)

/**
 * Converts the visible portion of the custom orthographic globe into a bounded set of
 * Web-Mercator XYZ tiles. Sampling screen space avoids fragile latitude/longitude bounding
 * boxes around the poles and antimeridian.
 */
object GlobeTileSelector {
    const val MAX_TILE_ZOOM = 14
    private const val BASE_DETAIL_ZOOM = 2
    internal const val MAX_VISIBLE_TILES = 36
    private const val SAMPLE_COLUMNS = 7
    private const val SAMPLE_ROWS = 9
    private const val FITTED_GLOBE_RADIUS_FRACTION = 0.44f

    fun select(viewport: GlobeViewport): GlobeTileRequest? {
        if (
            viewport.globeRadiusPx <= 0f ||
            viewport.widthPx <= 0 ||
            viewport.heightPx <= 0
        ) {
            return null
        }

        // When the entire sphere fits on screen, z2 is enough for the current simplified
        // globe design and costs only sixteen small tiles for complete global coverage.
        if (
            viewport.globeRadiusPx * 2f <= viewport.widthPx &&
            viewport.globeRadiusPx * 2f <= viewport.heightPx
        ) {
            val tiles = allTilesAtZoom(BASE_DETAIL_ZOOM)
            return GlobeTileRequest(
                detailZoom = BASE_DETAIL_ZOOM,
                detailTiles = tiles,
                priorityTiles = priorityTiles(viewport, BASE_DETAIL_ZOOM, tiles)
            )
        }

        // Tile detail follows user zoom, not physical display density. The previous
        // circumference-based calculation requested z4 near the fitted view on a high-DPI
        // phone, even though z2 has more than enough geometry at that visual scale.
        val fittedRadius = min(viewport.widthPx, viewport.heightPx) *
            FITTED_GLOBE_RADIUS_FRACTION
        val zoomFactor = (viewport.globeRadiusPx / fittedRadius).coerceAtLeast(1f)
        val scaleZoom = floor(
            log2(zoomFactor.toDouble())
        ).toInt() + BASE_DETAIL_ZOOM
        var detailZoom = scaleZoom.coerceIn(BASE_DETAIL_ZOOM, MAX_TILE_ZOOM)
        var tiles = sampledTiles(viewport, detailZoom)

        while (tiles.size > MAX_VISIBLE_TILES && detailZoom > BASE_DETAIL_ZOOM) {
            detailZoom--
            tiles = sampledTiles(viewport, detailZoom)
        }

        return GlobeTileRequest(
            detailZoom = detailZoom,
            detailTiles = tiles,
            priorityTiles = priorityTiles(viewport, detailZoom, tiles)
        )
    }

    private fun sampledTiles(
        viewport: GlobeViewport,
        zoom: Int
    ): Set<GlobeTileKey> {
        val keys = linkedSetOf<GlobeTileKey>()
        val cx = viewport.widthPx / 2.0
        val cy = viewport.heightPx / 2.0
        val radius = viewport.globeRadiusPx.toDouble()

        for (row in 0 until SAMPLE_ROWS) {
            val screenY = viewport.heightPx * row.toDouble() / (SAMPLE_ROWS - 1)
            for (column in 0 until SAMPLE_COLUMNS) {
                val screenX = viewport.widthPx * column.toDouble() / (SAMPLE_COLUMNS - 1)
                val location = GlobeMath.unproject(
                    x = (screenX - cx) / radius,
                    y = (screenY - cy) / radius,
                    centerLatDeg = viewport.centerLat,
                    centerLonDeg = viewport.centerLon
                ) ?: continue
                addTileAndNeighbors(keys, zoom, location.first, location.second)
            }
        }

        addTileAndNeighbors(
            keys,
            zoom,
            viewport.centerLat,
            viewport.centerLon
        )
        return keys
    }

    private fun addTileAndNeighbors(
        output: MutableSet<GlobeTileKey>,
        zoom: Int,
        latitude: Double,
        longitude: Double
    ) {
        val dimension = 1 shl zoom
        val centerX = longitudeToTileX(longitude, zoom)
        val centerY = latitudeToTileY(latitude, zoom)
        for (dy in -1..1) {
            val y = centerY + dy
            if (y !in 0 until dimension) continue
            for (dx in -1..1) {
                val x = floorMod(centerX + dx, dimension)
                output.add(GlobeTileKey(zoom, x, y))
            }
        }
    }

    private fun priorityTiles(
        viewport: GlobeViewport,
        zoom: Int,
        visibleTiles: Set<GlobeTileKey>
    ): Set<GlobeTileKey> {
        val dimension = 1 shl zoom
        val centerX = longitudeToTileX(viewport.centerLon, zoom)
        val centerY = latitudeToTileY(viewport.centerLat, zoom)
        val offsets = arrayOf(
            0 to 0,
            -1 to 0,
            1 to 0,
            0 to -1,
            0 to 1
        )
        return buildSet(offsets.size) {
            for ((dx, dy) in offsets) {
                val y = centerY + dy
                if (y !in 0 until dimension) continue
                val key = GlobeTileKey(zoom, floorMod(centerX + dx, dimension), y)
                if (key in visibleTiles) add(key)
            }
        }
    }

    internal fun longitudeToTileX(longitude: Double, zoom: Int): Int {
        val dimension = 1 shl zoom
        val normalized = GlobeMath.normalizeLon(longitude)
        return floor((normalized + 180.0) / 360.0 * dimension)
            .toInt()
            .coerceIn(0, dimension - 1)
    }

    internal fun latitudeToTileY(latitude: Double, zoom: Int): Int {
        val dimension = 1 shl zoom
        val clamped = latitude.coerceIn(-WEB_MERCATOR_MAX_LAT, WEB_MERCATOR_MAX_LAT)
        val radians = Math.toRadians(clamped)
        val mercator = ln(tan(radians) + 1.0 / kotlin.math.cos(radians))
        return floor((1.0 - mercator / PI) / 2.0 * dimension)
            .toInt()
            .coerceIn(0, dimension - 1)
    }

    internal fun tilePointToLongitude(
        tileX: Int,
        localX: Int,
        extent: Int,
        zoom: Int
    ): Double {
        val dimension = (1 shl zoom).toDouble()
        return (tileX + localX.toDouble() / extent) / dimension * 360.0 - 180.0
    }

    internal fun tilePointToLatitude(
        tileY: Int,
        localY: Int,
        extent: Int,
        zoom: Int
    ): Double {
        val dimension = (1 shl zoom).toDouble()
        val worldY = (tileY + localY.toDouble() / extent) / dimension
        return Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY))))
    }

    private fun allTilesAtZoom(zoom: Int): Set<GlobeTileKey> {
        val dimension = 1 shl zoom
        return buildSet(dimension * dimension) {
            for (y in 0 until dimension) {
                for (x in 0 until dimension) {
                    add(GlobeTileKey(zoom, x, y))
                }
            }
        }
    }

    private fun floorMod(value: Int, modulus: Int): Int {
        val remainder = value % modulus
        return if (remainder < 0) remainder + modulus else remainder
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    private const val WEB_MERCATOR_MAX_LAT = 85.05112878
}
