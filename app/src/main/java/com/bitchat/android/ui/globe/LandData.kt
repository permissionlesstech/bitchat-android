package com.bitchat.android.ui.globe

import android.content.Context
import org.json.JSONObject

/**
 * Loads the bundled Natural Earth 110m land polygons (public domain) from assets
 * and exposes them as flat rings of lat/lon pairs for vector globe rendering.
 */
object LandData {

    data class Ring(val coords: FloatArray, val size: Int)

    @Volatile
    private var cached: List<Ring>? = null

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
}
