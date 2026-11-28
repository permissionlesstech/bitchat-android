package com.bitchat.android.ui.globe

import kotlin.math.abs
import kotlin.math.exp

/** One marker as drawn this frame: the laid-out [marker] at its animated size and opacity. */
internal class MarkerFrame(
    val marker: GlobeMarker,
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float
)

/**
 * Fades activity markers in and out between frames so they never pop.
 *
 * Markers are tracked by their busiest channel and always stay where the globe puts them: a new
 * marker fades in while growing slightly to full size, a vanished one fades out while shrinking
 * slightly. Size changes (a cluster gaining members) ease rather than jump, and markers fade as
 * they turn over the globe's limb.
 *
 * [durationScale] is the system animator scale; 0 (Remove animations) makes every change instant.
 */
internal class GlobeMarkerAnimator(private val durationScale: Float = 1f) {

    private class Track(val bornNanos: Long, var radius: Float)

    private class Ghost(val frame: MarkerFrame, val diedNanos: Long)

    private val tracks = LinkedHashMap<String, Track>()
    private val lastFrames = HashMap<String, MarkerFrame>()
    private val ghosts = ArrayList<Ghost>()
    private var lastFrameNanos = 0L

    /** True while any marker is still appearing, leaving or resizing. */
    var isAnimating: Boolean = false
        private set

    fun frame(markers: List<GlobeMarker>, nowNanos: Long): List<MarkerFrame> {
        val dtNanos = if (lastFrameNanos == 0L) 0L else (nowNanos - lastFrameNanos).coerceIn(0L, MAX_STEP_NANOS)
        lastFrameNanos = nowNanos
        val instant = durationScale <= 0f
        val enterNanos = (ENTER_NANOS * durationScale).toLong()
        val exitNanos = (EXIT_NANOS * durationScale).toLong()

        val current = LinkedHashMap<String, GlobeMarker>(markers.size)
        markers.forEach { current[it.primary.geohash] = it }

        // Leaving: fade out exactly where the marker was last drawn.
        val gone = tracks.keys.filter { it !in current }
        for (key in gone) {
            tracks.remove(key)
            val last = lastFrames.remove(key) ?: continue
            if (!instant) ghosts += Ghost(last, nowNanos)
        }

        var animating = false
        val out = ArrayList<MarkerFrame>(current.size + ghosts.size)

        for ((key, marker) in current) {
            val track = tracks.getOrPut(key) {
                Track(bornNanos = if (instant) Long.MIN_VALUE / 2 else nowNanos, radius = marker.radius)
            }
            val p = if (instant) 1f else progress(nowNanos - track.bornNanos, enterNanos)
            if (p < 1f) {
                animating = true
                track.radius = marker.radius
            } else {
                // Ease size changes instead of jumping.
                track.radius = if (instant) marker.radius else approach(track.radius, marker.radius, dtNanos)
                if (abs(track.radius - marker.radius) > SETTLED_PX) animating = true
            }
            val t = easeOutCubic(p)
            val fade = limbFade(marker.depth)
            val frame = MarkerFrame(
                marker = marker,
                x = marker.x,
                y = marker.y,
                radius = track.radius * (ENTER_FROM_SCALE + (1f - ENTER_FROM_SCALE) * t) *
                    (LIMB_MIN_SCALE + (1f - LIMB_MIN_SCALE) * fade),
                alpha = t * fade
            )
            lastFrames[key] = frame
            out += frame
        }

        val ghostIterator = ghosts.iterator()
        while (ghostIterator.hasNext()) {
            val ghost = ghostIterator.next()
            val q = progress(nowNanos - ghost.diedNanos, exitNanos)
            if (q >= 1f) {
                ghostIterator.remove()
                continue
            }
            animating = true
            val t = easeOutCubic(q)
            val last = ghost.frame
            out += MarkerFrame(
                marker = last.marker,
                x = last.x,
                y = last.y,
                radius = last.radius * (1f - (1f - EXIT_TO_SCALE) * t),
                alpha = last.alpha * (1f - t)
            )
        }

        isAnimating = animating
        return out
    }

    private fun approach(current: Float, target: Float, dtNanos: Long): Float {
        val k = 1f - exp(-(dtNanos.toFloat() / RESIZE_TIME_CONSTANT_NANOS))
        return current + (target - current) * k
    }

    private fun progress(elapsed: Long, duration: Long): Float =
        if (duration <= 0L) 1f else (elapsed.toFloat() / duration).coerceIn(0f, 1f)

    private companion object {
        const val ENTER_NANOS = 200_000_000L
        const val EXIT_NANOS = 150_000_000L
        const val ENTER_FROM_SCALE = 0.6f
        const val EXIT_TO_SCALE = 0.7f
        const val RESIZE_TIME_CONSTANT_NANOS = 70_000_000f
        const val MAX_STEP_NANOS = 100_000_000L
        const val SETTLED_PX = 0.3f
        const val LIMB_MIN_SCALE = 0.55f
    }
}

/** 0 at the limb, 1 once a marker has turned [LIMB_FADE_DEPTH] toward the viewer. */
internal fun limbFade(depth: Float): Float {
    val t = (depth / LIMB_FADE_DEPTH).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutCubic(t: Float): Float {
    val u = 1f - t
    return 1f - u * u * u
}

private const val LIMB_FADE_DEPTH = 0.22f
