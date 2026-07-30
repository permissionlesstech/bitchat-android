package com.bitchat.android.ui.globe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bitchat.android.geohash.Geohash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * Hoisted state for the interactive 3D globe: view center, zoom, geohash precision
 * and the current selection. All mutation funnels through this class so rendering,
 * gestures and buttons stay in sync.
 */
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

    internal var baseRadiusPx by mutableFloatStateOf(0f)
    internal var screenMinPx by mutableFloatStateOf(0f)

    private var scope: CoroutineScope? = null
    private var animJob: Job? = null

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
        animJob = s.launch {
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

    /** Inertial spin after a fling. Velocities are in px/ms. */
    fun fling(velocityX: Float, velocityY: Float) {
        val s = scope ?: return
        if (abs(velocityX) < 0.05f && abs(velocityY) < 0.05f) return
        animJob?.cancel()
        animJob = s.launch {
            var vx = velocityX
            var vy = velocityY
            var lastTime = System.nanoTime()
            while (abs(vx) > 0.02f || abs(vy) > 0.02f) {
                val now = System.nanoTime()
                val dtMs = ((now - lastTime) / 1_000_000f).coerceAtMost(50f)
                lastTime = now
                rotateBy(vx * dtMs, vy * dtMs)
                val decay = 0.94f.pow(dtMs / 16f)
                vx *= decay
                vy *= decay
                kotlinx.coroutines.delay(16)
            }
        }
    }

    fun cancelAnimations() {
        animJob?.cancel()
    }

    private companion object {
        // Close to the projection limit so polar geohash cells remain selectable;
        // clamping tighter would make syncSelection() encode the wrong cell.
        const val MIN_LAT = -89f
        const val MAX_LAT = 89f
    }

    private fun syncSelection() {
        if (baseRadiusPx <= 0f) return
        val gh = Geohash.encode(centerLat.toDouble(), centerLon.toDouble(), precision)
        if (gh != selectedGeohash) selectedGeohash = gh
    }
}
