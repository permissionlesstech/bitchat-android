package com.bitchat.android.ui.globe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
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
    var centerLat by mutableFloatStateOf(if (startZoomedOut) (targetLat * 0.4).toFloat() else targetLat.toFloat())
        private set
    var centerLon by mutableFloatStateOf(if (startZoomedOut) GlobeMath.normalizeLon(targetLon - 70.0).toFloat() else targetLon.toFloat())
        private set
    var zoom by mutableFloatStateOf(if (startZoomedOut) GlobeMath.MIN_ZOOM else 1f)
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

    /** Direct rotation from drag gestures. Deltas are in screen px. */
    fun rotateBy(dxPx: Float, dyPx: Float) {
        val r = globeRadiusPx
        if (r <= 0f) return
        val degPerPx = 180.0 / (Math.PI * r)
        centerLon = GlobeMath.normalizeLon(centerLon - dxPx * degPerPx).toFloat()
        centerLat = (centerLat + dyPx * degPerPx).toFloat().coerceIn(MIN_LAT, MAX_LAT)
        syncSelection()
    }

    /** Continuous zoom from pinch gestures; precision follows automatically. */
    fun zoomBy(factor: Float) {
        if (factor == 1f) return
        zoom = (zoom * factor).coerceIn(GlobeMath.MIN_ZOOM, GlobeMath.MAX_ZOOM)
        syncPrecisionFromZoom()
        syncSelection()
    }

    private fun syncPrecisionFromZoom() {
        if (baseRadiusPx <= 0f) return
        precision = GlobeMath.autoPrecision(globeRadiusPx, screenMinPx)
    }

    /** Animate the view to a lat/lon, optionally to a zoom and precision. */
    fun animateTo(
        lat: Double,
        lon: Double,
        targetZoom: Float? = null,
        targetPrecision: Int? = null,
        durationMs: Int = 550
    ) {
        val s = scope ?: return
        animJob?.cancel()
        val startLat = centerLat
        val startLon = centerLon
        val dLon = GlobeMath.normalizeLon(lon - startLon)
        val startZoom = zoom
        val endZoom = (targetZoom ?: zoom).coerceIn(GlobeMath.MIN_ZOOM, GlobeMath.MAX_ZOOM)
        val generation = ++animationGeneration
        isAnimating = true
        animJob = s.launch {
            try {
                val anim = Animatable(0f)
                anim.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) {
                    val t = value
                    centerLat = (startLat + (lat.toFloat() - startLat) * t).coerceIn(MIN_LAT, MAX_LAT)
                    centerLon = GlobeMath.normalizeLon(startLon + dLon * t).toFloat()
                    // exponential interpolation feels natural for zoom
                    zoom = startZoom * (endZoom / startZoom).pow(t)
                    if (targetPrecision != null) {
                        precision = targetPrecision.coerceIn(1, GlobeMath.MAX_PRECISION)
                    } else {
                        syncPrecisionFromZoom()
                    }
                    syncSelection()
                }
            } finally {
                if (animationGeneration == generation) {
                    isAnimating = false
                    animJob = null
                }
            }
        }
    }

    /** Button-driven precision change: adjusts precision and animates zoom to frame it. */
    fun animatePrecision(newPrecision: Int) {
        val p = newPrecision.coerceIn(1, GlobeMath.MAX_PRECISION)
        if (p == precision || baseRadiusPx <= 0f) return
        val targetZoom = GlobeMath.zoomForPrecision(p, baseRadiusPx, screenMinPx)
        animateTo(centerLat.toDouble(), centerLon.toDouble(), targetZoom, p, durationMs = 450)
    }

    /** Cinematic intro: spin and zoom from a far view into the target location. */
    private fun playIntro(targetLat: Double, targetLon: Double, targetPrecision: Int) {
        if (baseRadiusPx <= 0f) {
            // viewport not ready yet; retry once attached to layout via caller
            return
        }
        val targetZoom = GlobeMath.zoomForPrecision(targetPrecision, baseRadiusPx, screenMinPx)
        animateTo(targetLat, targetLon, targetZoom, targetPrecision, durationMs = 1400)
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
        const val MIN_LAT = -89f
        const val MAX_LAT = 89f
        const val MIN_FLING_PX_PER_SECOND = 90f
        const val MAX_FLING_PX_PER_SECOND = 3_200f
        const val MAX_FRAME_DELTA_SECONDS = 1f / 30f
        const val MAX_FLING_RADIUS_FRACTION_PER_FRAME = 0.12f
        const val FLING_FRICTION_PER_SECOND = 4.2f
    }

    private fun syncSelection() {
        if (baseRadiusPx <= 0f) return
        val gh = Geohash.encode(centerLat.toDouble(), centerLon.toDouble(), precision)
        if (gh != selectedGeohash) selectedGeohash = gh
    }
}
