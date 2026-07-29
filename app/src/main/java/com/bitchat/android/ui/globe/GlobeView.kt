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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
                view.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
    }

    val labelTextSize = with(density) { 12.5.sp.toPx() }
    val labelTextSizeSmall = with(density) { 10.sp.toPx() }

    Canvas(
        modifier = modifier
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
                    var maxPointers = 1
                    var moved = Offset.Zero
                    val panTimes = ArrayDeque<Long>()
                    val panVec = ArrayDeque<Offset>()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        maxPointers = maxOf(maxPointers, pressed.size)

                        val pan = event.calculatePan()
                        val zoomChange = event.calculateZoom()

                        if (pan != Offset.Zero) {
                            state.rotateBy(pan.x, pan.y)
                            moved += pan
                            val now = SystemClock.uptimeMillis()
                            panTimes.addLast(now)
                            panVec.addLast(pan)
                            while (panTimes.isNotEmpty() && now - panTimes.first() > 120) {
                                panTimes.removeFirst()
                                panVec.removeFirst()
                            }
                        }
                        if (zoomChange != 1f) {
                            state.zoomBy(zoomChange)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }

                    state.isInteracting = false
                    val upTime = SystemClock.uptimeMillis()
                    val isTap = maxPointers == 1 &&
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
                    } else if (panVec.isNotEmpty()) {
                        var sx = 0f; var sy = 0f
                        panVec.forEach { sx += it.x; sy += it.y }
                        val windowMs = (SystemClock.uptimeMillis() - panTimes.first()).coerceAtLeast(1)
                        state.fling(sx / windowMs, sy / windowMs)
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

        // Starfield
        stars.forEach { s ->
            drawCircle(
                color = colors.star.copy(alpha = s.alpha),
                radius = s.radius * density.density,
                center = Offset(s.x * size.width, s.y * size.height)
            )
        }

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

        // Graticule
        drawGraticule(cx, cy, r, cLat, cLon, colors.graticule, clip)

        // Landmasses
        for (ring in land) {
            drawLandRing(ring, scratch, cx, cy, r, cLat, cLon, colors, clip)
        }

        // Country borders
        for (line in borders) {
            drawBorderLine(line, borderScratch, cx, cy, r, cLat, cLon, colors, clip)
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

        // Cities (dots + names) over the shaded sphere
        drawCities(
            cities, state, cx, cy, r, cLat, cLon, colors,
            labelPaint, haloPaint, labelTypeface, labelTextSizeSmall, density.density
        )

        // Geohash cells
        if (state.selectedGeohash.isNotEmpty()) {
            drawGeohashGrid(state, cx, cy, r, cLat, cLon, colors, clip)
        }

        // Labels
        if (state.selectedGeohash.isNotEmpty()) {
            drawGeohashLabels(
                state, cx, cy, r, cLat, cLon, colors,
                labelPaint, haloPaint, labelTypeface, labelTypefaceBold,
                labelTextSize, labelTextSizeSmall
            )
        }

        // Center crosshair
        val crossAlpha = if (state.isInteracting) 0.9f else pulse
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

private fun DrawScope.drawGraticule(
    cx: Float, cy: Float, r: Float, cLat: Double, cLon: Double, color: Color, clip: ClipRect
) {
    val path = Path()
    val step = 4.0
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

private data class DiscPt(val x: Float, val y: Float, val front: Boolean)

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
private fun buildFrontRuns(pts: List<DiscPt>, closed: Boolean = true): List<MutableList<Pair<Float, Float>>> {
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
    clip: ClipRect
) {
    if (pts.none { it.front }) return
    val poly = buildFillPolygon(pts, cx, cy, r)
    if (poly.size < 3) return
    val clipped = clipPolygon(poly, clip)
    if (clipped.size < 3) return
    val path = Path()
    path.moveTo(clipped[0].first, clipped[0].second)
    for (k in 1 until clipped.size) {
        path.lineTo(clipped[k].first, clipped[k].second)
    }
    path.close()
    drawPath(path, color)
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
    cx: Float, cy: Float, r: Float,
    cLat: Double, cLon: Double,
    colors: GlobeColors,
    clip: ClipRect
) {
    val n = line.size
    if (n < 2 || n * 3 > scratch.size) return

    var anyFront = false
    val pts = ArrayList<DiscPt>(n)
    var i = 0
    while (i < n) {
        val lat = line.coords[i * 2].toDouble()
        val lon = line.coords[i * 2 + 1].toDouble()
        val p = GlobeMath.projectRaw(lat, lon, cLat, cLon)
        val front = p.cosC > 0.005f
        pts.add(DiscPt(p.x, p.y, front))
        if (p.cosC >= 0f) anyFront = true
        i++
    }
    if (!anyFront) return

    val runs = buildFrontRuns(pts, closed = false)
    strokeRuns(runs, cx, cy, r, colors.border, 1.2f, clip)
}

private fun DrawScope.drawCities(
    cities: List<LandData.City>,
    state: GlobeState,
    cx: Float, cy: Float, r: Float,
    cLat: Double, cLon: Double,
    colors: GlobeColors,
    labelPaint: Paint,
    haloPaint: Paint,
    typeface: Typeface?,
    textSize: Float,
    density: Float
) {
    if (cities.isEmpty()) return
    val zoom = state.zoom
    val maxRank = when {
        zoom < 2f -> 1
        zoom < 8f -> 3
        zoom < 40f -> 4
        else -> 10
    }
    val canvas = drawContext.canvas.nativeCanvas
    for (city in cities) {
        if (city.rank > maxRank) continue
        val p = GlobeMath.project(city.lat.toDouble(), city.lon.toDouble(), cLat, cLon) ?: continue
        if (p.cosC < 0.03f) continue
        val sx = cx + p.x * r
        val sy = cy + p.y * r
        if (sx < -50 || sx > size.width + 50 || sy < -50 || sy > size.height + 50) continue

        val alpha = p.cosC.coerceIn(0.25f, 1f)
        val important = city.capital || city.megacity
        val dotRadius = (if (important) 2.6f else 1.8f) * density
        val dotColor = if (city.capital) colors.accent.copy(alpha = alpha)
            else colors.label.copy(alpha = alpha * 0.85f)
        drawCircle(dotColor, radius = dotRadius, center = Offset(sx, sy))

        if (zoom >= 6f || (important && zoom >= 2.5f)) {
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

private fun DrawScope.drawLandRing(
    ring: LandData.Ring,
    scratch: FloatArray,
    cx: Float, cy: Float, r: Float,
    cLat: Double, cLon: Double,
    colors: GlobeColors,
    clip: ClipRect
) {
    val n = ring.size
    if (n < 3 || n * 3 > scratch.size) return

    var anyFront = false
    var i = 0
    while (i < n) {
        val lat = ring.coords[i * 2].toDouble()
        val lon = ring.coords[i * 2 + 1].toDouble()
        val p = GlobeMath.projectRaw(lat, lon, cLat, cLon)
        scratch[i * 3] = p.x
        scratch[i * 3 + 1] = p.y
        scratch[i * 3 + 2] = p.cosC
        if (p.cosC >= 0f) anyFront = true
        i++
    }
    if (!anyFront) return

    val pts = ArrayList<DiscPt>(n)
    i = 0
    while (i < n) {
        pts.add(DiscPt(scratch[i * 3], scratch[i * 3 + 1], scratch[i * 3 + 2] > 0.005f))
        i++
    }
    val runs = buildFrontRuns(pts)
    fillPolygonClipped(pts, cx, cy, r, colors.land, clip)
    strokeRuns(runs, cx, cy, r, colors.coastline, 1.4f, clip)
}

private fun DrawScope.drawGeohashGrid(
    state: GlobeState,
    cx: Float, cy: Float, r: Float,
    cLat: Double, cLon: Double,
    colors: GlobeColors,
    clip: ClipRect
) {
    val selected = state.selectedGeohash
    val cells = linkedSetOf(selected)
    cells.addAll(Geohash.neighborsSamePrecision(selected))

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
            fillPolygonClipped(discPts, cx, cy, r, colors.accent.copy(alpha = 0.20f), clip)
            strokeRuns(runs, cx, cy, r, colors.accent.copy(alpha = 0.35f), 7f, clip)
            strokeRuns(runs, cx, cy, r, colors.accent, 3.2f, clip)
        } else {
            fillPolygonClipped(discPts, cx, cy, r, colors.grid.copy(alpha = 0.05f), clip)
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
    neighborSize: Float
) {
    val selected = state.selectedGeohash
    val cells = linkedSetOf(selected)
    cells.addAll(Geohash.neighborsSamePrecision(selected))
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
