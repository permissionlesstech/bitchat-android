package com.bitchat.android.ui

import kotlin.math.*

/**
 * Stateless math utility engine to calculate distance, bearing,
 * relative bearing, and Cartesian coordinate mappings on the radar viewport.
 */
object RadarMathEngine {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates the absolute distance between two GPS coordinates in meters
     * using the Haversine formula.
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculates the absolute bearing angle from (lat1, lon1) to (lat2, lon2)
     * relative to true North in degrees [0, 360).
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearingRad = atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360.0) % 360.0
    }

    /**
     * Adjusts the absolute bearing to be relative to the device's physical heading (orientation).
     * Output angle is normalized to degrees in range [0, 360).
     */
    fun calculateRelativeAngle(absoluteBearing: Double, deviceHeading: Double): Double {
        return ((absoluteBearing - deviceHeading) % 360.0 + 360.0) % 360.0
    }

    /**
     * Translates a distance and relative bearing angle (0 = top/North, clockwise)
     * into Cartesian coordinates (X, Y) relative to the center of the canvas.
     */
    fun toCartesian(
        distance: Double,
        relativeAngleDegrees: Double,
        maxDistance: Double,
        maxRadius: Double
    ): Pair<Double, Double> {
        val normalizedRadius = if (maxDistance > 0.0) (distance / maxDistance).coerceAtMost(1.0) else 1.0
        val radius = normalizedRadius * maxRadius
        val theta = Math.toRadians(relativeAngleDegrees)
        val x = sin(theta) * radius
        val y = -cos(theta) * radius
        return Pair(x, y)
    }
}
