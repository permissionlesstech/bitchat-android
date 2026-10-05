package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeTileSelectorTest {
    @Test
    fun wholeGlobeRequestsAllZoomTwoTiles() {
        val request = GlobeTileSelector.select(
            GlobeViewport(
                centerLat = 20.0,
                centerLon = 170.0,
                globeRadiusPx = 400f,
                widthPx = 1080,
                heightPx = 1920
            )
        )

        requireNotNull(request)
        assertEquals(2, request.detailZoom)
        assertEquals(16, request.detailTiles.size)
        assertEquals((0..3).toSet(), request.detailTiles.map { it.x }.toSet())
        assertEquals((0..3).toSet(), request.detailTiles.map { it.y }.toSet())
        assertTrue(request.priorityTiles.isNotEmpty())
        assertTrue(request.priorityTiles.all { it in request.detailTiles })
    }

    @Test
    fun nearFittedGlobeUsesOverviewDetailRegardlessOfDisplayDensity() {
        val request = GlobeTileSelector.select(
            GlobeViewport(
                centerLat = 20.0,
                centerLon = 0.0,
                globeRadiusPx = 1_000f,
                widthPx = 1_344,
                heightPx = 2_992
            )
        )

        requireNotNull(request)
        assertEquals(2, request.detailZoom)
        assertTrue(request.detailTiles.size <= GlobeTileSelector.MAX_VISIBLE_TILES)
    }

    @Test
    fun zoomedGlobeKeepsVisibleRequestsBoundedAcrossAntimeridian() {
        val request = GlobeTileSelector.select(
            GlobeViewport(
                centerLat = 35.0,
                centerLon = 179.8,
                globeRadiusPx = 18_000f,
                widthPx = 1080,
                heightPx = 1920
            )
        )

        requireNotNull(request)
        assertTrue(request.detailZoom in 2..GlobeTileSelector.MAX_TILE_ZOOM)
        assertTrue(request.detailTiles.size <= GlobeTileSelector.MAX_VISIBLE_TILES)
        val dimension = 1 shl request.detailZoom
        assertTrue(request.detailTiles.all { it.x in 0 until dimension })
        assertTrue(request.detailTiles.all { it.y in 0 until dimension })
        assertTrue(request.detailTiles.any { it.x == 0 })
        assertTrue(request.detailTiles.any { it.x == dimension - 1 })
    }

    @Test
    fun tileCoordinateRoundTripIsAccurate() {
        val zoom = 8
        val extent = 4096
        val tileX = GlobeTileSelector.longitudeToTileX(13.405, zoom)
        val tileY = GlobeTileSelector.latitudeToTileY(52.52, zoom)
        val dimension = 1 shl zoom
        val worldX = (13.405 + 180.0) / 360.0 * dimension
        val worldY = (
            1.0 - kotlin.math.ln(
                kotlin.math.tan(Math.toRadians(52.52)) +
                    1.0 / kotlin.math.cos(Math.toRadians(52.52))
            ) / Math.PI
            ) / 2.0 * dimension
        val localX = ((worldX - tileX) * extent).toInt()
        val localY = ((worldY - tileY) * extent).toInt()

        val longitude = GlobeTileSelector.tilePointToLongitude(
            tileX, localX, extent, zoom
        )
        val latitude = GlobeTileSelector.tilePointToLatitude(
            tileY, localY, extent, zoom
        )

        assertEquals(13.405, longitude, 0.001)
        assertEquals(52.52, latitude, 0.001)
    }
}
