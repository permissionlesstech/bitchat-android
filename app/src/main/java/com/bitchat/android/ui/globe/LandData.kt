package com.bitchat.android.ui.globe

import android.content.Context
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Loads the bundled Natural Earth 110m land polygons (public domain) from assets
 * and exposes them as flat rings of lat/lon pairs for vector globe rendering.
 */
object LandData {

    data class Ring(
        val coords: FloatArray,
        val size: Int,
        /**
         * Per-point sin(latitude), cos(latitude), sin(longitude), cos(longitude).
         * Preparing this once removes nearly all trigonometry from animated frames.
         */
        val projectionTerms: FloatArray = prepareProjectionTerms(coords, size)
    )

    data class City(
        val name: String,
        val lat: Float,
        val lon: Float,
        val rank: Int,
        val capital: Boolean,
        val megacity: Boolean,
        val projectionTerms: FloatArray = prepareProjectionTerms(
            floatArrayOf(lat, lon),
            size = 1
        )
    )

    @Volatile
    private var cached: List<Ring>? = null

    @Volatile
    private var cachedBorders: List<Ring>? = null

    @Volatile
    private var cachedCities: List<City>? = null

    /** Returns land polygon rings; each ring is a flat array of (lat, lon) pairs. */
    fun load(context: Context): List<Ring> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val rings = mutableListOf<Ring>()
            val text = context.assets.open("world_land.geojson").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val geometries = root.getJSONArray("geometries")
            for (i in 0 until geometries.length()) {
                val geom = geometries.getJSONObject(i)
                when (geom.getString("type")) {
                    "Polygon" -> parsePolygon(geom.getJSONArray("coordinates"), rings)
                    "MultiPolygon" -> {
                        val polys = geom.getJSONArray("coordinates")
                        for (j in 0 until polys.length()) {
                            parsePolygon(polys.getJSONArray(j), rings)
                        }
                    }
                }
            }
            cached = rings
            return rings
        }
    }

    /** Returns country border lines (Natural Earth admin-0 boundary lines, public domain). */
    fun loadBorders(context: Context): List<Ring> {
        cachedBorders?.let { return it }
        synchronized(this) {
            cachedBorders?.let { return it }
            val lines = mutableListOf<Ring>()
            val text = context.assets.open("world_borders.geojson").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val geometries = root.getJSONArray("geometries")
            for (i in 0 until geometries.length()) {
                val geom = geometries.getJSONObject(i)
                when (geom.getString("type")) {
                    "LineString" -> parseLine(geom.getJSONArray("coordinates"), lines)
                    "MultiLineString" -> {
                        val parts = geom.getJSONArray("coordinates")
                        for (j in 0 until parts.length()) {
                            parseLine(parts.getJSONArray(j), lines)
                        }
                    }
                }
            }
            cachedBorders = lines
            return lines
        }
    }

    /** Returns populated places (Natural Earth 50m, public domain) with name and scale rank. */
    fun loadCities(context: Context): List<City> {
        cachedCities?.let { return it }
        synchronized(this) {
            cachedCities?.let { return it }
            val cities = mutableListOf<City>()
            val text = context.assets.open("world_cities.geojson").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val arr = root.getJSONArray("cities")
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                cities.add(
                    City(
                        name = c.getString("n"),
                        lat = c.getDouble("lat").toFloat(),
                        lon = c.getDouble("lon").toFloat(),
                        rank = c.getInt("r"),
                        capital = c.optInt("cap", 0) == 1,
                        megacity = c.optInt("mega", 0) == 1
                    )
                )
            }
            cachedCities = cities
            return cities
        }
    }

    private fun parseLine(lineJson: org.json.JSONArray, out: MutableList<Ring>) {
        val n = lineJson.length()
        if (n < 2) return
        val coords = FloatArray(n * 2)
        for (p in 0 until n) {
            val pt = lineJson.getJSONArray(p)
            coords[p * 2] = pt.getDouble(1).toFloat() // lat
            coords[p * 2 + 1] = pt.getDouble(0).toFloat() // lon
        }
        out.add(Ring(coords, n))
    }

    private fun parsePolygon(ringsJson: org.json.JSONArray, out: MutableList<Ring>) {
        for (r in 0 until ringsJson.length()) {
            val ringJson = ringsJson.getJSONArray(r)
            val n = ringJson.length()
            if (n < 3) continue
            val coords = FloatArray(n * 2)
            for (p in 0 until n) {
                val pt = ringJson.getJSONArray(p)
                coords[p * 2] = pt.getDouble(1).toFloat() // lat
                coords[p * 2 + 1] = pt.getDouble(0).toFloat() // lon
            }
            out.add(Ring(coords, n))
        }
    }

    private fun prepareProjectionTerms(coords: FloatArray, size: Int): FloatArray {
        val result = FloatArray(size * 4)
        for (index in 0 until size) {
            val latRadians = Math.toRadians(coords[index * 2].toDouble())
            val lonRadians = Math.toRadians(coords[index * 2 + 1].toDouble())
            result[index * 4] = sin(latRadians).toFloat()
            result[index * 4 + 1] = cos(latRadians).toFloat()
            result[index * 4 + 2] = sin(lonRadians).toFloat()
            result[index * 4 + 3] = cos(lonRadians).toFloat()
        }
        return result
    }
}
