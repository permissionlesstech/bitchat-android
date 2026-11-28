package com.bitchat.android.ui.globe

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.bitchat.android.geohash.Geohash
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the globe's vector geometry: graticule, land, coastlines, country borders and the
 * geohash grid.
 *
 * Every frame draws the same full detail, moving or not, so nothing flickers in and out while
 * dragging. That is affordable because a frame allocates nothing per point: projected points,
 * fill polygons and clip buffers are primitive arrays reused across frames, and each layer of
 * geometry is batched into a handful of reused paths instead of one path per ring or run.
 */
internal class GlobeRenderer {
    private val clip = ClipRect()
    private var projected = FloatArray(3 * 256)
    private val polygon = PointBuffer(512)
    private val clipBufferA = PointBuffer(512)
    private val clipBufferB = PointBuffer(512)
    private val probes = FillProbes()

    private val graticulePath = SegmentPath()
    private val coastPath = SegmentPath()
    private val borderPath = SegmentPath()
    private val landPath = Path()
    private val invertedLandPath = Path()

    private val neighborFillPath = Path()
    private val selectedFillPath = Path()
    private val neighborStrokePath = SegmentPath()
    private val selectedStrokePath = SegmentPath()

    /**
     * Skia's edge rasterizer loses precision with path coordinates beyond ~32767px, which
     * happens at high globe zoom, so all geometry is clipped to an expanded viewport first.
     */
    fun beginFrame(width: Float, height: Float) {
        clip.set(-width, -height, width * 2f, height * 2f)
    }

    fun DrawScope.drawGraticule(
        projector: GlobeMath.PreciseProjector,
        cx: Float,
        cy: Float,
        r: Float,
        color: Color
    ) {
        graticulePath.reset()
        for (line in GRATICULE) {
            val n = line.size / 2
            ensureProjected(n)
            var anyFront = false
            for (i in 0 until n) {
                projector.project(line[i * 2], line[i * 2 + 1], projected, i * 3)
                if (projected[i * 3 + 2] > FRONT_COS) anyFront = true
            }
            if (anyFront) {
                graticulePath.addProjectedLine(projected, n, closed = false, cx, cy, r, clip)
            }
        }
        drawPath(graticulePath.path, color, style = Stroke(width = 1f))
    }

    fun DrawScope.drawLand(
        land: List<LandData.Ring>,
        projector: GlobeMath.PreparedProjector,
        cx: Float,
        cy: Float,
        r: Float,
        centerLat: Double,
        centerLon: Double,
        fill: Color,
        coastline: Color,
        coastlineWidth: Float
    ) {
        landPath.rewind()
        coastPath.reset()
        probes.invalidate()
        for (ring in land) {
            val n = ring.size
            if (n < 3) continue
            ensureProjected(n)
            var anyFront = false
            var anyBack = false
            for (i in 0 until n) {
                projector.project(ring.projectionTerms, i * 4, projected, i * 3)
                val cosC = projected[i * 3 + 2]
                if (cosC > FRONT_COS) anyFront = true
                if (cosC < 0f) anyBack = true
            }
            if (!anyFront) continue

            buildFillPolygon(projected, n, cx, cy, r, polygon)
            // Fills of every landmass go into one path wound the same way, so they union into a
            // single draw. The rare ring whose horizon closure needs flipping is drawn alone.
            val invert = anyBack && projectedFillNeedsInversion(
                polygon, ring, cx, cy, r, probes.at(centerLat, centerLon)
            )
            val clipped = clipPolygon(polygon, clip, clipBufferA, clipBufferB)
            if (clipped.size >= 3) {
                if (invert) {
                    invertedLandPath.rewind()
                    invertedLandPath.fillType = PathFillType.EvenOdd
                    invertedLandPath.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                    invertedLandPath.addPolygon(clipped, counterClockwise = null)
                    drawPath(invertedLandPath, fill)
                } else {
                    landPath.addPolygon(clipped, counterClockwise = true)
                }
            }
            coastPath.addProjectedLine(projected, n, closed = true, cx, cy, r, clip)
        }
        drawPath(landPath, fill)
        // Coastlines go on after every fill, so a continent can never cover an island's outline.
        drawPath(coastPath.path, coastline, style = Stroke(width = coastlineWidth))
    }

    fun DrawScope.drawBorders(
        borders: List<LandData.Ring>,
        projector: GlobeMath.PreparedProjector,
        cx: Float,
        cy: Float,
        r: Float,
        color: Color,
        width: Float
    ) {
        borderPath.reset()
        for (line in borders) {
            val n = line.size
            if (n < 2) continue
            ensureProjected(n)
            var anyFront = false
            for (i in 0 until n) {
                projector.project(line.projectionTerms, i * 4, projected, i * 3)
                if (projected[i * 3 + 2] > FRONT_COS) anyFront = true
            }
            if (anyFront) borderPath.addProjectedLine(projected, n, closed = false, cx, cy, r, clip)
        }
        drawPath(borderPath.path, color, style = Stroke(width = width))
    }

    /** The selected cell and its neighbors; the selection is drawn last so it stays on top. */
    fun DrawScope.drawGeohashGrid(
        selected: String,
        projector: GlobeMath.PreciseProjector,
        cx: Float,
        cy: Float,
        r: Float,
        accent: Color,
        grid: Color
    ) {
        neighborFillPath.rewind()
        selectedFillPath.rewind()
        neighborStrokePath.reset()
        selectedStrokePath.reset()

        addCell(selected, projector, cx, cy, r, selectedFillPath, selectedStrokePath)
        for (cell in Geohash.neighborsSamePrecision(selected)) {
            if (cell != selected) {
                addCell(cell, projector, cx, cy, r, neighborFillPath, neighborStrokePath)
            }
        }

        drawPath(neighborFillPath, grid.copy(alpha = 0.05f))
        drawPath(neighborStrokePath.path, grid, style = Stroke(width = 1.6f))
        drawPath(selectedFillPath, accent.copy(alpha = 0.20f))
        drawPath(selectedStrokePath.path, accent.copy(alpha = 0.35f), style = Stroke(width = 7f))
        drawPath(selectedStrokePath.path, accent, style = Stroke(width = 3.2f))
    }

    private fun addCell(
        cell: String,
        projector: GlobeMath.PreciseProjector,
        cx: Float,
        cy: Float,
        r: Float,
        fillPath: Path,
        strokePath: SegmentPath
    ) {
        val b = Geohash.decodeToBounds(cell)
        val spanLat = b.latMax - b.latMin
        val spanLon = b.lonMax - b.lonMin
        val steps = ceil(spanLat / 1.5).toInt().coerceIn(4, 48)
        val n = steps * 4
        ensureProjected(n)

        // Sample the boundary without repeating corners: N edge west to east, E edge north to
        // south, S edge east to west, W edge south to north.
        var count = 0
        var anyFront = false
        fun add(lat: Double, lon: Double) {
            projector.project(lat, lon, projected, count * 3)
            if (projected[count * 3 + 2] > FRONT_COS) anyFront = true
            count++
        }
        for (s in 0 until steps) add(b.latMax, b.lonMin + spanLon * s / steps)
        for (s in 0 until steps) add(b.latMax - spanLat * s / steps, b.lonMax)
        for (s in 0 until steps) add(b.latMin, b.lonMax - spanLon * s / steps)
        for (s in 0 until steps) add(b.latMin + spanLat * s / steps, b.lonMin)
        if (!anyFront) return

        buildFillPolygon(projected, count, cx, cy, r, polygon)
        val clipped = clipPolygon(polygon, clip, clipBufferA, clipBufferB)
        if (clipped.size >= 3) fillPath.addPolygon(clipped, counterClockwise = true)
        strokePath.addProjectedLine(projected, count, closed = true, cx, cy, r, clip)
    }

    private fun ensureProjected(points: Int) {
        if (projected.size < points * 3) projected = FloatArray(points * 3)
    }
}

/** A point counts as on the near side once it has turned this far toward the viewer. */
internal const val FRONT_COS = 0.005f

/**
 * Consecutive visible points further apart than this (squared, in globe radii) are a polygon
 * jumping across the view, such as Antarctica's closure along the antimeridian, not an edge.
 */
private const val MAX_EDGE_SQUARED = 1.2f

private const val GRATICULE_SPACING_DEGREES = 15
private const val GRATICULE_STEP_DEGREES = 4

/** Graticule polylines as flat (lat, lon) degree pairs: parallels to ±75°, then meridians. */
private val GRATICULE: List<DoubleArray> = buildList {
    for (lat in -75..75 step GRATICULE_SPACING_DEGREES) {
        val lons = (-180..180 step GRATICULE_STEP_DEGREES).toList()
        add(DoubleArray(lons.size * 2) { if (it % 2 == 0) lat.toDouble() else lons[it / 2].toDouble() })
    }
    for (lon in -180 until 180 step GRATICULE_SPACING_DEGREES) {
        val lats = (-90..90 step GRATICULE_STEP_DEGREES).toList()
        add(DoubleArray(lats.size * 2) { if (it % 2 == 0) lats[it / 2].toDouble() else lon.toDouble() })
    }
}

internal class ClipRect(
    var left: Float = 0f,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f
) {
    fun set(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

/** Interleaved (x, y) points in a growable array that is reused across frames. */
internal class PointBuffer(initialCapacity: Int = 64) {
    var coords = FloatArray(initialCapacity * 2)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(x: Float, y: Float) {
        val index = size * 2
        if (index + 2 > coords.size) coords = coords.copyOf(maxOf(coords.size * 2, 16))
        coords[index] = x
        coords[index + 1] = y
        size++
    }

    fun x(i: Int): Float = coords[i * 2]
    fun y(i: Int): Float = coords[i * 2 + 1]
}

/** Receives the clipped segments of projected lines. */
internal interface SegmentSink {
    /** Adds a segment, continuing the current polyline when it starts where the last one ended. */
    fun segment(x0: Float, y0: Float, x1: Float, y1: Float)

    /** Ends the current polyline. */
    fun lift()
}

/**
 * Walks a projected line (x, y, cosC triples in globe radii) and emits the parts on the near
 * hemisphere as screen-space segments. A segment that turns over the horizon is cut where it
 * crosses the limb, so outlines run right up to the globe's edge.
 */
internal fun SegmentSink.addProjectedLine(
    points: FloatArray,
    n: Int,
    closed: Boolean,
    cx: Float,
    cy: Float,
    r: Float,
    clip: ClipRect
) {
    if (n < 2) return
    lift()
    for (i in (if (closed) 0 else 1) until n) {
        val prev = if (i == 0) n - 1 else i - 1
        val prevCos = points[prev * 3 + 2]
        val curCos = points[i * 3 + 2]
        val prevFront = prevCos > FRONT_COS
        val curFront = curCos > FRONT_COS
        if (!prevFront && !curFront) {
            lift()
            continue
        }
        var ax = points[prev * 3]
        var ay = points[prev * 3 + 1]
        var bx = points[i * 3]
        var by = points[i * 3 + 1]
        if (!prevFront) {
            val t = limbFraction(curCos, prevCos)
            ax = bx + (ax - bx) * t
            ay = by + (ay - by) * t
            val len = sqrt(ax * ax + ay * ay)
            if (len > 1e-6f) {
                ax /= len
                ay /= len
            }
        } else if (!curFront) {
            val t = limbFraction(prevCos, curCos)
            bx = ax + (bx - ax) * t
            by = ay + (by - ay) * t
            val len = sqrt(bx * bx + by * by)
            if (len > 1e-6f) {
                bx /= len
                by /= len
            }
        }
        val dx = bx - ax
        val dy = by - ay
        if (dx * dx + dy * dy > MAX_EDGE_SQUARED) {
            lift()
            continue
        }
        clipSegment(cx + ax * r, cy + ay * r, cx + bx * r, cy + by * r, clip)
        if (!curFront) lift()
    }
}

/** How far from the front point toward the back one the chord crosses the horizon plane. */
private fun limbFraction(frontCos: Float, backCos: Float): Float {
    val denominator = frontCos - backCos
    return if (denominator <= 0f) 1f else (frontCos / denominator).coerceIn(0f, 1f)
}

/** Liang–Barsky clip of a segment to [clip]; emits the visible part, if any. */
private fun SegmentSink.clipSegment(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    clip: ClipRect
) {
    if (clip.contains(x0, y0) && clip.contains(x1, y1)) {
        segment(x0, y0, x1, y1)
        return
    }
    val dx = x1 - x0
    val dy = y1 - y0
    var u1 = 0f
    var u2 = 1f
    // Each edge: p is the direction toward outside, q the distance from the start to the edge.
    for (edge in 0 until 4) {
        val p: Float
        val q: Float
        when (edge) {
            0 -> { p = -dx; q = x0 - clip.left }
            1 -> { p = dx; q = clip.right - x0 }
            2 -> { p = -dy; q = y0 - clip.top }
            else -> { p = dy; q = clip.bottom - y0 }
        }
        if (p == 0f) {
            if (q < 0f) {
                lift()
                return
            }
            continue
        }
        val ratio = q / p
        if (p < 0f) {
            if (ratio > u2) {
                lift()
                return
            }
            if (ratio > u1) u1 = ratio
        } else {
            if (ratio < u1) {
                lift()
                return
            }
            if (ratio < u2) u2 = ratio
        }
    }
    if (u1 > 0f) lift()
    segment(x0 + u1 * dx, y0 + u1 * dy, x0 + u2 * dx, y0 + u2 * dy)
    if (u2 < 1f) lift()
}

/** Builds stroked polylines into one reused [path]. */
internal class SegmentPath : SegmentSink {
    val path = Path()
    private var penX = 0f
    private var penY = 0f
    private var hasPen = false

    fun reset() {
        path.rewind()
        hasPen = false
    }

    override fun segment(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!hasPen || x0 != penX || y0 != penY) path.moveTo(x0, y0)
        path.lineTo(x1, y1)
        penX = x1
        penY = y1
        hasPen = true
    }

    override fun lift() {
        hasPen = false
    }
}

/**
 * Builds a fill polygon for a projected ring (x, y, cosC triples) by clamping back-facing
 * points onto the limb and inserting horizon arc steps between consecutive limb points, so the
 * result approximates (polygon ∩ visible disc) without self-intersecting chords.
 */
internal fun buildFillPolygon(
    points: FloatArray,
    n: Int,
    cx: Float,
    cy: Float,
    r: Float,
    out: PointBuffer
) {
    out.clear()
    var hasPrevLimb = false
    var prevLimbAngle = 0f
    var hasFirstLimb = false
    var firstLimbAngle = 0f

    for (i in 0 until n) {
        val x = points[i * 3]
        val y = points[i * 3 + 1]
        if (points[i * 3 + 2] > FRONT_COS) {
            out.add(cx + x * r, cy + y * r)
            hasPrevLimb = false
        } else {
            val len = sqrt(x * x + y * y)
            val lx: Float
            val ly: Float
            if (len > 1e-6f) {
                lx = x / len
                ly = y / len
            } else {
                lx = 0f
                ly = -1f
            }
            val angle = atan2(ly, lx)
            if (hasPrevLimb) addHorizonArc(out, prevLimbAngle, angle, cx, cy, r)
            if (!hasFirstLimb) {
                hasFirstLimb = true
                firstLimbAngle = angle
            }
            out.add(cx + lx * r, cy + ly * r)
            hasPrevLimb = true
            prevLimbAngle = angle
        }
    }
    // Wrap-around arc when the ring both ends and starts on the limb.
    if (hasPrevLimb && hasFirstLimb && n > 0 && points[2] <= FRONT_COS) {
        addHorizonArc(out, prevLimbAngle, firstLimbAngle, cx, cy, r)
    }
}

private const val TWO_PI = (2 * Math.PI).toFloat()
private const val PI_F = Math.PI.toFloat()

private fun addHorizonArc(out: PointBuffer, from: Float, to: Float, cx: Float, cy: Float, r: Float) {
    var d = to - from
    while (d > PI_F) d -= TWO_PI
    while (d < -PI_F) d += TWO_PI
    val steps = (abs(d) / 0.04f).toInt().coerceIn(1, 64)
    for (s in 1 until steps) {
        val a = from + d * s / steps
        out.add(cx + cos(a) * r, cy + sin(a) * r)
    }
}

/**
 * Sutherland–Hodgman clip of [input] to [rect], using [a] and [b] as scratch. Returns the buffer
 * holding the result, which is [input] itself when nothing needed clipping.
 */
internal fun clipPolygon(input: PointBuffer, rect: ClipRect, a: PointBuffer, b: PointBuffer): PointBuffer {
    var inside = true
    for (i in 0 until input.size) {
        if (!rect.contains(input.x(i), input.y(i))) {
            inside = false
            break
        }
    }
    if (inside) return input
    clipEdge(input, a, CLIP_LEFT, rect.left)
    clipEdge(a, b, CLIP_RIGHT, rect.right)
    clipEdge(b, a, CLIP_TOP, rect.top)
    clipEdge(a, b, CLIP_BOTTOM, rect.bottom)
    return b
}

private const val CLIP_LEFT = 0
private const val CLIP_RIGHT = 1
private const val CLIP_TOP = 2
private const val CLIP_BOTTOM = 3

private fun isInside(edge: Int, bound: Float, x: Float, y: Float): Boolean = when (edge) {
    CLIP_LEFT -> x >= bound
    CLIP_RIGHT -> x <= bound
    CLIP_TOP -> y >= bound
    else -> y <= bound
}

private fun clipEdge(input: PointBuffer, output: PointBuffer, edge: Int, bound: Float) {
    output.clear()
    val n = input.size
    if (n == 0) return
    var sx = input.x(n - 1)
    var sy = input.y(n - 1)
    var sIn = isInside(edge, bound, sx, sy)
    for (i in 0 until n) {
        val ex = input.x(i)
        val ey = input.y(i)
        val eIn = isInside(edge, bound, ex, ey)
        if (eIn != sIn) {
            if (edge == CLIP_LEFT || edge == CLIP_RIGHT) {
                val t = (bound - sx) / (ex - sx)
                output.add(bound, sy + t * (ey - sy))
            } else {
                val t = (bound - sy) / (ey - sy)
                output.add(sx + t * (ex - sx), bound)
            }
        }
        if (eIn) output.add(ex, ey)
        sx = ex
        sy = ey
        sIn = eIn
    }
}

/**
 * Appends [polygon] as a closed contour. With [counterClockwise] set, the contour is wound
 * that way regardless of its source order, so overlapping contours union under non-zero fill.
 */
internal fun Path.addPolygon(polygon: PointBuffer, counterClockwise: Boolean?) {
    val n = polygon.size
    if (n < 3) return
    val reverse = counterClockwise != null &&
        (signedArea(polygon) > 0f) != counterClockwise
    if (reverse) {
        moveTo(polygon.x(n - 1), polygon.y(n - 1))
        for (i in n - 2 downTo 0) lineTo(polygon.x(i), polygon.y(i))
    } else {
        moveTo(polygon.x(0), polygon.y(0))
        for (i in 1 until n) lineTo(polygon.x(i), polygon.y(i))
    }
    close()
}

/** Shoelace area; positive for counter-clockwise winding in a y-up frame. */
internal fun signedArea(polygon: PointBuffer): Float {
    var sum = 0.0
    val n = polygon.size
    var px = polygon.x(n - 1)
    var py = polygon.y(n - 1)
    for (i in 0 until n) {
        val x = polygon.x(i)
        val y = polygon.y(i)
        sum += px.toDouble() * y - x.toDouble() * py
        px = x
        py = y
    }
    return (sum / 2.0).toFloat()
}

internal fun polygonContains(polygon: PointBuffer, x: Float, y: Float): Boolean {
    val n = polygon.size
    if (n < 3) return false
    var inside = false
    var px = polygon.x(n - 1)
    var py = polygon.y(n - 1)
    for (i in 0 until n) {
        val qx = polygon.x(i)
        val qy = polygon.y(i)
        if ((qy > y) != (py > y)) {
            val intersectionX = (px - qx) * (y - qy) / (py - qy) + qx
            if (x < intersectionX) inside = !inside
        }
        px = qx
        py = qy
    }
    return inside
}

/**
 * Points on the visible disc where a land fill's projected and geographic insides are
 * compared. They depend only on the view center, so they are unprojected once per frame.
 */
internal class FillProbes {
    val count get() = size
    val discX = FloatArray(SAMPLES.size)
    val discY = FloatArray(SAMPLES.size)
    val latitude = DoubleArray(SAMPLES.size)
    val longitude = DoubleArray(SAMPLES.size)
    private var size = 0
    private var valid = false

    fun invalidate() {
        valid = false
    }

    fun at(centerLat: Double, centerLon: Double): FillProbes {
        if (valid) return this
        size = 0
        for ((nx, ny) in SAMPLES) {
            val location = GlobeMath.unproject(nx.toDouble(), ny.toDouble(), centerLat, centerLon) ?: continue
            discX[size] = nx
            discY[size] = ny
            latitude[size] = location.first
            longitude[size] = location.second
            size++
        }
        valid = true
        return this
    }

    private companion object {
        val SAMPLES = arrayOf(
            0f to 0f,
            -0.5f to 0f,
            0.5f to 0f,
            0f to -0.5f,
            0f to 0.5f,
            -0.35f to -0.35f,
            0.35f to -0.35f,
            -0.35f to 0.35f,
            0.35f to 0.35f
        )
    }
}

/**
 * Orthographic projection can choose the wrong side of the horizon closure for very large rings.
 * A single center-point check is ambiguous for a large polygon and caused an occasional
 * whole-disc fill, so several visible points are compared with the original geographic ring,
 * and the fill is inverted only when the opposite side wins clearly.
 */
internal fun projectedFillNeedsInversion(
    polygon: PointBuffer,
    ring: LandData.Ring,
    cx: Float,
    cy: Float,
    r: Float,
    probes: FillProbes
): Boolean {
    var normalErrors = 0
    var invertedErrors = 0
    for (i in 0 until probes.count) {
        val geographicInside = ringContainsLocation(ring, probes.latitude[i], probes.longitude[i])
        val projectedInside = polygonContains(polygon, cx + probes.discX[i] * r, cy + probes.discY[i] * r)
        if (projectedInside != geographicInside) normalErrors++
        if (!projectedInside != geographicInside) invertedErrors++
    }
    return invertedErrors < normalErrors
}

/**
 * Point-in-ring test in geographic space: counts the ring edges that cross the probe's meridian
 * north of it. Each edge is followed the short way around, so neither the ±180° seam nor the
 * probe's antimeridian creates a false crossing. Casting the ray along a parallel instead wrapped
 * edges near the probe's antimeridian from +180° to -180°, so a Pacific probe saw Europe's coast
 * as crossings and Eurasia "contained" the Pacific, inverting its fill. Matches the iOS globe.
 */
internal fun ringContainsLocation(
    ring: LandData.Ring,
    latitude: Double,
    longitude: Double
): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var previousIndex = ring.size - 1
    for (index in 0 until ring.size) {
        val startLat = ring.coords[previousIndex * 2].toDouble()
        val endLat = ring.coords[index * 2].toDouble()
        val startLon = ring.coords[previousIndex * 2 + 1].toDouble()
        val endLon = ring.coords[index * 2 + 1].toDouble()
        val start = GlobeMath.normalizeLon(startLon - longitude)
        // Unbounded on purpose: the end changes sign relative to the start only when the
        // edge crosses the probe meridian, never when it crosses ±180°.
        val end = start + GlobeMath.normalizeLon(endLon - startLon)
        if ((start > 0.0) != (end > 0.0)) {
            val t = start / (start - end)
            if (startLat + (endLat - startLat) * t > latitude) inside = !inside
        }
        previousIndex = index
    }
    return inside
}
