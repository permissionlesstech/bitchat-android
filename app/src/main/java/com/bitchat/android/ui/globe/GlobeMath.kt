package com.bitchat.android.ui.globe

import kotlin.math.*

/**
 * Orthographic globe projection and geohash zoom math for the 3D globe picker.
 *
 * The globe is rendered as a disc of radius R centered on screen. Points are
 * projected with an orthographic projection around a view center lat/lon.
 */
object GlobeMath {

    data class Projection(val x: Float, val y: Float, val cosC: Float)

    /**
     * Frame-local projector for prepared static geometry. Center terms are calculated once
     * and projection results are written to a caller-owned array without allocating objects.
     */
    class PreparedProjector(centerLatDeg: Double, centerLonDeg: Double) {
        private val sinCenterLat = sin(Math.toRadians(centerLatDeg)).toFloat()
        private val cosCenterLat = cos(Math.toRadians(centerLatDeg)).toFloat()
        private val sinCenterLon = sin(Math.toRadians(centerLonDeg)).toFloat()
        private val cosCenterLon = cos(Math.toRadians(centerLonDeg)).toFloat()

        fun project(terms: FloatArray, termOffset: Int, out: FloatArray, outOffset: Int) {
            val sinLat = terms[termOffset]
            val cosLat = terms[termOffset + 1]
            val sinLon = terms[termOffset + 2]
            val cosLon = terms[termOffset + 3]
            val sinDeltaLon = sinLon * cosCenterLon - cosLon * sinCenterLon
            val cosDeltaLon = cosLon * cosCenterLon + sinLon * sinCenterLon
            out[outOffset] = cosLat * sinDeltaLon
            out[outOffset + 1] = -(
                cosCenterLat * sinLat -
                    sinCenterLat * cosLat * cosDeltaLon
                )
            out[outOffset + 2] =
                sinCenterLat * sinLat + cosCenterLat * cosLat * cosDeltaLon
        }
    }

    /**
     * Projects (lat, lon) onto the view disc of a globe centered at (centerLat, centerLon).
     * Returns x/y in units of globe radius (screen y down). [Projection.cosC] is negative
     * when the point is on the far side of the sphere.
     */
    fun projectRaw(latDeg: Double, lonDeg: Double, centerLatDeg: Double, centerLonDeg: Double): Projection {
        val phi = Math.toRadians(latDeg)
        val phi0 = Math.toRadians(centerLatDeg)
        val dLambda = Math.toRadians(normalizeLon(lonDeg - centerLonDeg))
        val cosC = sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dLambda)
        val x = cos(phi) * sin(dLambda)
        val y = -(cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dLambda))
        return Projection(x.toFloat(), y.toFloat(), cosC.toFloat())
    }

    /** Like [projectRaw] but null when the point is behind the limb. */
    fun project(latDeg: Double, lonDeg: Double, centerLatDeg: Double, centerLonDeg: Double): Projection? {
        val p = projectRaw(latDeg, lonDeg, centerLatDeg, centerLonDeg)
        return if (p.cosC >= 0f) p else null
    }

    /**
     * Inverse projection: disc coordinates (units of radius, screen y down) back to lat/lon.
     * Returns null if the point lies outside the disc.
     */
    fun unproject(x: Double, y: Double, centerLatDeg: Double, centerLonDeg: Double): Pair<Double, Double>? {
        val rho = sqrt(x * x + y * y)
        if (rho > 1.0) return null
        val c = asin(rho.coerceIn(-1.0, 1.0))
        val phi0 = Math.toRadians(centerLatDeg)
        val sinC = sin(c)
        val cosC = cos(c)
        val lat: Double
        val lonOffset: Double
        if (rho < 1e-9) {
            lat = centerLatDeg
            lonOffset = 0.0
        } else {
            lat = Math.toDegrees(asin(cosC * sin(phi0) + (-y) * sinC * cos(phi0) / rho))
            lonOffset = Math.toDegrees(atan2(x * sinC, rho * cos(phi0) * cosC - (-y) * sin(phi0) * sinC))
        }
        return lat to normalizeLon(centerLonDeg + lonOffset)
    }

    fun normalizeLon(lon: Double): Double {
        var x = lon % 360.0
        if (x > 180.0) x -= 360.0
        if (x < -180.0) x += 360.0
        return x
    }

    /** Longitude span of a geohash cell in degrees. */
    fun cellSpanLon(precision: Int): Double = 360.0 / 2.0.pow(ceil(5.0 * precision / 2.0))

    /** Latitude span of a geohash cell in degrees. */
    fun cellSpanLat(precision: Int): Double = 180.0 / 2.0.pow(floor(5.0 * precision / 2.0))

    /**
     * Picks the geohash precision whose cells render at a comfortable on-screen size
     * for the current zoom: the largest precision whose cell is at least ~22% of the
     * screen's smallest dimension.
     */
    fun autoPrecision(globeRadiusPx: Float, screenMinPx: Float): Int {
        val targetPx = screenMinPx * 0.22f
        var best = 1
        for (p in 1..MAX_PRECISION) {
            val spanPx = (cellSpanLat(p) * (Math.PI / 180.0) * globeRadiusPx).toFloat()
            if (spanPx >= targetPx) best = p else break
        }
        return best.coerceIn(1, MAX_PRECISION)
    }

    /**
     * Zoom factor (globe radius multiplier over the fit-to-screen base radius) that frames
     * a cell of the given precision nicely: the cell spans ~1/3 of the screen.
     */
    fun zoomForPrecision(precision: Int, baseRadiusPx: Float, screenMinPx: Float): Float {
        val spanRad = cellSpanLat(precision) * (Math.PI / 180.0)
        val targetRadius = (screenMinPx / 3.0) / spanRad
        return (targetRadius / baseRadiusPx).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 120000f
    const val MAX_PRECISION = 12
}
