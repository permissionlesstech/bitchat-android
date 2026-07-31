package com.bitchat.android.ui.globe

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Render-ready geographic data decoded from streamed OpenStreetMap vector tiles.
 *
 * Coordinates are retained as latitude/longitude so the existing orthographic globe
 * projection remains the sole source of screen geometry and visual styling.
 */
data class GlobeMapData(
    val oceanPolygons: List<OceanPolygon> = emptyList(),
    val borders: List<BorderLine> = emptyList(),
    val boundaryLabels: List<MapLabel> = emptyList(),
    val placeLabels: List<MapLabel> = emptyList(),
    val detailZoom: Int = 0
) {
    val hasGeography: Boolean
        get() = oceanPolygons.isNotEmpty() || borders.isNotEmpty() ||
            boundaryLabels.isNotEmpty() || placeLabels.isNotEmpty()

    companion object {
        val EMPTY = GlobeMapData()
    }
}

data class OceanPolygon(val rings: List<GeoRing>)

data class BorderLine(
    val ring: GeoRing,
    val maritime: Boolean,
    val disputed: Boolean,
    val adminLevel: Int?
)

enum class MapLabelKind {
    COUNTRY,
    STATE,
    CAPITAL,
    CITY,
    TOWN,
    VILLAGE,
    OTHER
}

data class MapLabel(
    val name: String,
    val lat: Float,
    val lon: Float,
    val kind: MapLabelKind,
    val importance: Long,
    val projectionTerms: FloatArray = prepareProjectionTerms(
        coords = floatArrayOf(lat, lon),
        size = 1
    )
) {
    val rank: Int
        get() = when {
            kind == MapLabelKind.COUNTRY -> 0
            kind == MapLabelKind.STATE -> 3
            kind == MapLabelKind.CAPITAL -> 0
            importance >= 5_000_000L -> 0
            importance >= 1_000_000L -> 1
            importance >= 500_000L -> 2
            importance >= 100_000L -> 3
            kind == MapLabelKind.CITY -> 4
            kind == MapLabelKind.TOWN -> 6
            kind == MapLabelKind.VILLAGE -> 8
            else -> 10
        }

    val isCapital: Boolean get() = kind == MapLabelKind.CAPITAL
    val isMegacity: Boolean get() = importance >= 5_000_000L
}

data class GeoRing(
    val coords: FloatArray,
    val size: Int,
    /**
     * Polygon role from MVT winding order. `true` is a water exterior and `false`
     * is a land hole inside that water feature; lines and synthetic geometry use null.
     */
    val isMvtExterior: Boolean? = null,
    /**
     * Per-point sin(latitude), cos(latitude), sin(longitude), cos(longitude).
     * Preparing this once removes nearly all trigonometry from animated frames.
     */
    val projectionTerms: FloatArray = prepareProjectionTerms(coords, size)
) {
    /**
     * Conservative spherical cap used to reject rings that cannot touch the viewport.
     * The streamed z0 ocean tile contains thousands of tiny island rings, most of which
     * are far outside a zoomed view.
     */
    internal val sphericalBounds: SphericalRingBounds =
        prepareSphericalBounds(projectionTerms, size)
}

internal data class SphericalRingBounds(
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val angularRadius: Float
) {
    fun mayIntersectView(
        viewCenterX: Float,
        viewCenterY: Float,
        viewCenterZ: Float,
        viewAngularRadius: Float
    ): Boolean {
        // Large/degenerate rings can enclose the view even when their edge vertices are
        // distant, so only cull compact rings.
        if (angularRadius >= MAX_CULLABLE_RING_RADIUS_RADIANS) return true
        val maximumDistance =
            angularRadius + viewAngularRadius + RING_CULL_MARGIN_RADIANS
        if (maximumDistance >= Math.PI.toFloat()) return true
        val dot =
            centerX * viewCenterX +
                centerY * viewCenterY +
                centerZ * viewCenterZ
        return dot >= cos(maximumDistance)
    }
}

internal fun prepareProjectionTerms(coords: FloatArray, size: Int): FloatArray {
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

private fun prepareSphericalBounds(
    projectionTerms: FloatArray,
    size: Int
): SphericalRingBounds {
    if (size <= 0) {
        return SphericalRingBounds(0f, 0f, 1f, Math.PI.toFloat())
    }
    var sumX = 0.0
    var sumY = 0.0
    var sumZ = 0.0
    for (index in 0 until size) {
        val offset = index * 4
        val sinLat = projectionTerms[offset]
        val cosLat = projectionTerms[offset + 1]
        val sinLon = projectionTerms[offset + 2]
        val cosLon = projectionTerms[offset + 3]
        sumX += cosLat * cosLon
        sumY += cosLat * sinLon
        sumZ += sinLat
    }
    val length = sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ)
    if (length < 1e-6) {
        return SphericalRingBounds(0f, 0f, 1f, Math.PI.toFloat())
    }
    val centerX = (sumX / length).toFloat()
    val centerY = (sumY / length).toFloat()
    val centerZ = (sumZ / length).toFloat()
    var angularRadius = 0f
    for (index in 0 until size) {
        val offset = index * 4
        val pointX = projectionTerms[offset + 1] * projectionTerms[offset + 3]
        val pointY = projectionTerms[offset + 1] * projectionTerms[offset + 2]
        val pointZ = projectionTerms[offset]
        val dot = (
            centerX * pointX +
                centerY * pointY +
                centerZ * pointZ
            ).coerceIn(-1f, 1f)
        angularRadius = maxOf(angularRadius, acos(dot))
    }
    return SphericalRingBounds(centerX, centerY, centerZ, angularRadius)
}

private const val MAX_CULLABLE_RING_RADIUS_RADIANS = 1.2f
private const val RING_CULL_MARGIN_RADIANS = 0.035f

data class GlobeMapLoadResult(
    val data: GlobeMapData,
    val requestedTileCount: Int,
    val failedTileCount: Int
)

data class GlobeMapUiState(
    val data: GlobeMapData = GlobeMapData.EMPTY,
    val isLoading: Boolean = false,
    val hasError: Boolean = false
)
