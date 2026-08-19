package com.bitchat.android.ui.globe

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.graphics.withScale
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
    val showGraticule: Boolean,
    val landPointStride: Int,
    val minimumLandRingRadiusPx: Float,
    val showBorders: Boolean,
    val cityMaxRank: Int?,
    val showCityLabels: Boolean,
    val showStateLabels: Boolean,
    val maximumBoundaryLabels: Int?,
    val showGeohashGrid: Boolean,
    val showNeighborCells: Boolean
)

internal enum class GlobeMotionDetail {
    FULL,
    FAST
}

internal enum class MapLabelTransitionPhase {
    STABLE,
    ENTERING,
    EXITING
}

internal data class AnimatedMapLabel(
    val label: MapLabel,
    val phase: MapLabelTransitionPhase
)

internal data class MapLabelTransitionSet(
    val labels: List<AnimatedMapLabel>,
    val hasChanges: Boolean
)

private data class MapLabelAnimationKey(
    val name: String,
    val latitudeBits: Int,
    val longitudeBits: Int,
    val kind: MapLabelKind
)

private fun MapLabel.animationKey(): MapLabelAnimationKey =
    MapLabelAnimationKey(
        name = name,
        latitudeBits = lat.toBits(),
        longitudeBits = lon.toBits(),
        kind = kind
    )

internal fun buildMapLabelTransition(
    previous: List<MapLabel>,
    current: List<MapLabel>
): MapLabelTransitionSet {
    val previousKeys = previous.mapTo(HashSet(previous.size)) { it.animationKey() }
    val currentKeys = current.mapTo(HashSet(current.size)) { it.animationKey() }
    val labels = ArrayList<AnimatedMapLabel>(previous.size + current.size)
    current.forEach { label ->
        labels += AnimatedMapLabel(
            label = label,
            phase = if (label.animationKey() in previousKeys) {
                MapLabelTransitionPhase.STABLE
            } else {
                MapLabelTransitionPhase.ENTERING
            }
        )
    }
    previous.forEach { label ->
        if (label.animationKey() !in currentKeys) {
            labels += AnimatedMapLabel(label, MapLabelTransitionPhase.EXITING)
        }
    }
    return MapLabelTransitionSet(
        labels = labels,
        hasChanges = labels.any { it.phase != MapLabelTransitionPhase.STABLE }
    )
}

internal fun globeFrameDetail(motionDetail: GlobeMotionDetail): GlobeFrameDetail =
    when (motionDetail) {
        GlobeMotionDetail.FULL -> GlobeFrameDetail(
            showGraticule = true,
            landPointStride = 1,
            minimumLandRingRadiusPx = 0f,
            showBorders = true,
            cityMaxRank = null,
            showCityLabels = true,
            showStateLabels = true,
            maximumBoundaryLabels = null,
            showGeohashGrid = true,
            showNeighborCells = true
        )
        GlobeMotionDetail.FAST -> GlobeFrameDetail(
            showGraticule = true,
            landPointStride = 1,
            minimumLandRingRadiusPx = 0f,
            showBorders = true,
            cityMaxRank = null,
            showCityLabels = true,
            showStateLabels = true,
            maximumBoundaryLabels = null,
            showGeohashGrid = true,
            showNeighborCells = true
        )
    }

@Composable
fun GlobeView(
    state: GlobeState,
    colors: GlobeColors,
    mapData: GlobeMapData,
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

    val maxRingPoints = remember(mapData.oceanPolygons) {
        mapData.oceanPolygons.maxOfOrNull { polygon ->
            polygon.rings.maxOfOrNull { it.size } ?: 0
        } ?: 0
    }
    val scratch = remember(maxRingPoints) { FloatArray(maxOf(1, maxRingPoints) * 3) }
    val borderScratch = remember { FloatArray(3) }
    val borderPaths = remember { BorderPaths() }
    val fullGraticule = remember { prepareGraticule(4.0) }
    val placeTransitionProgress = remember { Animatable(1f) }
    val currentPlaceLabels = remember { mutableStateOf(emptyList<MapLabel>()) }
    val animatedPlaceLabels = remember {
        mutableStateOf(emptyList<AnimatedMapLabel>())
    }
    val placeLabelKeys = remember(mapData.placeLabels) {
        mapData.placeLabels.map { it.animationKey() }
    }

    LaunchedEffect(placeLabelKeys) {
        val nextLabels = mapData.placeLabels
        val transition = buildMapLabelTransition(
            previous = currentPlaceLabels.value,
            current = nextLabels
        )
        currentPlaceLabels.value = nextLabels
        animatedPlaceLabels.value = transition.labels
        if (transition.hasChanges) {
            placeTransitionProgress.snapTo(0f)
            placeTransitionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PLACE_LABEL_TRANSITION_MILLIS,
                    easing = LinearOutSlowInEasing
                )
            )
        } else {
            placeTransitionProgress.snapTo(1f)
        }
        animatedPlaceLabels.value = nextLabels.map {
            AnimatedMapLabel(it, MapLabelTransitionPhase.STABLE)
        }
    }

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    }
    val haloPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
        }
    }

    LaunchedEffect(state) {
        // Fire intro once the viewport size is known.
        snapshotFlow { state.baseRadiusPx }.first { it > 0f }
        state.playPendingIntroIfAny()
    }

    LaunchedEffect(state) {
        var lastHapticMillis = 0L
        snapshotFlow { state.selectedGeohash }
            .drop(1)
            .collect {
                val now = SystemClock.uptimeMillis()
                if (now - lastHapticMillis < MIN_HAPTIC_INTERVAL_MILLIS) {
                    return@collect
                }
                lastHapticMillis = now
                @Suppress("DEPRECATION")
                view.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
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
                state.setViewport(minDim * 0.44f, minDim, size.width, size.height)
            }
            .pointerInput(state) {
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown()
                    state.cancelAnimations()
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
                                state.isInteracting = true
                            }
                            if (dragStarted) state.rotateBy(pan.x, pan.y)
                        }
                        if (zoomChange != 1f) {
                            state.isInteracting = true
                            state.zoomBy(zoomChange)
                        }
                        if (!hadMultiplePointers && pressed.size == 1) {
                            val change = pressed[0]
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }

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
                    state.isInteracting = false
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
        val viewBounds = projectedViewBounds(cLat, cLon, cx, cy, r)

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

        val oceanBrush = Brush.radialGradient(
            0f to colors.oceanCenter,
            0.7f to colors.oceanCenter,
            1f to colors.oceanEdge,
            center = Offset(cx - r * 0.32f, cy - r * 0.38f),
            radius = r * 1.5f
        )
        val clip = ClipRect(
            -GEOMETRY_CLIP_MARGIN_PX,
            -GEOMETRY_CLIP_MARGIN_PX,
            size.width + GEOMETRY_CLIP_MARGIN_PX,
            size.height + GEOMETRY_CLIP_MARGIN_PX
        )

        val frameDetail = globeFrameDetail(
            if (state.isInMotion) GlobeMotionDetail.FAST else GlobeMotionDetail.FULL
        )

        val projectedOcean = if (mapData.oceanPolygons.isNotEmpty()) {
            buildProjectedOcean(
                polygons = mapData.oceanPolygons,
                scratch = scratch,
                projector = preparedProjector,
                cx = cx,
                cy = cy,
                r = r,
                clip = clip,
                pointStride = frameDetail.landPointStride,
                minimumRingRadiusPx = frameDetail.minimumLandRingRadiusPx,
                centerLat = cLat,
                centerLon = cLon,
                viewBounds = viewBounds
            )
        } else {
            null
        }

        if (projectedOcean == null) {
            // Until the first streamed world tile arrives, keep the sphere present without
            // substituting any bundled geography.
            drawCircle(brush = oceanBrush, radius = r, center = Offset(cx, cy))
            if (frameDetail.showGraticule) {
                drawGraticule(
                    cx = cx,
                    cy = cy,
                    r = r,
                    projector = preparedProjector,
                    color = colors.graticule,
                    clip = clip,
                    graticule = fullGraticule
                )
            }
        } else {
            // The streamed ocean mask lets the existing palette remain unchanged: land is
            // the base sphere and water receives the same radial treatment as before.
            drawCircle(color = colors.land, radius = r, center = Offset(cx, cy))
            drawPath(projectedOcean.path, brush = oceanBrush)
            if (frameDetail.showGraticule) {
                clipPath(projectedOcean.path) {
                    drawGraticule(
                        cx = cx,
                        cy = cy,
                        r = r,
                        projector = preparedProjector,
                        color = colors.graticule,
                        clip = clip,
                        graticule = fullGraticule
                    )
                }
            }
        }

        // Keep country and state lines stable across touch, drag, and animation frames.
        if (frameDetail.showBorders) {
            drawBorders(
                lines = mapData.borders,
                scratch = borderScratch,
                paths = borderPaths,
                projector = preparedProjector,
                cx = cx,
                cy = cy,
                r = r,
                colors = colors,
                clip = clip,
                viewBounds = viewBounds
            )
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

        val occupiedLabelRects = ArrayList<Rect>()
        if (frameDetail.showCityLabels) {
            drawBoundaryLabels(
                mapData.boundaryLabels,
                state,
                preparedProjector,
                cx,
                cy,
                r,
                colors,
                labelPaint,
                haloPaint,
                labelTypefaceBold,
                labelTextSizeSmall,
                density.density,
                occupiedLabelRects,
                showStateLabels = frameDetail.showStateLabels,
                maximumLabels = frameDetail.maximumBoundaryLabels
            )
        }

        // Keep place density stable so tap-to-focus does not visibly swap map layers.
        val cityMaxRank = frameDetail.cityMaxRank
        if (cityMaxRank == null || cityMaxRank >= 0) {
            drawPlaces(
                animatedPlaceLabels.value, state, preparedProjector, cx, cy, r, colors,
                labelPaint, haloPaint, labelTypeface, labelTextSizeSmall, density.density,
                maxRankOverride = cityMaxRank,
                showLabels = frameDetail.showCityLabels,
                occupiedLabelRects = occupiedLabelRects,
                transitionProgress = placeTransitionProgress.value
            )
        }

        // Keep the selected and neighboring cells continuous throughout globe movement.
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

        // Keep the crosshair in its own retained node. A decorative infinite pulse caused
        // continuous whole-window frames and repeatedly rasterized the full-detail map.
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val crossColor = colors.accent.copy(alpha = 0.9f)
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

private const val MIN_HAPTIC_INTERVAL_MILLIS = 80L
private const val PLACE_LABEL_TRANSITION_MILLIS = 180

private data class PreparedGraticule(
    val latitudeLines: List<FloatArray>,
    val longitudeLines: List<FloatArray>
)

private fun prepareGraticule(step: Double): PreparedGraticule {
    val latitudeLines = ArrayList<FloatArray>()
    var latitude = -75.0
    while (latitude <= 75.0) {
        val coordinates = ArrayList<Float>()
        var longitude = -180.0
        while (longitude <= 180.0) {
            coordinates.add(latitude.toFloat())
            coordinates.add(longitude.toFloat())
            longitude += step
        }
        latitudeLines.add(
            prepareProjectionTerms(coordinates.toFloatArray(), coordinates.size / 2)
        )
        latitude += 15.0
    }

    val longitudeLines = ArrayList<FloatArray>()
    var longitude = -180.0
    while (longitude < 180.0) {
        val coordinates = ArrayList<Float>()
        var lineLatitude = -90.0
        while (lineLatitude <= 90.0) {
            coordinates.add(lineLatitude.toFloat())
            coordinates.add(longitude.toFloat())
            lineLatitude += step
        }
        longitudeLines.add(
            prepareProjectionTerms(coordinates.toFloatArray(), coordinates.size / 2)
        )
        longitude += 15.0
    }
    return PreparedGraticule(latitudeLines, longitudeLines)
}

private fun DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    r: Float,
    projector: GlobeMath.PreparedProjector,
    color: Color,
    clip: ClipRect,
    graticule: PreparedGraticule
) {
    val path = Path()
    val projection = FloatArray(3)
    fun strokeSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
        val seg = clipSegment(x0, y0, x1, y1, clip) ?: return
        path.moveTo(seg.first.first, seg.first.second)
        path.lineTo(seg.second.first, seg.second.second)
    }

    fun addLines(lines: List<FloatArray>) {
        for (line in lines) {
            var hasPrevious = false
            var previousX = 0f
            var previousY = 0f
            val pointCount = line.size / 4
            for (index in 0 until pointCount) {
                projector.project(line, index * 4, projection, 0)
                if (projection[2] > 0.02f) {
                    val x = cx + projection[0] * r
                    val y = cy + projection[1] * r
                    if (hasPrevious) {
                        strokeSegment(previousX, previousY, x, y)
                    }
                    previousX = x
                    previousY = y
                    hasPrevious = true
                } else {
                    hasPrevious = false
                }
            }
        }
    }
    addLines(graticule.latitudeLines)
    addLines(graticule.longitudeLines)
    drawPath(path, color, style = Stroke(width = 1f))
}

internal data class DiscPt(
    val x: Float,
    val y: Float,
    val front: Boolean,
    val depth: Float = if (front) 1f else -1f
)

private data class ProjectedOcean(
    val path: Path
)

private data class ProjectedViewBounds(
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val angularRadius: Float
)

private fun projectedViewBounds(
    centerLat: Double,
    centerLon: Double,
    cx: Float,
    cy: Float,
    r: Float
): ProjectedViewBounds {
    val centerLatitudeRadians = Math.toRadians(centerLat)
    val centerLongitudeRadians = Math.toRadians(centerLon)
    return ProjectedViewBounds(
        centerX = (
            kotlin.math.cos(centerLatitudeRadians) *
                kotlin.math.cos(centerLongitudeRadians)
            ).toFloat(),
        centerY = (
            kotlin.math.cos(centerLatitudeRadians) *
                kotlin.math.sin(centerLongitudeRadians)
            ).toFloat(),
        centerZ = kotlin.math.sin(centerLatitudeRadians).toFloat(),
        angularRadius = kotlin.math.asin(
            (kotlin.math.sqrt(cx * cx + cy * cy) / r).coerceIn(0f, 1f)
        )
    )
}

internal fun limbPoint(first: DiscPt, second: DiscPt): Pair<Float, Float> {
    val depthDelta = first.depth - second.depth
    val t = if (kotlin.math.abs(depthDelta) > 1e-6f) {
        (first.depth / depthDelta).coerceIn(0f, 1f)
    } else {
        0.5f
    }
    val x = first.x + (second.x - first.x) * t
    val y = first.y + (second.y - first.y) * t
    val length = kotlin.math.sqrt(x * x + y * y)
    return if (length > 1e-6f) {
        (x / length) to (y / length)
    } else {
        0f to -1f
    }
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
// which happens at high globe zoom. Clipping to the visible viewport also keeps
// transient path masks small enough that motion cannot evict the globe or glyphs.
private const val GEOMETRY_CLIP_MARGIN_PX = 4f

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
internal fun buildFillPolygon(
    pts: List<DiscPt>,
    cx: Float, cy: Float, r: Float
): List<Pair<Float, Float>> {
    val firstFrontIndex = pts.indexOfFirst { it.front }
    if (firstFrontIndex < 0) return emptyList()
    val out = ArrayList<Pair<Float, Float>>(pts.size + 128)

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

    fun addProjected(point: DiscPt) {
        out.add((cx + point.x * r) to (cy + point.y * r))
    }

    var previous = pts[firstFrontIndex]
    addProjected(previous)
    var exitAngle: Float? = null
    for (step in 1..pts.size) {
        val current = pts[(firstFrontIndex + step) % pts.size]
        when {
            previous.front && current.front -> addProjected(current)
            previous.front && !current.front -> {
                val limb = limbPoint(previous, current)
                out.add((cx + limb.first * r) to (cy + limb.second * r))
                exitAngle = kotlin.math.atan2(limb.second, limb.first)
            }
            !previous.front && current.front -> {
                val limb = limbPoint(previous, current)
                val entryAngle = kotlin.math.atan2(limb.second, limb.first)
                exitAngle?.let { addArc(it, entryAngle) }
                out.add((cx + limb.first * r) to (cy + limb.second * r))
                addProjected(current)
                exitAngle = null
            }
        }
        previous = current
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
    ring: GeoRing,
    cx: Float,
    cy: Float,
    r: Float,
    centerLat: Double,
    centerLon: Double,
    quickCheck: Boolean = false
): Boolean {
    // A single center-point check is ambiguous for a large polygon and caused an
    // occasional whole-disc fill. Compare several visible points with the original
    // geographic ring, and invert only when the opposite fill wins clearly.
    val samples = if (quickCheck) QUICK_FILL_SAMPLES else FULL_FILL_SAMPLES
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

private val QUICK_FILL_SAMPLES = arrayOf(
    0f to 0f,
    -0.5f to 0f,
    0.5f to 0f
)

private val FULL_FILL_SAMPLES = arrayOf(
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
    ring: GeoRing,
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

private class BorderPaths {
    val maritime = Path()
    val maritimeDisputed = Path()
    val country = Path()
    val countryDisputed = Path()
    val local = Path()
    val localDisputed = Path()

    fun reset() {
        maritime.reset()
        maritimeDisputed.reset()
        country.reset()
        countryDisputed.reset()
        local.reset()
        localDisputed.reset()
    }

    fun forLine(line: BorderLine): Path = when {
        line.maritime && line.disputed -> maritimeDisputed
        line.maritime -> maritime
        line.adminLevel == 2 && line.disputed -> countryDisputed
        line.adminLevel == 2 -> country
        line.disputed -> localDisputed
        else -> local
    }
}

private fun DrawScope.drawBorders(
    lines: List<BorderLine>,
    scratch: FloatArray,
    paths: BorderPaths,
    projector: GlobeMath.PreparedProjector,
    cx: Float, cy: Float, r: Float,
    colors: GlobeColors,
    clip: ClipRect,
    viewBounds: ProjectedViewBounds
) {
    paths.reset()
    for (line in lines) {
        val ring = line.ring
        if (
            ring.size < 2 ||
            !ring.sphericalBounds.mayIntersectView(
                viewBounds.centerX,
                viewBounds.centerY,
                viewBounds.centerZ,
                viewBounds.angularRadius
            )
        ) {
            continue
        }
        paths.forLine(line).appendProjectedBorder(
            ring = ring,
            scratch = scratch,
            projector = projector,
            cx = cx,
            cy = cy,
            r = r,
            clip = clip
        )
    }

    drawPath(paths.maritime, colors.coastline, style = Stroke(width = 1.4f))
    drawPath(
        paths.maritimeDisputed,
        colors.coastline.copy(alpha = colors.coastline.alpha * 0.65f),
        style = Stroke(width = 1.4f)
    )
    drawPath(paths.country, colors.border, style = Stroke(width = 1.2f))
    drawPath(
        paths.countryDisputed,
        colors.border.copy(alpha = colors.border.alpha * 0.65f),
        style = Stroke(width = 1.2f)
    )
    drawPath(paths.local, colors.border, style = Stroke(width = 0.9f))
    drawPath(
        paths.localDisputed,
        colors.border.copy(alpha = colors.border.alpha * 0.65f),
        style = Stroke(width = 0.9f)
    )
}

private fun Path.appendProjectedBorder(
    ring: GeoRing,
    scratch: FloatArray,
    projector: GlobeMath.PreparedProjector,
    cx: Float,
    cy: Float,
    r: Float,
    clip: ClipRect
) {
    projector.project(ring.projectionTerms, 0, scratch, 0)
    var previousX = scratch[0]
    var previousY = scratch[1]
    var previousDepth = scratch[2]
    var emittedX = previousX
    var emittedY = previousY
    var hasEmittedFrontPoint = previousDepth >= 0f
    var pathIsOpen = false
    val minimumSegmentSquared = MIN_BORDER_SEGMENT_PX * MIN_BORDER_SEGMENT_PX

    for (index in 1 until ring.size) {
        projector.project(ring.projectionTerms, index * 4, scratch, 0)
        val currentX = scratch[0]
        val currentY = scratch[1]
        val currentDepth = scratch[2]
        val previousFront = previousDepth >= 0f
        val currentFront = currentDepth >= 0f

        when {
            previousFront && currentFront -> {
                if (!hasEmittedFrontPoint) {
                    emittedX = previousX
                    emittedY = previousY
                    hasEmittedFrontPoint = true
                }
                val dx = (currentX - emittedX) * r
                val dy = (currentY - emittedY) * r
                if (
                    index == ring.size - 1 ||
                    dx * dx + dy * dy >= minimumSegmentSquared
                ) {
                    pathIsOpen = appendClippedSegment(
                        cx + emittedX * r,
                        cy + emittedY * r,
                        cx + currentX * r,
                        cy + currentY * r,
                        clip,
                        connectFromPrevious = pathIsOpen
                    )
                    emittedX = currentX
                    emittedY = currentY
                }
            }
            previousFront && !currentFront -> {
                val limb = limbPoint(
                    DiscPt(previousX, previousY, front = true, depth = previousDepth),
                    DiscPt(currentX, currentY, front = false, depth = currentDepth)
                )
                if (hasEmittedFrontPoint) {
                    appendClippedSegment(
                        cx + emittedX * r,
                        cy + emittedY * r,
                        cx + limb.first * r,
                        cy + limb.second * r,
                        clip,
                        connectFromPrevious = pathIsOpen
                    )
                }
                hasEmittedFrontPoint = false
                pathIsOpen = false
            }
            !previousFront && currentFront -> {
                val limb = limbPoint(
                    DiscPt(previousX, previousY, front = false, depth = previousDepth),
                    DiscPt(currentX, currentY, front = true, depth = currentDepth)
                )
                pathIsOpen = appendClippedSegment(
                    cx + limb.first * r,
                    cy + limb.second * r,
                    cx + currentX * r,
                    cy + currentY * r,
                    clip,
                    connectFromPrevious = false
                )
                emittedX = currentX
                emittedY = currentY
                hasEmittedFrontPoint = true
            }
        }

        previousX = currentX
        previousY = currentY
        previousDepth = currentDepth
    }
}

private const val MIN_BORDER_SEGMENT_PX = 1.5f

private fun Path.appendClippedSegment(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    clip: ClipRect,
    connectFromPrevious: Boolean
): Boolean {
    val firstInside =
        x0 in clip.left..clip.right &&
            y0 in clip.top..clip.bottom
    val secondInside =
        x1 in clip.left..clip.right &&
            y1 in clip.top..clip.bottom
    if (firstInside && secondInside) {
        if (!connectFromPrevious) moveTo(x0, y0)
        lineTo(x1, y1)
        return true
    }
    val segment = clipSegment(x0, y0, x1, y1, clip) ?: return false
    if (!(connectFromPrevious && firstInside)) {
        moveTo(segment.first.first, segment.first.second)
    }
    lineTo(segment.second.first, segment.second.second)
    return secondInside
}

private fun DrawScope.drawBoundaryLabels(
    labels: List<MapLabel>,
    state: GlobeState,
    projector: GlobeMath.PreparedProjector,
    cx: Float,
    cy: Float,
    r: Float,
    colors: GlobeColors,
    labelPaint: Paint,
    haloPaint: Paint,
    typeface: Typeface?,
    textSize: Float,
    density: Float,
    occupiedLabelRects: MutableList<Rect>,
    showStateLabels: Boolean,
    maximumLabels: Int?
) {
    if (labels.isEmpty()) return
    val projection = FloatArray(3)
    val canvas = drawContext.canvas.nativeCanvas
    var drawnLabelCount = 0
    for (label in labels) {
        if (
            label.kind == MapLabelKind.STATE &&
            (!showStateLabels || state.zoom < 6f)
        ) {
            continue
        }
        projector.project(label.projectionTerms, 0, projection, 0)
        val cosC = projection[2]
        if (cosC < 0.1f) continue
        val sx = cx + projection[0] * r
        val sy = cy + projection[1] * r
        if (sx < 0f || sx > size.width || sy < 0f || sy > size.height) continue

        val scaledTextSize = if (label.kind == MapLabelKind.COUNTRY) {
            textSize * 1.02f
        } else {
            textSize * 0.9f
        }
        configureMapLabelPaints(
            labelPaint = labelPaint,
            haloPaint = haloPaint,
            typeface = typeface,
            textSize = scaledTextSize,
            textAlign = Paint.Align.CENTER,
            colors = colors,
            alpha = cosC.coerceIn(0.3f, 1f)
        )
        val width = labelPaint.measureText(label.name)
        val height = labelPaint.descent() - labelPaint.ascent()
        val rect = Rect(
            left = sx - width / 2f - 3f * density,
            top = sy - height / 2f - 2f * density,
            right = sx + width / 2f + 3f * density,
            bottom = sy + height / 2f + 2f * density
        )
        if (!occupiedLabelRects.reserve(rect)) continue
        val baseline = sy - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label.name, sx, baseline, haloPaint)
        canvas.drawText(label.name, sx, baseline, labelPaint)
        drawnLabelCount++
        if (maximumLabels != null && drawnLabelCount >= maximumLabels) break
    }
}

private fun DrawScope.drawPlaces(
    places: List<AnimatedMapLabel>,
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
    showLabels: Boolean,
    occupiedLabelRects: MutableList<Rect>,
    transitionProgress: Float
) {
    if (places.isEmpty()) return
    val projection = FloatArray(3)
    val progress = transitionProgress.coerceIn(0f, 1f)
    val zoom = state.zoom
    val maxRank = maxRankOverride ?: when {
        zoom < 2f -> 1
        zoom < 8f -> 3
        zoom < 40f -> 4
        else -> 10
    }
    val canvas = drawContext.canvas.nativeCanvas
    for (animatedPlace in places) {
        val place = animatedPlace.label
        val visibility = when (animatedPlace.phase) {
            MapLabelTransitionPhase.STABLE -> 1f
            MapLabelTransitionPhase.ENTERING -> progress
            MapLabelTransitionPhase.EXITING -> 1f - progress
        }
        if (visibility <= 0.01f) continue
        val transitionScale = when (animatedPlace.phase) {
            MapLabelTransitionPhase.STABLE -> 1f
            MapLabelTransitionPhase.ENTERING -> 0.84f + 0.16f * progress
            MapLabelTransitionPhase.EXITING -> 1f - 0.12f * progress
        }
        if (place.rank > maxRank) continue
        projector.project(place.projectionTerms, 0, projection, 0)
        val cosC = projection[2]
        if (cosC < 0.03f) continue
        val sx = cx + projection[0] * r
        val sy = cy + projection[1] * r
        if (sx < -50 || sx > size.width + 50 || sy < -50 || sy > size.height + 50) continue

        val alpha = cosC.coerceIn(0.25f, 1f) * visibility
        val important = place.isCapital || place.isMegacity
        val dotRadius = (if (important) 2.6f else 1.8f) * density
        val dotColor = if (place.isCapital) colors.accent.copy(alpha = alpha)
            else colors.label.copy(alpha = alpha * 0.85f)
        drawCircle(
            dotColor,
            radius = dotRadius * transitionScale,
            center = Offset(sx, sy)
        )

        if (showLabels && (zoom >= 6f || (important && zoom >= 2.5f))) {
            configureMapLabelPaints(
                labelPaint = labelPaint,
                haloPaint = haloPaint,
                typeface = typeface,
                textSize = textSize,
                textAlign = Paint.Align.LEFT,
                colors = colors,
                alpha = alpha
            )
            val tx = sx + dotRadius + 3 * density
            val ty = sy - ((labelPaint.descent() + labelPaint.ascent()) / 2f)
            val width = labelPaint.measureText(place.name)
            val rect = Rect(
                left = tx - 2f * density,
                top = ty + labelPaint.ascent() - 2f * density,
                right = tx + width + 2f * density,
                bottom = ty + labelPaint.descent() + 2f * density
            )
            if (occupiedLabelRects.reserve(rect)) {
                canvas.withScale(transitionScale, transitionScale, tx, sy) {
                    drawText(place.name, tx, ty, haloPaint)
                    drawText(place.name, tx, ty, labelPaint)
                }
            }
        }
    }
    labelPaint.textAlign = Paint.Align.CENTER
    haloPaint.textAlign = Paint.Align.CENTER
}

private fun configureMapLabelPaints(
    labelPaint: Paint,
    haloPaint: Paint,
    typeface: Typeface?,
    textSize: Float,
    textAlign: Paint.Align,
    colors: GlobeColors,
    alpha: Float
) {
    labelPaint.textSize = textSize
    labelPaint.typeface = typeface
    labelPaint.textAlign = textAlign
    labelPaint.color = android.graphics.Color.argb(
        (200 * alpha).toInt(),
        (colors.label.red * 255).toInt(),
        (colors.label.green * 255).toInt(),
        (colors.label.blue * 255).toInt()
    )
    haloPaint.textSize = textSize
    haloPaint.typeface = typeface
    haloPaint.textAlign = textAlign
    haloPaint.strokeWidth = textSize * 0.16f
    haloPaint.color = android.graphics.Color.argb(
        (140 * alpha).toInt(),
        (colors.labelHalo.red * 255).toInt(),
        (colors.labelHalo.green * 255).toInt(),
        (colors.labelHalo.blue * 255).toInt()
    )
}

private fun MutableList<Rect>.reserve(candidate: Rect): Boolean {
    if (any { it.overlaps(candidate) }) return false
    add(candidate)
    return true
}

private fun buildProjectedOcean(
    polygons: List<OceanPolygon>,
    scratch: FloatArray,
    projector: GlobeMath.PreparedProjector,
    cx: Float,
    cy: Float,
    r: Float,
    clip: ClipRect,
    pointStride: Int,
    minimumRingRadiusPx: Float,
    centerLat: Double,
    centerLon: Double,
    viewBounds: ProjectedViewBounds
): ProjectedOcean? {
    // The z0 MVT ocean feature is a water multipolygon: clockwise rings are water
    // exteriors and counter-clockwise rings are land holes. The globe disc already is
    // the water exterior, so projecting only the land holes avoids sending the tile's
    // rectangular world edge through an orthographic horizon during every rotation.
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(cx - r, cy - r, cx + r, cy + r))
    }
    // Normal region/country zooms remain far below Skia's coordinate precision limit,
    // and the Canvas already clips them. Avoiding four software polygon-clipping passes
    // here removes a large amount of per-frame allocation.
    val largestPathCoordinate = maxOf(
        kotlin.math.abs(cx - r),
        kotlin.math.abs(cy - r),
        kotlin.math.abs(cx + r),
        kotlin.math.abs(cy + r)
    )
    val requiresClipping = largestPathCoordinate > MAX_SAFE_PATH_COORDINATE
    for (polygonFeature in polygons) {
        for (ring in polygonFeature.rings) {
            if (ring.isMvtExterior != false) continue
            if (
                minimumRingRadiusPx > 0f &&
                ring.sphericalBounds.angularRadius < 1.2f &&
                ring.sphericalBounds.angularRadius * r < minimumRingRadiusPx
            ) {
                continue
            }
            if (
                !ring.sphericalBounds.mayIntersectView(
                    viewBounds.centerX,
                    viewBounds.centerY,
                    viewBounds.centerZ,
                    viewBounds.angularRadius
                )
            ) {
                continue
            }
            val stride = if (ring.size >= 64) pointStride else 1
            val pointCount = ((ring.size - 1 + stride - 1) / stride) + 1
            if (pointCount < 3 || pointCount * 3 > scratch.size) continue

            var anyFront = false
            var anyBack = false
            val points = ArrayList<DiscPt>(pointCount)
            var index = 0
            while (index < pointCount) {
                val sourceIndex = (index * stride).coerceAtMost(ring.size - 1)
                projector.project(ring.projectionTerms, sourceIndex * 4, scratch, index * 3)
                val cosC = scratch[index * 3 + 2]
                if (cosC >= 0f) anyFront = true else anyBack = true
                points.add(
                    DiscPt(
                        x = scratch[index * 3],
                        y = scratch[index * 3 + 1],
                        front = cosC >= 0f,
                        depth = cosC
                    )
                )
                index++
            }

            if (!anyFront) {
                if (ringContainsLocation(ring, centerLat, centerLon)) {
                    path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                }
                continue
            }

            val projectedPolygon = buildFillPolygon(points, cx, cy, r)
            if (projectedPolygon.size < 3) continue
            val drawablePolygon = if (requiresClipping) {
                clipPolygon(projectedPolygon, clip)
            } else {
                projectedPolygon
            }
            if (drawablePolygon.size < 3) continue
            if (
                anyBack &&
                projectedFillNeedsInversion(
                    polygon = projectedPolygon,
                    ring = ring,
                    cx = cx,
                    cy = cy,
                    r = r,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    quickCheck = false
                )
            ) {
                path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            }
            path.moveTo(drawablePolygon[0].first, drawablePolygon[0].second)
            for (pointIndex in 1 until drawablePolygon.size) {
                path.lineTo(
                    drawablePolygon[pointIndex].first,
                    drawablePolygon[pointIndex].second
                )
            }
            path.close()
        }
    }

    return ProjectedOcean(path)
}

private const val MAX_SAFE_PATH_COORDINATE = 30_000f

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
        val pts = ArrayList<DiscPt>(steps * 4 + 4)
        fun addPt(lat: Double, lonRaw: Double) {
            var lon = lonRaw
            // keep boundary continuous across the antimeridian relative to the view
            val ref = cLon
            while (lon - ref > 180.0) lon -= 360.0
            while (lon - ref < -180.0) lon += 360.0
            val p = GlobeMath.projectRaw(lat, lon, cLat, cLon)
            pts.add(DiscPt(p.x, p.y, p.cosC >= 0f, depth = p.cosC))
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

        if (pts.none { it.front }) continue

        val runs = buildFrontRuns(pts)

        if (isSelected) {
            fillPolygonClipped(
                pts, cx, cy, r, colors.accent.copy(alpha = 0.20f), clip
            )
            strokeRuns(runs, cx, cy, r, colors.accent.copy(alpha = 0.35f), 7f, clip)
            strokeRuns(runs, cx, cy, r, colors.accent, 3.2f, clip)
        } else {
            fillPolygonClipped(
                pts, cx, cy, r, colors.grid.copy(alpha = 0.05f), clip
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
