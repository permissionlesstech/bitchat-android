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
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
import com.bitchat.android.nostr.GeohashChannelActivity
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.math.min

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

/**
 * The globe's gradients depend only on where the globe sits and how large it is, so they are
 * rebuilt when it zooms, not on every frame of a drag.
 */
private class GlobeShading {
    private var cx = Float.NaN
    private var cy = Float.NaN
    private var r = Float.NaN
    private var colors: GlobeColors? = null

    lateinit var atmosphere: Brush
        private set
    lateinit var ocean: Brush
        private set
    lateinit var limb: Brush
        private set
    lateinit var night: Brush
        private set

    fun update(cx: Float, cy: Float, r: Float, colors: GlobeColors) {
        if (cx == this.cx && cy == this.cy && r == this.r && colors == this.colors) return
        this.cx = cx
        this.cy = cy
        this.r = r
        this.colors = colors
        atmosphere = Brush.radialGradient(
            0f to colors.atmosphere.copy(alpha = 0.30f),
            0.75f to colors.atmosphere.copy(alpha = 0.12f),
            1f to Color.Transparent,
            center = Offset(cx, cy),
            radius = r * ATMOSPHERE_SCALE
        )
        ocean = Brush.radialGradient(
            0f to colors.oceanCenter,
            0.7f to colors.oceanCenter,
            1f to colors.oceanEdge,
            center = Offset(cx - r * 0.32f, cy - r * 0.38f),
            radius = r * 1.5f
        )
        limb = Brush.radialGradient(
            0f to Color.Transparent,
            0.62f to Color.Transparent,
            0.88f to Color.Black.copy(alpha = 0.34f),
            1f to Color.Black.copy(alpha = 0.72f),
            center = Offset(cx - r * 0.25f, cy - r * 0.3f),
            radius = r * 1.35f
        )
        night = Brush.linearGradient(
            0f to Color.Transparent,
            0.55f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.42f),
            start = Offset(cx - r * 0.7f, cy - r * 0.7f),
            end = Offset(cx + r * 0.75f, cy + r * 0.8f)
        )
    }
}

/**
 * The interactive globe. It draws the same full detail whether it is moving or at rest, so
 * labels, borders and coastlines never drop out mid-drag.
 *
 * Each canvas below sits in its own graphics layer. Without one, Compose records every sibling
 * canvas into the parent's layer, so the always-running crosshair pulse re-drew the whole globe
 * on every frame even when nothing moved.
 */
@Composable
fun GlobeView(
    state: GlobeState,
    colors: GlobeColors,
    land: List<LandData.Ring>,
    borders: List<LandData.Ring>,
    cities: List<LandData.City>,
    labelTypeface: Typeface?,
    labelTypefaceBold: Typeface?,
    modifier: Modifier = Modifier,
    activity: List<GeohashChannelActivity> = emptyList(),
    activityColors: GlobeActivityColors? = null,
    onMarkerTap: ((GlobeMarker) -> Unit)? = null,
    onBackgroundTap: (() -> Unit)? = null
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

    val renderer = remember { GlobeRenderer() }
    val shading = remember { GlobeShading() }

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    }
    val haloPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
        }
    }

    val markerLayout = remember { GlobeMarkerLayout() }
    val markerAnimator = remember {
        val scale = android.provider.Settings.Global.getFloat(
            view.context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        GlobeMarkerAnimator(durationScale = scale)
    }
    val markerTextPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val markerPulse by rememberInfiniteTransition(label = "markers").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart),
        label = "markerPulse"
    )
    val currentOnMarkerTap by rememberUpdatedState(onMarkerTap)
    val currentOnBackgroundTap by rememberUpdatedState(onBackgroundTap)

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
                // Tick for cells the user drags across, not for every cell a fly-in passes.
                if (state.isAnimating) return@collect
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
        // Static background: its layer is recorded once and only replayed afterwards.
        Canvas(modifier = Modifier.matchParentSize().graphicsLayer()) {
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
                .graphicsLayer()
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

                    val tappedMarker = if (isTap) {
                        currentOnMarkerTap?.let {
                            markerLayout.hitTest(downPos, MARKER_TAP_SLOP_DP * density.density)
                        }
                    } else null
                    if (tappedMarker != null) {
                        lastTapTime = 0L
                        currentOnMarkerTap?.invoke(tappedMarker)
                    } else if (isTap) {
                        currentOnBackgroundTap?.invoke()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = state.globeRadiusPx
                        if (r > 0f) {
                            val latLon = GlobeMath.unproject(
                                ((downPos.x - cx) / r).toDouble(),
                                ((downPos.y - cy) / r).toDouble(),
                                state.centerLat,
                                state.centerLon
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
            if (state.baseRadiusPx <= 0f) return@Canvas
            val r = state.globeRadiusPx
            val cLat = state.centerLat
            val cLon = state.centerLon
            val center = Offset(cx, cy)
            val projector = GlobeMath.PreparedProjector(cLat, cLon)
            val preciseProjector = GlobeMath.PreciseProjector(cLat, cLon)

            shading.update(cx, cy, r, colors)
            drawCircle(brush = shading.atmosphere, radius = r * ATMOSPHERE_SCALE, center = center)
            drawCircle(brush = shading.ocean, radius = r, center = center)

            with(renderer) {
                beginFrame(size.width, size.height)
                drawGraticule(preciseProjector, cx, cy, r, colors.graticule)
                drawLand(
                    land = land,
                    projector = projector,
                    cx = cx,
                    cy = cy,
                    r = r,
                    centerLat = cLat,
                    centerLon = cLon,
                    fill = colors.land,
                    coastline = colors.coastline,
                    coastlineWidth = COASTLINE_WIDTH_PX
                )
                drawBorders(borders, projector, cx, cy, r, colors.border, BORDER_WIDTH_PX)
            }

            // Sphere shading: dark limb + night side for 3D depth
            drawCircle(brush = shading.limb, radius = r + 1, center = center)
            drawCircle(brush = shading.night, radius = r + 1, center = center)

            drawCities(
                cities, state, projector, cx, cy, r, colors,
                labelPaint, haloPaint, labelTypeface, labelTextSizeSmall, density.density
            )

            val selected = state.selectedGeohash
            if (selected.isNotEmpty()) {
                with(renderer) {
                    drawGeohashGrid(selected, preciseProjector, cx, cy, r, colors.accent, colors.grid)
                }
                drawGeohashLabels(
                    state, cx, cy, r, cLat, cLon, colors,
                    labelPaint, haloPaint, labelTypeface, labelTypefaceBold,
                    labelTextSize, labelTextSizeSmall,
                    includeNeighbors = true
                )
            }
        }

        // The crosshair pulses forever; its own layer keeps that from re-drawing the globe.
        Canvas(modifier = Modifier.matchParentSize().graphicsLayer()) {
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
        // Activity markers get their own layer, above the crosshair so a centered marker's
        // count stays readable; their pulse never redraws the globe geometry.
        if (activityColors != null) {
            Canvas(modifier = Modifier.matchParentSize().graphicsLayer()) {
                val markers = if (state.baseRadiusPx <= 0f || activity.isEmpty()) {
                    emptyList()
                } else {
                    layoutGlobeMarkers(
                        channels = activity,
                        projector = GlobeMath.PreparedProjector(
                            state.centerLat,
                            state.centerLon
                        ),
                        cx = size.width / 2f,
                        cy = size.height / 2f,
                        r = state.globeRadiusPx,
                        width = size.width,
                        height = size.height,
                        density = density.density
                    )
                }
                markerLayout.markers = markers
                val frames = markerAnimator.frame(markers, System.nanoTime())
                val nowSeconds = System.currentTimeMillis() / 1000
                // Read the animation clock only while something moves: entering, leaving or
                // pulsing markers. A settled, quiet globe then stops redrawing this layer.
                val anyRecent = markers.any {
                    it.messages > 0 && nowSeconds - it.lastActiveAt < MARKER_RECENT_SECONDS
                }
                val pulsePhase = if (anyRecent || markerAnimator.isAnimating) markerPulse else 0f
                drawGlobeMarkers(
                    frames = frames,
                    colors = activityColors,
                    selectedGeohash = state.selectedGeohash,
                    pulsePhase = if (anyRecent) pulsePhase else 0f,
                    nowSeconds = nowSeconds,
                    textPaint = markerTextPaint,
                    typeface = labelTypefaceBold ?: labelTypeface,
                    density = density.density
                )
            }
        }
    }
}

private const val MARKER_TAP_SLOP_DP = 12f
private const val ATMOSPHERE_SCALE = 1.22f
private const val COASTLINE_WIDTH_PX = 1.4f
private const val BORDER_WIDTH_PX = 1.2f

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
    density: Float
) {
    if (cities.isEmpty()) return
    val projection = FloatArray(3)
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
