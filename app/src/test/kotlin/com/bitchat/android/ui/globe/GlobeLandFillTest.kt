package com.bitchat.android.ui.globe

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the land fill's geographic inside test against the bundled Natural Earth land, so a
 * wide ring like Eurasia cannot claim an ocean (which inverted its fill on the globe).
 */
class GlobeLandFillTest {

    private val land: List<LandData.Ring> by lazy { loadLand() }

    private fun insideAnyRing(lat: Double, lon: Double) = land.count { ringContainsLocation(it, lat, lon) }

    @Test
    fun openOcean_isInsideNoLandRing() {
        val oceans = listOf(
            20.0 to -150.0, // central Pacific; its antipode runs through Africa and Europe
            0.0 to -140.0,
            -30.0 to -120.0,
            35.0 to 165.0, // northwest Pacific, just west of the antimeridian
            -40.0 to -170.0,
            30.0 to -40.0, // Atlantic
            0.0 to -25.0,
            -20.0 to 80.0 // Indian Ocean
        )
        for ((lat, lon) in oceans) {
            assertEquals("ocean at $lat,$lon", 0, insideAnyRing(lat, lon))
        }
    }

    @Test
    fun landmasses_areInsideExactlyOneRing() {
        val landPoints = listOf(
            50.0 to 10.0, // Europe
            60.0 to 100.0, // Siberia
            66.0 to 175.0, // Chukotka, beside the ±180° seam
            40.0 to -100.0, // North America
            -10.0 to -55.0, // South America
            -25.0 to 135.0, // Australia
            -80.0 to 0.0 // Antarctica
        )
        for ((lat, lon) in landPoints) {
            assertEquals("land at $lat,$lon", 1, insideAnyRing(lat, lon))
        }
    }

    @Test
    fun ringWrappingTheAntimeridian_hasNoFalseCrossingAtTheSeam() {
        // A band from 170°E across ±180° to 170°W, stored with raw ±180 longitudes.
        val ring = LandData.Ring(
            coords = floatArrayOf(
                -10f, 170f,
                -10f, -170f,
                10f, -170f,
                10f, 170f
            ),
            size = 4
        )

        assertTrue(ringContainsLocation(ring, 0.0, 180.0))
        assertTrue(ringContainsLocation(ring, 0.0, -175.0))
        assertFalse(ringContainsLocation(ring, 0.0, 0.0))
        assertFalse(ringContainsLocation(ring, 0.0, 160.0))
    }

    private fun loadLand(): List<LandData.Ring> {
        val root = JsonParser.parseString(File("src/main/assets/world_land.geojson").readText()).asJsonObject
        val rings = mutableListOf<LandData.Ring>()
        fun addPolygon(polygon: JsonArray) {
            for (ringJson in polygon) {
                val points = ringJson.asJsonArray
                val coords = FloatArray(points.size() * 2)
                points.forEachIndexed { index, point ->
                    coords[index * 2] = point.asJsonArray[1].asFloat
                    coords[index * 2 + 1] = point.asJsonArray[0].asFloat
                }
                rings += LandData.Ring(coords, points.size())
            }
        }
        for (geometry in root.getAsJsonArray("geometries")) {
            val obj = geometry.asJsonObject
            val coordinates = obj.getAsJsonArray("coordinates")
            when (obj.get("type").asString) {
                "Polygon" -> addPolygon(coordinates)
                "MultiPolygon" -> coordinates.forEach { addPolygon(it.asJsonArray) }
            }
        }
        return rings
    }
}
