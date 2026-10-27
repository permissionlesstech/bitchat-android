package com.bitchat.android.ui.globe

import kotlin.math.abs
import kotlin.math.exp

/** One marker as drawn this frame: the laid-out [marker] at its animated position, size and opacity. */
internal class MarkerFrame(
    val marker: GlobeMarker,
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float
)

/**
 * Animates activity markers between frames so they never pop.
 *
 * Markers are tracked by their busiest channel. A new marker pops in with a slight overshoot; a
 * vanished one shrinks out quickly. When zooming merges a marker into a cluster it flies into
 * it, and when a cluster splits the new markers fly out of it. Size changes ease rather than
 * jump, and markers fade as they turn over the globe's limb.
 *
 * [durationScale] is the system animator scale; 0 (Remove animations) makes every change instant.
 */
internal class GlobeMarkerAnimator(private val durationScale: Float = 1f) {

    private class Track(
        var marker: GlobeMarker,
        val bornNanos: Long,
        /** Cluster this marker split out of; it starts at that cluster's position. */
        val originKey: String?,
        val originX: Float,
        val originY: Float,
        var radius: Float,
        var x: Float,
        var y: Float
    )

    private class Ghost(
        val marker: GlobeMarker,
        val diedNanos: Long,
        val x: Float,
        val y: Float,
        val radius: Float,
        val alpha: Float,
        /** Marker this one merged into, if any; the ghost flies into it. */
        val intoKey: String?
    )

    private val tracks = LinkedHashMap<String, Track>()
    private val ghosts = ArrayList<Ghost>()
    /** Channel geohash -> key of the marker that showed it last frame. */
    private var lastOwners: Map<String, String> = emptyMap()
    private var lastFrameNanos = 0L

    /** True while any marker is still entering, leaving or resizing. */
    var isAnimating: Boolean = false
        private set

    fun frame(markers: List<GlobeMarker>, nowNanos: Long): List<MarkerFrame> {
        val dtNanos = if (lastFrameNanos == 0L) 0L else (nowNanos - lastFrameNanos).coerceIn(0L, MAX_STEP_NANOS)
        lastFrameNanos = nowNanos
        val instant = durationScale <= 0f
        val enterNanos = (ENTER_NANOS * durationScale).toLong()
        val exitNanos = (EXIT_NANOS * durationScale).toLong()

        val current = LinkedHashMap<String, GlobeMarker>(markers.size)
        val owners = HashMap<String, String>()
        for (marker in markers) {
            val key = keyOf(marker)
            current[key] = marker
            marker.channels.forEach { owners[it.geohash] = key }
        }

        // Leaving: keep a ghost where the marker was last drawn.
        val gone = tracks.keys.filter { it !in current }
        for (key in gone) {
            val track = tracks.remove(key) ?: continue
            if (instant) continue
            ghosts += Ghost(
                marker = track.marker,
                diedNanos = nowNanos,
                x = track.x,
                y = track.y,
                radius = track.radius,
                alpha = limbFade(track.marker.depth),
                intoKey = owners[key]?.takeIf { it != key }
            )
        }

        // Entering: split out of the cluster that showed this channel, or pop in place.
        for ((key, marker) in current) {
            if (key in tracks) continue
            val parentKey = lastOwners[key]?.takeIf { it != key && it in current }
            val parent = parentKey?.let(tracks::get)
            val stagger = if (parent == null) staggerNanos(key) else 0L
            tracks[key] = Track(
                marker = marker,
                bornNanos = if (instant) Long.MIN_VALUE / 2 else nowNanos + stagger,
                originKey = parentKey,
                originX = parent?.x ?: marker.x,
                originY = parent?.y ?: marker.y,
                radius = if (instant) marker.radius else 0f,
                x = marker.x,
                y = marker.y
            )
        }
        lastOwners = owners

        var animating = false
        val out = ArrayList<MarkerFrame>(tracks.size + ghosts.size)

        for ((key, track) in tracks) {
            val marker = current.getValue(key)
            track.marker = marker
            val p = if (instant) 1f else progress(nowNanos - track.bornNanos, enterNanos)
            val scale: Float
            if (p < 1f) {
                animating = true
                scale = easeOutBack(p)
                track.radius = marker.radius
                val origin = track.originKey?.let(tracks::get)
                val fromX = origin?.x ?: track.originX
                val fromY = origin?.y ?: track.originY
                val t = easeOutCubic(p)
                track.x = fromX + (marker.x - fromX) * t
                track.y = fromY + (marker.y - fromY) * t
            } else {
                scale = 1f
                // Ease size changes (a cluster gaining members) instead of jumping.
                val target = marker.radius
                track.radius = if (instant) target else approach(track.radius, target, dtNanos)
                if (abs(track.radius - target) > SETTLED_PX) animating = true
                track.x = marker.x
                track.y = marker.y
            }
            if (p <= 0f) continue
            val fade = limbFade(marker.depth)
            out += MarkerFrame(
                marker = marker,
                x = track.x,
                y = track.y,
                radius = track.radius * scale * (LIMB_MIN_SCALE + (1f - LIMB_MIN_SCALE) * fade),
                alpha = (p * ENTER_FADE_SPEED).coerceAtMost(1f) * fade
            )
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
            val into = ghost.intoKey?.let(tracks::get)
            val t = easeInCubic(q)
            val x = if (into != null) ghost.x + (into.x - ghost.x) * t else ghost.x
            val y = if (into != null) ghost.y + (into.y - ghost.y) * t else ghost.y
            out += MarkerFrame(
                marker = ghost.marker,
                x = x,
                y = y,
                radius = ghost.radius * (1f - t),
                alpha = ghost.alpha * (1f - q)
            )
        }

        isAnimating = animating
        return out
    }

    private fun keyOf(marker: GlobeMarker) = marker.primary.geohash

    /** Spreads a batch of new markers over a few frames so it cascades in rather than flashing. */
    private fun staggerNanos(key: String): Long =
        (((key.hashCode() and Int.MAX_VALUE) % STAGGER_SLOTS) * STAGGER_STEP_NANOS * durationScale).toLong()

    private fun approach(current: Float, target: Float, dtNanos: Long): Float {
        val k = 1f - exp(-(dtNanos.toFloat() / RESIZE_TIME_CONSTANT_NANOS))
        return current + (target - current) * k
    }

    private fun progress(elapsed: Long, duration: Long): Float =
        if (duration <= 0L) 1f else (elapsed.toFloat() / duration).coerceIn(0f, 1f)

    private companion object {
        const val ENTER_NANOS = 280_000_000L
        const val EXIT_NANOS = 160_000_000L
        const val STAGGER_SLOTS = 6
        const val STAGGER_STEP_NANOS = 22_000_000L
        const val RESIZE_TIME_CONSTANT_NANOS = 70_000_000f
        const val MAX_STEP_NANOS = 100_000_000L
        const val SETTLED_PX = 0.3f
        /** Opacity reaches full in the first third of the pop, before the overshoot lands. */
        const val ENTER_FADE_SPEED = 3f
        const val LIMB_MIN_SCALE = 0.55f
    }
}

/** 0 at the limb, 1 once a marker has turned [LIMB_FADE_DEPTH] toward the viewer. */
internal fun limbFade(depth: Float): Float {
    val t = (depth / LIMB_FADE_DEPTH).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Overshoots slightly past 1 before settling: the "snap" of the pop-in. */
internal fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

private fun easeOutCubic(t: Float): Float {
    val u = 1f - t
    return 1f - u * u * u
}

private fun easeInCubic(t: Float): Float = t * t * t

private const val LIMB_FADE_DEPTH = 0.22f
