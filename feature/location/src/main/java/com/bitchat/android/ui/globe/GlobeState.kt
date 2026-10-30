package com.bitchat.android.ui.globe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bitchat.android.geohash.Geohash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Hoisted state for the interactive 3D globe: view center, zoom, geohash precision
 * and the current selection. All mutation funnels through this class so rendering,
 * gestures and buttons stay in sync.
 */
@Stable
class GlobeState(
    targetLat: Double,
    targetLon: Double,
    initialPrecision: Int,
    startZoomedOut: Boolean
) {
    // Double, not Float: at deep zoom one Float step of latitude spans several pixels, which made
    // panning and fly-ins visibly step.
    var centerLat by mutableDoubleStateOf(if (startZoomedOut) targetLat * 0.4 else targetLat)
        private set
    var centerLon by mutableDoubleStateOf(if (startZoomedOut) GlobeMath.normalizeLon(targetLon - 70.0) else targetLon)
        private set
    // The opening rotation starts a little smaller than the whole-globe view and grows into it.
    var zoom by mutableFloatStateOf(if (startZoomedOut) INTRO_START_ZOOM else 1f)
        private set
    var precision by mutableIntStateOf(initialPrecision.coerceIn(1, GlobeMath.MAX_PRECISION))
        private set
    var selectedGeohash by mutableStateOf("")
        private set
    var isInteracting by mutableStateOf(false)
        internal set
    var isAnimating by mutableStateOf(false)
        private set

    internal var baseRadiusPx by mutableFloatStateOf(0f)
    internal var screenMinPx by mutableFloatStateOf(0f)

    private var scope: CoroutineScope? = null
    private var animJob: Job? = null
    private var animationGeneration = 0

    /** Pending cinematic intro target (lat, lon, precision); consumed when played. */
    var introTarget: Triple<Double, Double, Int>? = null
    private var introPlayed = false

    fun playPendingIntroIfAny() {
        if (introPlayed) return
        val target = introTarget ?: return
        introPlayed = true
        introTarget = null
        playIntro(target.first, target.second, target.third)
    }

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    fun setViewport(baseRadiusPx: Float, screenMinPx: Float) {
        if (baseRadiusPx <= 0f || screenMinPx <= 0f) return
        this.baseRadiusPx = baseRadiusPx
        this.screenMinPx = screenMinPx
        syncSelection()
    }

    val globeRadiusPx: Float get() = baseRadiusPx * zoom

    /**
     * Where the opening rotation lands: zoomed in toward the region, but kept a margin below the
     * zoom at which two-letter cells take over, so the selection stays at level 1.
     */
    internal fun introEndZoom(): Float =
        GlobeMath.thresholdZoomForPrecision(2, baseRadiusPx, screenMinPx) * INTRO_END_THRESHOLD_FRACTION

    /** Direct rotation from drag gestures. Deltas are in screen px. */
    fun rotateBy(dxPx: Float, dyPx: Float) {
        val r = globeRadiusPx
        if (r <= 0f) return
        val degPerPx = 180.0 / (Math.PI * r)
        centerLon = GlobeMath.normalizeLon(centerLon - dxPx * degPerPx)
        centerLat = (centerLat + dyPx * degPerPx).coerceIn(MIN_LAT, MAX_LAT)
        syncSelection()
    }

    /** Continuous zoom from pinch gestures; precision follows automatically. */
    fun zoomBy(factor: Float) {
        if (factor == 1f) return
        // An interrupted intro can leave the globe below the minimum; continue from there rather
        // than snapping up, while never pinching further out than the current size.
        zoom = (zoom * factor).coerceIn(minOf(GlobeMath.MIN_ZOOM, zoom), GlobeMath.MAX_ZOOM)
        syncPrecisionFromZoom()
        syncSelection()
    }

    private fun syncPrecisionFromZoom() {
        if (baseRadiusPx <= 0f) return
        precision = GlobeMath.autoPrecision(globeRadiusPx, screenMinPx)
    }

    /**
     * Animate the view to a lat/lon, optionally to a zoom and precision.
     *
     * A large zoom change flies along [GlobeFlight] so on-screen motion stays even, and takes
     * longer the further it travels; [durationMs] is then a minimum. Precision follows the zoom
     * on the way and lands on [targetPrecision] at the end, so fine cells are not re-selected on
     * every frame while the globe is still far out.
     */
    fun animateTo(
        lat: Double,
        lon: Double,
        targetZoom: Float? = null,
        targetPrecision: Int? = null,
        durationMs: Int = 550,
        /** Rotate and zoom together over [durationMs] even for a large zoom change. */
        glide: Boolean = false
    ) {
        val s = scope ?: return
        animJob?.cancel()
        val startLat = centerLat
        val startLon = centerLon
        val endLat = lat.coerceIn(MIN_LAT, MAX_LAT)
        val endLon = GlobeMath.normalizeLon(lon)
        val startZoom = zoom
        val endZoom = (targetZoom ?: zoom).coerceIn(GlobeMath.MIN_ZOOM, GlobeMath.MAX_ZOOM)
        val zoomRatio = endZoom / startZoom
        val zoomFloor = minOf(GlobeMath.MIN_ZOOM, startZoom)
        val flight = if (!glide && baseRadiusPx > 0f && (zoomRatio > FLIGHT_ZOOM_RATIO || zoomRatio < 1f / FLIGHT_ZOOM_RATIO)) {
            GlobeFlight(
                distance = GlobeFlight.arc(startLat, startLon, endLat, endLon),
                startWidth = visibleWidth(startZoom),
                endWidth = visibleWidth(endZoom)
            )
        } else null
        val duration = if (flight == null) durationMs else
            (flight.pathLength * FLIGHT_MS_PER_UNIT).toInt().coerceIn(durationMs, MAX_FLIGHT_MS)
        val dLon = GlobeMath.normalizeLon(endLon - startLon)
        val generation = ++animationGeneration
        isAnimating = true
        animJob = s.launch {
            try {
                val anim = Animatable(0f)
                anim.animateTo(1f, tween(duration, easing = FastOutSlowInEasing)) {
                    val t = value.toDouble()
                    if (flight != null) {
                        val (fraction, width) = flight.at(t)
                        val (fLat, fLon) = GlobeFlight.slerp(startLat, startLon, endLat, endLon, fraction)
                        centerLat = fLat.coerceIn(MIN_LAT, MAX_LAT)
                        centerLon = fLon
                        zoom = zoomForWidth(width, zoomFloor)
                    } else {
                        centerLat = (startLat + (endLat - startLat) * t).coerceIn(MIN_LAT, MAX_LAT)
                        centerLon = GlobeMath.normalizeLon(startLon + dLon * t)
                        // Exponential interpolation feels natural for small zoom changes.
                        zoom = startZoom * zoomRatio.pow(value)
                    }
                    syncPrecisionFromZoom()
                    syncSelection()
                }
                // Land exactly on the target once the motion completes.
                centerLat = endLat
                centerLon = endLon
                zoom = endZoom
                if (targetPrecision != null) {
                    precision = targetPrecision.coerceIn(1, GlobeMath.MAX_PRECISION)
                } else {
                    syncPrecisionFromZoom()
                }
                syncSelection()
            } finally {
                if (animationGeneration == generation) {
                    isAnimating = false
                    animJob = null
                }
            }
        }
    }

    /** Angular span across the screen's short side at [zoom], radians. */
    private fun visibleWidth(zoom: Float): Double = screenMinPx.toDouble() / (baseRadiusPx * zoom)

    /** [floor] lets a flight that starts below the minimum (an interrupted intro) begin there. */
    private fun zoomForWidth(width: Double, floor: Float): Float =
        (screenMinPx / (width * baseRadiusPx)).toFloat().coerceIn(floor, GlobeMath.MAX_ZOOM)

    /** Button-driven precision change: adjusts precision and animates zoom to frame it. */
    fun animatePrecision(newPrecision: Int) {
        val p = newPrecision.coerceIn(1, GlobeMath.MAX_PRECISION)
        if (p == precision || baseRadiusPx <= 0f) return
        val targetZoom = GlobeMath.zoomForPrecision(p, baseRadiusPx, screenMinPx)
        animateTo(centerLat, centerLon, targetZoom, p, durationMs = 450)
    }

    /**
     * Whether − can widen the view. Level 1 spans a range of zooms (the opening rotation lands
     * well inside it), so it can still pull back to the whole globe without changing precision.
     */
    val canZoomOut: Boolean
        get() = precision > 1 || zoom > GlobeMath.MIN_ZOOM * (1f + WHOLE_GLOBE_ZOOM_TOLERANCE)

    /** The − button: one level coarser, or the whole globe when already at level 1. */
    fun zoomOut() {
        if (!canZoomOut || baseRadiusPx <= 0f) return
        if (precision > 1) {
            animatePrecision(precision - 1)
        } else {
            animateTo(centerLat, centerLon, GlobeMath.MIN_ZOOM, 1, durationMs = 450)
        }
    }

    /**
     * Cinematic intro: turn to the target while zooming from [INTRO_START_ZOOM] to [introEndZoom],
     * rotation and zoom together as one move, landing on [targetPrecision].
     */
    private fun playIntro(targetLat: Double, targetLon: Double, targetPrecision: Int) {
        if (baseRadiusPx <= 0f) {
            // viewport not ready yet; retry once attached to layout via caller
            return
        }
        animateTo(targetLat, targetLon, introEndZoom(), targetPrecision, durationMs = 1400, glide = true)
    }

    /** Inertial spin after a fling. Velocities are in px/second. */
    fun fling(velocityX: Float, velocityY: Float) {
        val s = scope ?: return
        var initialVx = velocityX.coerceIn(-MAX_FLING_PX_PER_SECOND, MAX_FLING_PX_PER_SECOND)
        var initialVy = velocityY.coerceIn(-MAX_FLING_PX_PER_SECOND, MAX_FLING_PX_PER_SECOND)
        if (abs(initialVx) < MIN_FLING_PX_PER_SECOND) initialVx = 0f
        if (abs(initialVy) < MIN_FLING_PX_PER_SECOND) initialVy = 0f
        if (initialVx == 0f && initialVy == 0f) return
        animJob?.cancel()
        val generation = ++animationGeneration
        isAnimating = true
        animJob = s.launch {
            try {
                var vx = initialVx
                var vy = initialVy
                var lastFrameNanos = withFrameNanos { it }
                while (abs(vx) >= MIN_FLING_PX_PER_SECOND || abs(vy) >= MIN_FLING_PX_PER_SECOND) {
                    val frameNanos = withFrameNanos { it }
                    val dtSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000f)
                        .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
                    lastFrameNanos = frameNanos
                    val maxStep = globeRadiusPx * MAX_FLING_RADIUS_FRACTION_PER_FRAME
                    rotateBy(
                        (vx * dtSeconds).coerceIn(-maxStep, maxStep),
                        (vy * dtSeconds).coerceIn(-maxStep, maxStep)
                    )
                    val decay = exp(-FLING_FRICTION_PER_SECOND * dtSeconds)
                    vx *= decay
                    vy *= decay
                }
            } finally {
                if (animationGeneration == generation) {
                    isAnimating = false
                    animJob = null
                }
            }
        }
    }

    fun cancelAnimations() {
        animationGeneration++
        animJob?.cancel()
        animJob = null
        isAnimating = false
    }

    val isInMotion: Boolean get() = isInteracting || isAnimating

    private companion object {
        // Close to the projection limit so polar geohash cells remain selectable;
        // clamping tighter would make syncSelection() encode the wrong cell.
        const val MIN_LAT = -89.0
        const val MAX_LAT = 89.0
        /** Where the opening rotation's zoom-in starts: a little smaller than the whole globe. */
        const val INTRO_START_ZOOM = 0.72f
        /** How far toward the precision-2 threshold the opening rotation zooms. */
        const val INTRO_END_THRESHOLD_FRACTION = 0.8f
        /** Zoom within this fraction of the minimum already counts as the whole globe. */
        const val WHOLE_GLOBE_ZOOM_TOLERANCE = 0.01f
        /** Zoom changes beyond this factor fly along [GlobeFlight]; smaller ones glide. */
        const val FLIGHT_ZOOM_RATIO = 1.5f
        const val FLIGHT_MS_PER_UNIT = 230.0
        const val MAX_FLIGHT_MS = 2_200
        const val MIN_FLING_PX_PER_SECOND = 90f
        const val MAX_FLING_PX_PER_SECOND = 3_200f
        const val MAX_FRAME_DELTA_SECONDS = 1f / 30f
        const val MAX_FLING_RADIUS_FRACTION_PER_FRAME = 0.12f
        const val FLING_FRICTION_PER_SECOND = 4.2f
    }

    private fun syncSelection() {
        if (baseRadiusPx <= 0f) return
        val gh = Geohash.encode(centerLat, centerLon, precision)
        if (gh != selectedGeohash) selectedGeohash = gh
    }
}
