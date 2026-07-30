package com.bitchat.android.ui.globe

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.bitchat.android.geohash.Geohash
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Color palette for the globe, derived from the app theme by the caller. */
data class GlobeColors(
    val accent: Color,
    val land: Color,
    val coastline: Color,
    val border: Color,
    val oceanCenter: Color,
    val oceanEdge: Color,
    val atmosphere: Color,
    val graticule: Color,
    val grid: Color,
    val label: Color,
    val labelHalo: Color,
    val star: Color
)

private class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float)

internal data class GlobeFrameDetail(
    val graticuleStepDegrees: Double,
    val landPointStride: Int,
    val showBorders: Boolean,
    val cityMaxRank: Int?,
    val showCityLabels: Boolean,
    val showGeohashGrid: Boolean,
    val showNeighborCells: Boolean
)

internal enum class GlobeMotionDetail {
    FULL,
    BALANCED,
    FAST
}

internal fun globeFrameDetail(motionDetail: GlobeMotionDetail): GlobeFrameDetail =
    when (motionDetail) {
        GlobeMotionDetail.FULL -> GlobeFrameDetail(
            graticuleStepDegrees = 4.0,
            landPointStride = 1,
            showBorders = true,
            cityMaxRank = null,
            showCityLabels = true,
            showGeohashGrid = true,
            showNeighborCells = true
        )
        GlobeMotionDetail.BALANCED -> GlobeFrameDetail(
            graticuleStepDegrees = 8.0,
            landPointStride = 2,
            showBorders = true,
            cityMaxRank = 1,
            showCityLabels = false,
            showGeohashGrid = true,
            showNeighborCells = false
        )
        GlobeMotionDetail.FAST -> GlobeFrameDetail(
            graticuleStepDegrees = 10.0,
            landPointStride = 2,
            showBorders = false,
            cityMaxRank = -1,
            showCityLabels = false,
            showGeohashGrid = false,
            showNeighborCells = false
        )
    }

/**
 * Adjusts moving-frame detail from measured frame cadence. Hysteresis keeps the renderer
 * from oscillating between levels when timings sit near a boundary.
 */
internal fun nextGlobeMotionDetail(
    current: GlobeMotionDetail,
    averageFrameMillis: Float
): GlobeMotionDetail {
    if (!averageFrameMillis.isFinite()) return GlobeMotionDetail.FAST
    return when (current) {
        GlobeMotionDetail.FULL -> when {
            averageFrameMillis > SEVERELY_SLOW_FRAME_MILLIS -> GlobeMotionDetail.FAST
            averageFrameMillis > SLOW_FRAME_MILLIS -> GlobeMotionDetail.BALANCED
            else -> GlobeMotionDetail.FULL
        }
        GlobeMotionDetail.BALANCED -> when {
            averageFrameMillis > VERY_SLOW_FRAME_MILLIS -> GlobeMotionDetail.FAST
            averageFrameMillis < SMOOTH_FRAME_MILLIS -> GlobeMotionDetail.FULL
            else -> GlobeMotionDetail.BALANCED
        }
        GlobeMotionDetail.FAST -> {
            if (averageFrameMillis < RECOVERING_FRAME_MILLIS) {
                GlobeMotionDetail.BALANCED
            } else {
                GlobeMotionDetail.FAST
            }
        }
    }
}

@Composable
fun GlobeView(
    state: GlobeState,
    colors: GlobeColors,
    land: List<LandData.Ring>,
    borders: List<LandData.Ring>,
    cities: List<LandData.City>,
    labelTypeface: Typeface?,
    labelTypefaceBold: Typeface?,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current

    val stars = remember {
        val rnd = kotlin.random.Random(42)
        List(160) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                radius = 0.6f + rnd.nextFloat() * 1.5f,
                alpha = 0.15f + rnd.nextFloat() * 0.55f
            )
        }
    }

    val maxRingPoints = remember(land) { land.maxOfOrNull { it.size } ?: 0 }
    val scratch = remember(maxRingPoints) { FloatArray(maxOf(1, maxRingPoints) * 3) }
    val maxBorderPoints = remember(borders) { borders.maxOfOrNull { it.size } ?: 0 }
    val borderScratch = remember(maxBorderPoints) { FloatArray(maxOf(1, maxBorderPoints) * 3) }

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    }
    val haloPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
        }
    }

    val pulse by rememberInfiniteTransition(label = "crosshair").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "crosshairAlpha"
    )

    LaunchedEffect(state) {
        // Fire intro once the viewport size is known.
        snapshotFlow { state.baseRadiusPx }.first { it > 0f }
        state.playPendingIntroIfAny()
    }

    LaunchedEffect(state) {
        snapshotFlow { state.selectedGeohash }
            .drop(1)
            .collect {
                @Suppress("DEPRECATION")
                view.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
    }

    var motionDetail by remember(state) { mutableStateOf(GlobeMotionDetail.BALANCED) }
    LaunchedEffect(state, state.isInMotion) {
        if (!state.isInMotion) {
            motionDetail = GlobeMotionDetail.FULL
            return@LaunchedEffect
        }

        // Balanced is a safe first frame; measured cadence then moves detail up or down.
        motionDetail = GlobeMotionDetail.BALANCED
        var previousFrameNanos = withFrameNanos { it }
        var sampledFrameMillis = 0f
        var sampledFrameCount = 0
        while (state.isInMotion) {
            val frameNanos = withFrameNanos { it }
            val frameMillis = ((frameNanos - previousFrameNanos) / 1_000_000f)
                .coerceIn(1f, MAX_SAMPLED_FRAME_MILLIS)
            previousFrameNanos = frameNanos
            sampledFrameMillis += frameMillis
            sampledFrameCount++

            if (sampledFrameCount >= FRAME_SAMPLE_COUNT) {
                motionDetail = nextGlobeMotionDetail(
                    current = motionDetail,
                    averageFrameMillis = sampledFrameMillis / sampledFrameCount
                )
                sampledFrameMillis = 0f
                sampledFrameCount = 0
            }
        }
    }

    val labelTextSize = with(density) { 12.5.sp.toPx() }
    val labelTextSizeSmall = with(density) { 10.sp.toPx() }

    Box(modifier = modifier) {
        // Static background lives in its own layer and is not invalidated by globe movement.
        Canvas(modifier = Modifier.matchParentSize()) {
            stars.forEach { star ->
                drawCircle(
                    color = colors.star.copy(alpha = star.alpha),
                    radius = star.radius * density.density,
                    center = Offset(star.x * size.width, star.y * size.height)
                )
            }
        }

        Canvas(
            modifier = Modifier
            .matchParentSize()
            .onSizeChanged { size: IntSize ->
                val minDim = min(size.width, size.height).toFloat()
                state.setViewport(minDim * 0.44f, minDim)
            }
            .pointerInput(state) {
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown()
                    state.cancelAnimations()
                    state.isInteracting = true
                    val downTime = SystemClock.uptimeMillis()
                    val downPos = down.position
                    var moved = Offset.Zero
                    var dragStarted = false
                    var hadMultiplePointers = false
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size > 1) {
                            hadMultiplePointers = true
                            velocityTracker.resetTracking()
                        }

                        val pan = event.calculatePan()
                        val zoomChange = event.calculateZoom()

                        if (pan != Offset.Zero) {
                            moved += pan
                            if (!dragStarted && moved.getDistance() >= viewConfiguration.touchSlop) {
                                dragStarted = true
                            }
                            if (dragStarted) state.rotateBy(pan.x, pan.y)
                        }
                        if (zoomChange != 1f) {
                            state.zoomBy(zoomChange)
                        }
                        if (!hadMultiplePointers && pressed.size == 1) {
                            val change = pressed[0]
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }

                    state.isInteracting = false
                    val upTime = SystemClock.uptimeMillis()
                    val isTap = !hadMultiplePointers &&
                        !dragStarted &&
                        upTime - downTime < 400 &&
                        moved.getDistance() < viewConfiguration.touchSlop

                    if (isTap) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = state.globeRadiusPx
                        if (r > 0f) {
                            val latLon = GlobeMath.unproject(
                                ((downPos.x - cx) / r).toDouble(),
                                ((downPos.y - cy) / r).toDouble(),
                                state.centerLat.toDouble(),
                                state.centerLon.toDouble()
                            )
                            if (latLon != null) {
                                val now = upTime
                                val lastTap = lastTapTime
                                val isDouble = now - lastTap < 350 &&
                                    (downPos - lastTapPos).getDistance() < viewConfiguration.touchSlop * 4
                                lastTapTime = now
                                lastTapPos = downPos
                                if (isDouble) {
                                    val targetZoom = (state.zoom * 1.9f).coerceAtMost(GlobeMath.MAX_ZOOM)
                                    state.animateTo(latLon.first, latLon.second, targetZoom, null, 500)
                                } else {
                                    state.animateTo(latLon.first, latLon.second, null, null, 450)
                                }
                            }
                        }
                    } else if (dragStarted && !hadMultiplePointers) {
                        val velocity = velocityTracker.calculateVelocity()
                        state.fling(velocity.x, velocity.y)
                    }
                }
            }
        ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseR = state.baseRadiusPx
        if (baseR <= 0f) return@Canvas
        val r = state.globeRadiusPx
        val cLat = state.centerLat.toDouble()
        val cLon = state.centerLon.toDouble()
        val preparedProjector = GlobeMath.PreparedProjector(cLat, cLon)

        // Atmosphere glow
        drawCircle(
            brush = Brush.radialGradient(
                0f to colors.atmosphere.copy(alpha = 0.30f),
                0.75f to colors.atmosphere.copy(alpha = 0.12f),
                1f to Color.Transparent,
                center = Offset(cx, cy),
                radius = r * 1.22f
            ),
            radius = r * 1.22f,
            center = Offset(cx, cy)
        )

        // Ocean sphere
        drawCircle(
            brush = Brush.radialGradient(
                0f to colors.oceanCenter,
                0.7f to colors.oceanCenter,
                1f to colors.oceanEdge,
                center = Offset(cx - r * 0.32f, cy - r * 0.38f),
                radius = r * 1.5f
            ),
            radius = r,
            center = Offset(cx, cy)
        )

        val clip = ClipRect(-size.width, -size.height, size.width * 2f, size.height * 2f)

        val frameDetail = globeFrameDetail(
            if (state.isInMotion) motionDetail else GlobeMotionDetail.FULL
        )

        // Graticule
        drawGraticule(
            cx, cy, r, cLat, cLon, colors.graticule, clip,
            step = frameDetail.graticuleStepDegrees
        )

        // Fill every landmass first. Coastlines are drawn in a separate final pass so a
        // large continent fill can never cover an island outline drawn earlier.
        val coastlineRuns = ArrayList<List<MutableList<Pair<Float, Float>>>>(land.size)
        for (ring in land) {
            val runs = drawLandFill(
                ring = ring,
                scratch = scratch,
                projector = preparedProjector,
                cx = cx,
                cy = cy,
                r = r,
                colors = colors,
                clip = clip,
                pointStride = if (ring.size >= 64) frameDetail.landPointStride else 1,
                centerLat = cLat,
                centerLon = cLon
            )
            if (runs != null) coastlineRuns.add(runs)
        }
        for (runs in coastlineRuns) {
            strokeRuns(runs, cx, cy, r, colors.coastline, 1.4f, clip)
        }

        // Country borders are restored when interaction settles.
        if (frameDetail.showBorders) {
            for (line in borders) {
                drawBorderLine(line, borderScratch, preparedProjector, cx, cy, r, colors, clip)
            }
        }

        // Sphere shading: dark limb + night side for 3D depth
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                0.62f to Color.Transparent,
                0.88f to Color.Black.copy(alpha = 0.34f),
                1f to Color.Black.copy(alpha = 0.72f),
                center = Offset(cx - r * 0.25f, cy - r * 0.3f),
                radius = r * 1.35f
            ),
            radius = r + 1,
            center = Offset(cx, cy)
        )
        drawCircle(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.42f),
                start = Offset(cx - r * 0.7f, cy - r * 0.7f),
                end = Offset(cx + r * 0.75f, cy + r * 0.8f)
            ),
            radius = r + 1,
            center = Offset(cx, cy)
        )

        // Cities are detail-only; omitting them while moving keeps touch latency predictable.
        val cityMaxRank = frameDetail.cityMaxRank
        if (cityMaxRank == null || cityMaxRank >= 0) {
            drawCities(
                cities, state, preparedProjector, cx, cy, r, colors,
                labelPaint, haloPaint, labelTypeface, labelTextSizeSmall, density.density,
                maxRankOverride = cityMaxRank,
                showLabels = frameDetail.showCityLabels
            )
        }

        // Detailed cells and labels settle into place after the gesture ends.
        if (frameDetail.showGeohashGrid && state.selectedGeohash.isNotEmpty()) {
            drawGeohashGrid(
                state, cx, cy, r, cLat, cLon, colors, clip,
                includeNeighbors = frameDetail.showNeighborCells
            )
            drawGeohashLabels(
                state, cx, cy, r, cLat, cLon, colors,
                labelPaint, haloPaint, labelTypeface, labelTypefaceBold,
                labelTextSize, labelTextSizeSmall,
                includeNeighbors = frameDetail.showNeighborCells
            )
        }

        }

        // Keep this animation isolated so its pulse does not redraw the globe geometry.
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val crossAlpha = if (state.isInMotion) 0.9f else pulse
            val crossColor = colors.accent.copy(alpha = crossAlpha)
            val gap = 5 * density.density
            val len = 9 * density.density
            val strokeW = 1.6f * density.density
            drawLine(crossColor, Offset(cx - gap - len, cy), Offset(cx - gap, cy), strokeW)
            drawLine(crossColor, Offset(cx + gap, cy), Offset(cx + gap + len, cy), strokeW)
            drawLine(crossColor, Offset(cx, cy - gap - len), Offset(cx, cy - gap), strokeW)
            drawLine(crossColor, Offset(cx, cy + gap), Offset(cx, cy + gap + len), strokeW)
            drawCircle(crossColor, radius = 1.8f * density.density, center = Offset(cx, cy))
        }
    }
}

private const val FRAME_SAMPLE_COUNT = 8
private const val MAX_SAMPLED_FRAME_MILLIS = 50f
private const val SMOOTH_FRAME_MILLIS = 18.5f
private const val SLOW_FRAME_MILLIS = 22f
private const val RECOVERING_FRAME_MILLIS = 22f
private const val VERY_SLOW_FRAME_MILLIS = 29f
private const val SEVERELY_SLOW_FRAME_MILLIS = 34f

private fun DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    r: Float,
    cLat: Double,
    cLon: Double,
    color: Color,
    clip: ClipRect,
    step: Double
) {
    val path = Path()
    fun strokeSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
        val seg = clipSegment(x0, y0, x1, y1, clip) ?: return
        path.moveTo(seg.first.first, seg.first.second)
        path.lineTo(seg.second.first, seg.second.second)
    }
    // latitude lines
    var lat = -75.0
    while (lat <= 75.0) {
        var prev: GlobeMath.Projection? = null
        var lon = -180.0
        while (lon <= 180.0) {
            val p = GlobeMath.project(lat, lon, cLat, cLon)
            if (p != null && p.cosC > 0.02f) {
                val pp = prev
                if (pp != null) strokeSegment(cx + pp.x * r, cy + pp.y * r, cx + p.x * r, cy + p.y * r)
                prev = p
            } else prev = null
            lon += step
        }
        lat += 15.0
    }
    // longitude lines
    var lon = -180.0
    while (lon < 180.0) {
        var prev: GlobeMath.Projection? = null
        var la = -90.0
        while (la <= 90.0) {
            val p = GlobeMath.project(la, lon, cLat, cLon)
            if (p != null && p.cosC > 0.02f) {
                val pp = prev
                if (pp != null) strokeSegment(cx + pp.x * r, cy + pp.y * r, cx + p.x * r, cy + p.y * r)
                prev = p
            } else prev = null
            la += step
        }
        lon += 15.0
    }
    drawPath(path, color, style = Stroke(width = 1f))
}

internal data class DiscPt(val x: Float, val y: Float, val front: Boolean)

private fun limbPoint(behind: DiscPt, front: DiscPt): Pair<Float, Float> {
    var lo = 0f; var hi = 1f
    repeat(14) {
        val t = (lo + hi) / 2f
        val x = behind.x + (front.x - behind.x) * t
        val y = behind.y + (front.y - behind.y) * t
        if (x * x + y * y < 1f) lo = t else hi = t
    }
    val t = (lo + hi) / 2f
    return (behind.x + (front.x - behind.x) * t) to (behind.y + (front.y - behind.y) * t)
}

/**
 * Splits a projected polygon into front-facing runs. Runs that touch the limb are
 * padded with the horizon intersection point so they can be closed along the horizon.
 * Pass [closed] = false for open polylines (border lines).
 */
internal fun buildFrontRuns(
    pts: List<DiscPt>,
    closed: Boolean = true
): List<MutableList<Pair<Float, Float>>> {
    if (pts.isEmpty()) return emptyList()
    val n = pts.size
    val runs = mutableListOf<MutableList<Pair<Float, Float>>>()
    var run = mutableListOf<Pair<Float, Float>>()
    var prev: DiscPt? = if (closed) pts[n - 1] else null
    for (i in 0 until n) {
        val cur = pts[i]
        val p = prev
        if (cur.front) {
            if (run.isEmpty() && p != null && !p.front) run.add(limbPoint(p, cur))
            // Antimeridian wrap: consecutive front points can jump across the whole
            // disc when a polygon crosses ±180° — break the run instead of drawing
            // a chord through the view.
            if (run.isNotEmpty()) {
                val last = run.last()
                val dx = cur.x - last.first
                val dy = cur.y - last.second
                if (dx * dx + dy * dy > 1.2f) {
                    runs.add(run)
                    run = mutableListOf()
                }
            }
            run.add(cur.x to cur.y)
        } else {
            if (run.isNotEmpty() && p != null) {
                run.add(limbPoint(p, cur))
                runs.add(run)
                run = mutableListOf()
            }
        }
        prev = cur
    }
    if (run.isNotEmpty()) {
        if (closed && runs.isNotEmpty() && pts[0].front && pts[n - 1].front) {
            runs[0] = (run + runs[0]).toMutableList()
        } else {
            // A fully front-facing ring has no limb transition to terminate its run.
            // Close it explicitly; cell boundary sampling intentionally omits the
            // duplicated final corner.
            if (closed && runs.isEmpty() && pts[0].front && pts[n - 1].front) {
                run.add(run.first())
            }
            runs.add(run)
        }
    }
    return runs
}

// Skia's edge rasterizer loses precision with path coordinates beyond ~32767px,
// which happens at high globe zoom. All geometry is clipped to an expanded
// viewport rect in screen space before being handed to a Path.
private class ClipRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun clipPolygon(pts: List<Pair<Float, Float>>, rect: ClipRect): List<Pair<Float, Float>> {
    fun clipEdge(
        input: List<Pair<Float, Float>>,
        inside: (Pair<Float, Float>) -> Boolean,
        intersect: (Pair<Float, Float>, Pair<Float, Float>) -> Pair<Float, Float>
    ): List<Pair<Float, Float>> {
        if (input.isEmpty()) return input
        val result = mutableListOf<Pair<Float, Float>>()
        var s = input.last()
        for (e in input) {
            val eIn = inside(e)
            val sIn = inside(s)
            if (eIn) {
                if (!sIn) result.add(intersect(s, e))
                result.add(e)
            } else if (sIn) {
                result.add(intersect(s, e))
            }
            s = e
        }
        return result
    }
    var out = pts
    out = clipEdge(out, { it.first >= rect.left }) { a, b ->
        val t = (rect.left - a.first) / (b.first - a.first)
        rect.left to (a.second + t * (b.second - a.second))
    }
    out = clipEdge(out, { it.first <= rect.right }) { a, b ->
        val t = (rect.right - a.first) / (b.first - a.first)
        rect.right to (a.second + t * (b.second - a.second))
    }
    out = clipEdge(out, { it.second >= rect.top }) { a, b ->
        val t = (rect.top - a.second) / (b.second - a.second)
        (a.first + t * (b.first - a.first)) to rect.top
    }
    out = clipEdge(out, { it.second <= rect.bottom }) { a, b ->
        val t = (rect.bottom - a.second) / (b.second - a.second)
        (a.first + t * (b.first - a.first)) to rect.bottom
    }
    return out
}

/** Liang–Barsky clip of a segment to the rect; returns clipped endpoints or null. */
private fun clipSegment(
    x0: Float, y0: Float, x1: Float, y1: Float, rect: ClipRect
): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
    val dx = x1 - x0
    val dy = y1 - y0
    var u1 = 0f
    var u2 = 1f
    fun test(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0f
        val r = q / p
        if (p < 0f) {
            if (r > u2) return false
            if (r > u1) u1 = r
        } else {
            if (r < u1) return false
            if (r < u2) u2 = r
        }
        return true
    }
    if (!test(-dx, x0 - rect.left)) return null
    if (!test(dx, rect.right - x0)) return null
    if (!test(-dy, y0 - rect.top)) return null
    if (!test(dy, rect.bottom - y0)) return null
    return ((x0 + u1 * dx) to (y0 + u1 * dy)) to ((x0 + u2 * dx) to (y0 + u2 * dy))
}

/**
 * Builds a fill polygon for a projected ring by clamping back-facing points onto the
 * limb and inserting horizon arc steps between consecutive limb points, so the result
 * approximates (polygon ∩ visible disc) without self-intersecting chords.
 */
private fun buildFillPolygon(
    pts: List<DiscPt>,
    cx: Float, cy: Float, r: Float
): List<Pair<Float, Float>> {
    val out = ArrayList<Pair<Float, Float>>(pts.size + 128)
    var prevLimbAngle: Float? = null
    var firstLimbAngle: Float? = null

    fun addArc(from: Float, to: Float) {
        var d = to - from
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        val steps = (kotlin.math.abs(d) / 0.04f).toInt().coerceIn(1, 64)
        for (s in 1 until steps) {
            val a = from + d * s / steps
            out.add((cx + kotlin.math.cos(a) * r) to (cy + kotlin.math.sin(a) * r))
        }
    }

    for (p in pts) {
        if (p.front) {
            out.add((cx + p.x * r) to (cy + p.y * r))
            prevLimbAngle = null
        } else {
            val len = kotlin.math.sqrt(p.x * p.x + p.y * p.y)
            val lx: Float; val ly: Float
            if (len > 1e-6f) { lx = p.x / len; ly = p.y / len } else { lx = 0f; ly = -1f }
            val ang = kotlin.math.atan2(ly, lx)
            prevLimbAngle?.let { addArc(it, ang) }
            if (firstLimbAngle == null) firstLimbAngle = ang
            out.add((cx + lx * r) to (cy + ly * r))
            prevLimbAngle = ang
        }
    }
    // wrap-around arc if the ring ends and starts on the limb
    val lastAng = prevLimbAngle
    val firstAng = firstLimbAngle
    if (lastAng != null && firstAng != null && pts.isNotEmpty() && !pts[0].front) {
        addArc(lastAng, firstAng)
    }
    return out
}

private fun DrawScope.fillPolygonClipped(
    pts: List<DiscPt>,
    cx: Float, cy: Float, r: Float,
    color: Color,
    clip: ClipRect,
    invertFill: Boolean = false,
    preparedPolygon: List<Pair<Float, Float>>? = null
) {
    if (pts.none { it.front }) return
    val poly = preparedPolygon ?: buildFillPolygon(pts, cx, cy, r)
    if (poly.size < 3) return
    val clipped = clipPolygon(poly, clip)
    if (clipped.size < 3) return
    val path = Path()
    if (invertFill) {
        // Orthographic projection can choose the wrong side of the horizon closure for
        // very large rings. Even-odd filling with the globe disc flips that one ring.
        path.fillType = PathFillType.EvenOdd
        path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
    }
    path.moveTo(clipped[0].first, clipped[0].second)
    for (k in 1 until clipped.size) {
        path.lineTo(clipped[k].first, clipped[k].second)
    }
    path.close()
    drawPath(path, color)
}

internal fun projectedFillNeedsInversion(
    polygon: List<Pair<Float, Float>>,
    ring: LandData.Ring,
    cx: Float,
    cy: Float,
    r: Float,
    centerLat: Double,
    centerLon: Double
): Boolean {
    // A single center-point check is ambiguous for a large polygon and caused an
    // occasional whole-disc fill. Compare several visible points with the original
    // geographic ring, and invert only when the opposite fill wins clearly.
    val samples = arrayOf(
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
    var normalErrors = 0
    var invertedErrors = 0
    for ((nx, ny) in samples) {
        val location = GlobeMath.unproject(
            x = nx.toDouble(),
            y = ny.toDouble(),
            centerLatDeg = centerLat,
            centerLonDeg = centerLon
        ) ?: continue
        val geographicInside = ringContainsLocation(ring, location.first, location.second)
        val projectedInside = polygonContains(polygon, cx + nx * r, cy + ny * r)
        if (projectedInside != geographicInside) normalErrors++
        if (!projectedInside != geographicInside) invertedErrors++
    }
    return invertedErrors < normalErrors
}

internal fun polygonContains(
    polygon: List<Pair<Float, Float>>,
    x: Float,
    y: Float
): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var previous = polygon.last()
    for (current in polygon) {
        val crossesRay = (current.second > y) != (previous.second > y)
        if (crossesRay) {
            val intersectionX = (previous.first - current.first) *
                (y - current.second) / (previous.second - current.second) +
                current.first
            if (x < intersectionX) inside = !inside
        }
        previous = current
    }
    return inside
}

internal fun ringContainsLocation(
    ring: LandData.Ring,
    latitude: Double,
    longitude: Double
): Boolean {
    if (ring.size < 3) return false

    fun relativeLongitude(value: Float): Double =
        GlobeMath.normalizeLon(value.toDouble() - longitude)

    var inside = false
    var previousIndex = ring.size - 1
    for (index in 0 until ring.size) {
        val currentLat = ring.coords[index * 2].toDouble()
        val currentLon = relativeLongitude(ring.coords[index * 2 + 1])
        val previousLat = ring.coords[previousIndex * 2].toDouble()
        val previousLon = relativeLongitude(ring.coords[previousIndex * 2 + 1])
        val crossesRay = (currentLat > latitude) != (previousLat > latitude)
        if (crossesRay) {
            val intersectionLon = (previousLon - currentLon) *
                (latitude - currentLat) / (previousLat - currentLat) +
                currentLon
            if (0.0 < intersectionLon) inside = !inside
        }
        previousIndex = index
    }
    return inside
}

private fun DrawScope.strokeRuns(
    runs: List<MutableList<Pair<Float, Float>>>,
    cx: Float, cy: Float, r: Float,
    color: Color,
    width: Float,
    clip: ClipRect
) {
    for (run in runs) {
        if (run.size < 2) continue
        val path = Path()
        for (k in 1 until run.size) {
            val seg = clipSegment(
                cx + run[k - 1].first * r, cy + run[k - 1].second * r,
                cx + run[k].first * r, cy + run[k].second * r,
                clip
            ) ?: continue
            path.moveTo(seg.first.first, seg.first.second)
            path.lineTo(seg.second.first, seg.second.second)
        }
        drawPath(path, color, style = Stroke(width = width))
    }
}

private fun DrawScope.drawBorderLine(
    line: LandData.Ring,
    scratch: FloatArray,
    projector: GlobeMath.PreparedProjector,
    cx: Float, cy: Float, r: Float,
    colors: GlobeColors,
    clip: ClipRect
) {
    val n = line.size
    if (n < 2 || n * 3 > scratch.size) return

    var anyFront = false
    val pts = ArrayList<DiscPt>(n)
    var i = 0
    while (i < n) {
        projector.project(line.projectionTerms, i * 4, scratch, i * 3)
        val x = scratch[i * 3]
        val y = scratch[i * 3 + 1]
        val cosC = scratch[i * 3 + 2]
        val front = cosC > 0.005f
        pts.add(DiscPt(x, y, front))
        if (cosC >= 0f) anyFront = true
        i++
    }
    if (!anyFront) return

    val runs = buildFrontRuns(pts, closed = false)
    strokeRuns(runs, cx, cy, r, colors.border, 1.2f, clip)
}

private fun DrawScope.drawCities(
    cities: List<LandData.City>,
    state: GlobeState,
    projector: GlobeMath.PreparedProjector,
    cx: Float, cy: Float, r: Float,
    colors: GlobeColors,
    labelPaint: Paint,
    haloPaint: Paint,
    typeface: Typeface?,
    textSize: Float,
    density: Float,
    maxRankOverride: Int?,
    showLabels: Boolean
) {
    if (cities.isEmpty()) return
    val projection = FloatArray(3)
    val zoom = state.zoom
    val maxRank = maxRankOverride ?: when {
        zoom < 2f -> 1
        zoom < 8f -> 3
        zoom < 40f -> 4
        else -> 10
    }
    val canvas = drawContext.canvas.nativeCanvas
    for (city in cities) {
        if (city.rank > maxRank) continue
        projector.project(city.projectionTerms, 0, projection, 0)
        val cosC = projection[2]
        if (cosC < 0.03f) continue
        val sx = cx + projection[0] * r
        val sy = cy + projection[1] * r
        if (sx < -50 || sx > size.width + 50 || sy < -50 || sy > size.height + 50) continue

        val alpha = cosC.coerceIn(0.25f, 1f)
        val important = city.capital || city.megacity
        val dotRadius = (if (important) 2.6f else 1.8f) * density
        val dotColor = if (city.capital) colors.accent.copy(alpha = alpha)
            else colors.label.copy(alpha = alpha * 0.85f)
        drawCircle(dotColor, radius = dotRadius, center = Offset(sx, sy))

        if (showLabels && (zoom >= 6f || (important && zoom >= 2.5f))) {
            labelPaint.textSize = textSize
            labelPaint.typeface = typeface
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.color = android.graphics.Color.argb(
                (200 * alpha).toInt(),
                (colors.label.red * 255).toInt(), (colors.label.green * 255).toInt(), (colors.label.blue * 255).toInt()
            )
            haloPaint.textSize = textSize
            haloPaint.typeface = typeface
            haloPaint.textAlign = Paint.Align.LEFT
            haloPaint.strokeWidth = textSize * 0.16f
            haloPaint.color = android.graphics.Color.argb(
                (140 * alpha).toInt(),
                (colors.labelHalo.red * 255).toInt(), (colors.labelHalo.green * 255).toInt(), (colors.labelHalo.blue * 255).toInt()
            )
            val tx = sx + dotRadius + 3 * density
            val ty = sy - ((labelPaint.descent() + labelPaint.ascent()) / 2f)
            canvas.drawText(city.name, tx, ty, haloPaint)
            canvas.drawText(city.name, tx, ty, labelPaint)
        }
    }
    labelPaint.textAlign = Paint.Align.CENTER
    haloPaint.textAlign = Paint.Align.CENTER
}

private fun DrawScope.drawLandFill(
    ring: LandData.Ring,
    scratch: FloatArray,
    projector: GlobeMath.PreparedProjector,
    cx: Float, cy: Float, r: Float,
    colors: GlobeColors,
    clip: ClipRect,
    pointStride: Int,
    centerLat: Double,
    centerLon: Double
): List<MutableList<Pair<Float, Float>>>? {
    val n = ((ring.size - 1) / pointStride) + 1
    if (n < 3 || n * 3 > scratch.size) return null

    var anyFront = false
    var anyBack = false
    var i = 0
    while (i < n) {
        val sourceIndex = (i * pointStride).coerceAtMost(ring.size - 1)
        projector.project(ring.projectionTerms, sourceIndex * 4, scratch, i * 3)
        if (scratch[i * 3 + 2] >= 0f) {
            anyFront = true
        } else {
            anyBack = true
        }
        i++
    }
    if (!anyFront) return null

    val pts = ArrayList<DiscPt>(n)
    i = 0
    while (i < n) {
        pts.add(DiscPt(scratch[i * 3], scratch[i * 3 + 1], scratch[i * 3 + 2] > 0.005f))
        i++
    }
    val runs = buildFrontRuns(pts)
    val polygon = buildFillPolygon(pts, cx, cy, r)
    fillPolygonClipped(
        pts = pts,
        cx = cx,
        cy = cy,
        r = r,
        color = colors.land,
        clip = clip,
        invertFill = anyBack && projectedFillNeedsInversion(
                polygon = polygon,
                ring = ring,
                cx = cx,
                cy = cy,
                r = r,
                centerLat = centerLat,
                centerLon = centerLon
            ),
        preparedPolygon = polygon
    )
    return runs
}

private fun DrawScope.drawGeohashGrid(
    state: GlobeState,
    cx: Float, cy: Float, r: Float,
    cLat: Double, cLon: Double,
    colors: GlobeColors,
    clip: ClipRect,
    includeNeighbors: Boolean
) {
    val selected = state.selectedGeohash
    val cells = linkedSetOf(selected)
    if (includeNeighbors) cells.addAll(Geohash.neighborsSamePrecision(selected))

    for (cell in cells) {
        val isSelected = cell == selected
        val b = Geohash.decodeToBounds(cell)
        val spanLat = b.latMax - b.latMin
        val spanLon = b.lonMax - b.lonMin
        val steps = ceil(spanLat / 1.5).toInt().coerceIn(4, 48)

        // Sample the cell boundary: N edge l->r, E edge t->b, S edge r->l, W edge b->t
        val pts = ArrayList<Triple<Float, Float, Boolean>>(steps * 4 + 4)
        fun addPt(lat: Double, lonRaw: Double) {
            var lon = lonRaw
            // keep boundary continuous across the antimeridian relative to the view
            val ref = cLon
            while (lon - ref > 180.0) lon -= 360.0
            while (lon - ref < -180.0) lon += 360.0
            val p = GlobeMath.projectRaw(lat, lon, cLat, cLon)
            pts.add(Triple(p.x, p.y, p.cosC >= 0.005f))
        }
        for (s in 0..steps) {
            val t = s.toDouble() / steps
            addPt(b.latMax, b.lonMin + spanLon * t)
        }
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            addPt(b.latMax - spanLat * t, b.lonMax)
        }
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            addPt(b.latMin, b.lonMax - spanLon * t)
        }
        for (s in 1 until steps) {
            val t = s.toDouble() / steps
            addPt(b.latMin + spanLat * t, b.lonMin)
        }

        if (pts.none { it.third }) continue

        val discPts = pts.map { DiscPt(it.first, it.second, it.third) }
        val runs = buildFrontRuns(discPts)

        if (isSelected) {
            fillPolygonClipped(
                discPts, cx, cy, r, colors.accent.copy(alpha = 0.20f), clip
            )
            strokeRuns(runs, cx, cy, r, colors.accent.copy(alpha = 0.35f), 7f, clip)
            strokeRuns(runs, cx, cy, r, colors.accent, 3.2f, clip)
        } else {
            fillPolygonClipped(
                discPts, cx, cy, r, colors.grid.copy(alpha = 0.05f), clip
            )
            strokeRuns(runs, cx, cy, r, colors.grid, 1.6f, clip)
        }
    }
}

private fun DrawScope.drawGeohashLabels(
    state: GlobeState,
    cx: Float, cy: Float, r: Float,
    cLat: Double, cLon: Double,
    colors: GlobeColors,
    labelPaint: Paint,
    haloPaint: Paint,
    labelTypeface: Typeface?,
    labelTypefaceBold: Typeface?,
    selectedSize: Float,
    neighborSize: Float,
    includeNeighbors: Boolean
) {
    val selected = state.selectedGeohash
    val cells = linkedSetOf(selected)
    if (includeNeighbors) cells.addAll(Geohash.neighborsSamePrecision(selected))
    val canvas = drawContext.canvas.nativeCanvas

    for (cell in cells) {
        val isSelected = cell == selected
        val (lat, lon) = Geohash.decodeToCenter(cell)
        val p = GlobeMath.project(lat, lon, cLat, cLon) ?: continue
        if (p.cosC < 0.08f) continue
        val sx = cx + p.x * r
        val sy = cy + p.y * r
        val paint = labelPaint
        paint.textSize = if (isSelected) selectedSize else neighborSize
        paint.typeface = if (isSelected) (labelTypefaceBold ?: labelTypeface) else labelTypeface
        paint.color = if (isSelected) {
            android.graphics.Color.argb(255, (colors.accent.red * 255).toInt(), (colors.accent.green * 255).toInt(), (colors.accent.blue * 255).toInt())
        } else {
            android.graphics.Color.argb(
                (160 * p.cosC.coerceIn(0.4f, 1f)).toInt(),
                (colors.label.red * 255).toInt(), (colors.label.green * 255).toInt(), (colors.label.blue * 255).toInt()
            )
        }
        val baseline = sy - ((paint.descent() + paint.ascent()) / 2f)
        haloPaint.textSize = paint.textSize
        haloPaint.typeface = paint.typeface
        haloPaint.strokeWidth = paint.textSize * 0.18f
        haloPaint.color = android.graphics.Color.argb(
            if (isSelected) 200 else 120,
            (colors.labelHalo.red * 255).toInt(), (colors.labelHalo.green * 255).toInt(), (colors.labelHalo.blue * 255).toInt()
        )
        canvas.drawText(cell, sx, baseline, haloPaint)
        canvas.drawText(cell, sx, baseline, paint)
    }
}
