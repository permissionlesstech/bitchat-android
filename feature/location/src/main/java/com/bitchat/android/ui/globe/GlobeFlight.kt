package com.bitchat.android.ui.globe

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tanh

/**
 * Smooth pan-and-zoom path from van Wijk and Nuij, "Smooth and efficient zooming and panning"
 * (the curve behind d3-zoom and Mapbox flyTo). It keeps on-screen motion even when the zoom
 * changes by orders of magnitude: it pans while zoomed out and zooms while nearly on target,
 * instead of panning linearly while zooming exponentially, which races at the end.
 *
 * [distance] is the arc between the start and end centers in radians; widths are the angular
 * span visible across the screen (screen size over globe radius) at each end.
 */
internal class GlobeFlight(
    private val distance: Double,
    private val startWidth: Double,
    private val endWidth: Double,
    private val rho: Double = RHO
) {
    private val panning = distance > EPSILON
    private val r0: Double
    private val length: Double

    init {
        if (!panning) {
            r0 = 0.0
            length = ln(endWidth / startWidth) / rho
        } else {
            val rho2 = rho * rho
            val rho4 = rho2 * rho2
            val d2 = distance * distance
            val b0 = (endWidth * endWidth - startWidth * startWidth + rho4 * d2) / (2 * startWidth * rho2 * distance)
            val b1 = (endWidth * endWidth - startWidth * startWidth - rho4 * d2) / (2 * endWidth * rho2 * distance)
            // ln(sqrt(b² + 1) - b), written as -asinh(b): the direct form cancels to ln(0) when b is
            // large, which a zoom-out over a tiny arc produces.
            r0 = -asinh(b0)
            val r1 = -asinh(b1)
            length = (r1 - r0) / rho
        }
    }

    /** Path length in the curve's own units; longer flights should take longer. */
    val pathLength: Double get() = abs(length)

    /** At progress [t] in 0..1: the fraction of [distance] covered and the visible width. */
    fun at(t: Double): Pair<Double, Double> {
        if (t >= 1.0) return 1.0 to endWidth
        val s = t * length
        if (!panning) return t to startWidth * exp(rho * s)
        val coshR0 = cosh(r0)
        // The curve's position is w0 / rho² · (…); dividing by distance as well yields the fraction.
        val fraction = startWidth / (rho * rho * distance) * (coshR0 * tanh(rho * s + r0) - sinh(r0))
        val w = startWidth * coshR0 / cosh(rho * s + r0)
        return fraction.coerceIn(0.0, 1.0) to w
    }

    companion object {
        /** d3's default: how strongly the path zooms out to travel. */
        const val RHO = 1.414
        /** Arcs shorter than this (d3's threshold) are zoom-only; [arc] returns noise near zero. */
        private const val EPSILON = 1e-6

        /** Great-circle angle between two points, radians. */
        fun arc(lat0: Double, lon0: Double, lat1: Double, lon1: Double): Double {
            val a = unit(lat0, lon0)
            val b = unit(lat1, lon1)
            val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
            return acos(dot)
        }

        /** Point a [fraction] of the way along the great circle; (lat, lon) in degrees. */
        fun slerp(lat0: Double, lon0: Double, lat1: Double, lon1: Double, fraction: Double): Pair<Double, Double> {
            val a = unit(lat0, lon0)
            val b = unit(lat1, lon1)
            val omega = acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0))
            val sinOmega = sin(omega)
            if (sinOmega < 1e-6) {
                // Same point or exact antipodes: no unique great circle, so interpolate degrees.
                val dLon = GlobeMath.normalizeLon(lon1 - lon0)
                return (lat0 + (lat1 - lat0) * fraction) to GlobeMath.normalizeLon(lon0 + dLon * fraction)
            }
            val wa = sin((1 - fraction) * omega) / sinOmega
            val wb = sin(fraction * omega) / sinOmega
            val x = wa * a[0] + wb * b[0]
            val y = wa * a[1] + wb * b[1]
            val z = wa * a[2] + wb * b[2]
            return Math.toDegrees(asin(z.coerceIn(-1.0, 1.0))) to Math.toDegrees(atan2(y, x))
        }

        private fun unit(latDeg: Double, lonDeg: Double): DoubleArray {
            val lat = Math.toRadians(latDeg)
            val lon = Math.toRadians(lonDeg)
            return doubleArrayOf(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
        }
    }
}
