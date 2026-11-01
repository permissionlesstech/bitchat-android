package com.bitchat.android.ui.globe

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.bitchat.android.nostr.GeohashChannelActivity
import com.bitchat.android.nostr.estimateDistinctPeople
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/** Marker colors, derived from the app theme by the caller. */
data class GlobeActivityColors(
    /** Heartbeats only: people have the app open here but nobody wrote this hour. */
    val quiet: Color,
    /** Some chat this hour. */
    val active: Color,
    /** Busy chat; markers blend toward this as messages climb. */
    val hot: Color,
    /** Count on a solid chat marker. */
    val text: Color,
    /** Count on a translucent heartbeat-only marker. */
    val quietText: Color,
    val selection: Color
)

/** One marker on screen: a single channel, or overlapping channels merged into a cluster. */
class GlobeMarker internal constructor(
    val x: Float,
    val y: Float,
    val radius: Float,
    /** Busiest first. */
    val channels: List<GeohashChannelActivity>,
    /** Estimated distinct people; see [estimateDistinctPeople]. */
    val people: Int,
    val messages: Int,
    /** Newest activity across [channels], epoch seconds. */
    val lastActiveAt: Long,
    /** How far the marker faces the viewer: 1 at the view center, 0 at the limb. */
    val depth: Float = 1f
) {
    val isCluster: Boolean get() = channels.size > 1
    /** Nobody wrote this hour: only heartbeats. Drawn small and quiet. */
    val isAmbient: Boolean get() = messages == 0
    val primary: GeohashChannelActivity get() = channels.first()

    /** People-weighted center of the merged channels, for zooming into a cluster. */
    val centroid: Pair<Double, Double>
        get() {
            var vx = 0.0
            var vy = 0.0
            var vz = 0.0
            for (channel in channels) {
                val lat = Math.toRadians(channel.latitude)
                val lon = Math.toRadians(channel.longitude)
                val w = channel.people.toDouble()
                vx += w * kotlin.math.cos(lat) * kotlin.math.cos(lon)
                vy += w * kotlin.math.cos(lat) * kotlin.math.sin(lon)
                vz += w * kotlin.math.sin(lat)
            }
            val hyp = sqrt(vx * vx + vy * vy)
            return Math.toDegrees(kotlin.math.atan2(vz, hyp)) to Math.toDegrees(kotlin.math.atan2(vy, vx))
        }
}

/** Markers from the most recent frame; read by the tap handler. */
class GlobeMarkerLayout {
    @Volatile
    var markers: List<GlobeMarker> = emptyList()
        internal set

    /** The marker under [position], preferring the one whose edge is closest. */
    fun hitTest(position: Offset, slopPx: Float): GlobeMarker? {
        var best: GlobeMarker? = null
        var bestDistance = Float.MAX_VALUE
        for (marker in markers) {
            val dx = marker.x - position.x
            val dy = marker.y - position.y
            val edge = sqrt(dx * dx + dy * dy) - marker.radius
            if (edge <= slopPx && edge < bestDistance) {
                best = marker
                bestDistance = edge
            }
        }
        return best
    }
}

/**
 * Radius in dp. Area tracks headcount (square root), and chat markers outrank heartbeat-only
 * ones so conversations stand out from people who merely have the app open.
 */
internal fun markerRadiusDp(people: Int, chatting: Boolean, cluster: Boolean): Float {
    val root = sqrt(people.coerceAtLeast(1).toFloat())
    return if (chatting) {
        val base = CHAT_MIN_RADIUS_DP + CHAT_RADIUS_PER_SQRT_PERSON_DP * root
        (if (cluster) base.coerceAtLeast(CLUSTER_MIN_RADIUS_DP) else base).coerceAtMost(CHAT_MAX_RADIUS_DP)
    } else {
        (AMBIENT_MIN_RADIUS_DP + AMBIENT_RADIUS_PER_SQRT_PERSON_DP * root).coerceAtMost(AMBIENT_MAX_RADIUS_DP)
    }
}

/** 0 when nobody wrote, rising toward 1 around a message a minute. */
internal fun markerHeat(messages: Int): Float =
    if (messages <= 0) 0f else (ln(1f + messages) / ln(1f + HOT_MESSAGES_PER_HOUR)).coerceIn(0f, 1f)

/** Marker fill: quiet for heartbeats, then green through yellow to hot as chat picks up. */
fun markerColor(messages: Int, colors: GlobeActivityColors): Color =
    if (messages <= 0) colors.quiet else lerpHue(colors.active, colors.hot, markerHeat(messages))

/** Interpolates around the hue wheel, so green to orange passes yellow instead of mud. */
internal fun lerpHue(from: Color, to: Color, t: Float): Color {
    val a = FloatArray(3).also { android.graphics.Color.colorToHSV(from.toArgb(), it) }
    val b = FloatArray(3).also { android.graphics.Color.colorToHSV(to.toArgb(), it) }
    var dh = b[0] - a[0]
    if (dh > 180f) dh -= 360f
    if (dh < -180f) dh += 360f
    val hsv = floatArrayOf(
        (a[0] + dh * t + 360f) % 360f,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t
    )
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** "7", "842", "1.2k", "12k". */
internal fun compactCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 10_000 -> String.format(Locale.US, "%.1fk", count / 1_000f).replace(".0k", "k")
    else -> "${count / 1_000}k"
}

/**
 * Projects channels onto the visible hemisphere and greedily merges markers that would overlap,
 * busiest first, so a crowded region reads as one labelled cluster until the user zooms in.
 */
internal fun layoutGlobeMarkers(
    channels: List<GeohashChannelActivity>,
    projector: GlobeMath.PreparedProjector,
    cx: Float,
    cy: Float,
    r: Float,
    width: Float,
    height: Float,
    density: Float
): List<GlobeMarker> {
    class Group(val x: Float, val y: Float, val depth: Float, first: GeohashChannelActivity, var radius: Float) {
        val members = mutableListOf(first)
        var messages = first.messages
    }

    val terms = FloatArray(4)
    val projection = FloatArray(3)
    val margin = MARKER_MAX_RADIUS_DP * density
    val groups = ArrayList<Group>()
    for (channel in channels) {
        val lat = Math.toRadians(channel.latitude)
        val lon = Math.toRadians(channel.longitude)
        terms[0] = kotlin.math.sin(lat).toFloat()
        terms[1] = kotlin.math.cos(lat).toFloat()
        terms[2] = kotlin.math.sin(lon).toFloat()
        terms[3] = kotlin.math.cos(lon).toFloat()
        projector.project(terms, 0, projection, 0)
        if (projection[2] < MIN_VISIBLE_COS) continue
        val x = cx + projection[0] * r
        val y = cy + projection[1] * r
        if (x < -margin || x > width + margin || y < -margin || y > height + margin) continue

        val radius = markerRadiusDp(channel.people, channel.messages > 0, cluster = false) * density
        val host = groups.firstOrNull { group ->
            val dx = group.x - x
            val dy = group.y - y
            val reach = (group.radius + radius) * MERGE_OVERLAP + MERGE_GAP_DP * density
            dx * dx + dy * dy < reach * reach
        }
        if (host == null) {
            groups.add(Group(x, y, projection[2], channel, radius))
        } else {
            host.members.add(channel)
            host.messages += channel.messages
            host.radius = markerRadiusDp(
                estimateDistinctPeople(host.members),
                chatting = host.messages > 0,
                cluster = true
            ) * density
        }
    }
    return groups.map { group ->
        GlobeMarker(
            x = group.x,
            y = group.y,
            radius = group.radius,
            channels = group.members,
            people = estimateDistinctPeople(group.members),
            messages = group.messages,
            lastActiveAt = group.members.maxOf { it.lastActiveAt },
            depth = group.depth
        )
    }
}

internal fun DrawScope.drawGlobeMarkers(
    frames: List<MarkerFrame>,
    colors: GlobeActivityColors,
    selectedGeohash: String,
    pulsePhase: Float,
    nowSeconds: Long,
    textPaint: Paint,
    typeface: Typeface?,
    density: Float
) {
    val canvas = drawContext.canvas.nativeCanvas
    textPaint.typeface = typeface
    textPaint.textAlign = Paint.Align.CENTER

    // Heartbeat-only markers first so conversations always sit on top.
    for (frame in frames.sortedBy { it.marker.messages * 1_000 + it.marker.people }) {
        val marker = frame.marker
        val radius = frame.radius
        if (radius < 0.5f || frame.alpha <= 0.01f) continue
        val a = frame.alpha.coerceIn(0f, 1f)
        val center = Offset(frame.x, frame.y)
        val color = markerColor(marker.messages, colors)
        val selected = marker.channels.any { it.geohash == selectedGeohash }

        if (marker.isAmbient) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = 0.22f * a),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius * AMBIENT_GLOW_SCALE
                ),
                radius = radius * AMBIENT_GLOW_SCALE,
                center = center
            )
            drawCircle(color.copy(alpha = 0.30f * a), radius = radius, center = center)
            drawCircle(color.copy(alpha = 0.85f * a), radius = radius, center = center, style = Stroke(1.2f * density))
        } else {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = 0.45f * a),
                    0.45f to color.copy(alpha = 0.16f * a),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius * GLOW_SCALE
                ),
                radius = radius * GLOW_SCALE,
                center = center
            )
            if (nowSeconds - marker.lastActiveAt < MARKER_RECENT_SECONDS) {
                drawCircle(
                    color = color.copy(alpha = 0.6f * (1f - pulsePhase) * a),
                    radius = radius * (1f + 1.1f * pulsePhase),
                    center = center,
                    style = Stroke(width = 1.6f * density)
                )
            }
            if (marker.isCluster) {
                drawCircle(color.copy(alpha = 0.28f * a), radius = radius + 3f * density, center = center)
            }
            drawCircle(color.copy(alpha = a), radius = radius, center = center)
            drawCircle(
                color = Color.White.copy(alpha = 0.5f * a),
                radius = radius - 0.5f * density,
                center = center,
                style = Stroke(width = 1f * density)
            )
        }

        if (selected) {
            drawCircle(
                color = colors.selection.copy(alpha = a),
                radius = radius + 4.5f * density,
                center = center,
                style = Stroke(width = 2f * density)
            )
        }

        // Whether a count fits is decided at full size; the text then scales with the animation.
        if (marker.radius >= LABEL_MIN_RADIUS_DP * density) {
            val label = compactCount(marker.people)
            val textColor = if (marker.isAmbient) colors.quietText else colors.text
            textPaint.color = textColor.copy(alpha = textColor.alpha * a).toArgb()
            textPaint.textSize = (marker.radius * 0.95f).coerceIn(9f * density, 12.5f * density) *
                (if (label.length >= 3) 0.84f else 1f) * (radius / marker.radius)
            if (textPaint.textSize < 2f * density) continue
            val baseline = frame.y - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, frame.x, baseline, textPaint)
        }
    }
}

/** Chat markers active this recently pulse. */
internal const val MARKER_RECENT_SECONDS = 180L

private const val CHAT_MIN_RADIUS_DP = 5f
private const val CHAT_RADIUS_PER_SQRT_PERSON_DP = 2.4f
private const val CHAT_MAX_RADIUS_DP = 17f
private const val CLUSTER_MIN_RADIUS_DP = 9f
private const val AMBIENT_MIN_RADIUS_DP = 2.5f
private const val AMBIENT_RADIUS_PER_SQRT_PERSON_DP = 1.3f
private const val AMBIENT_MAX_RADIUS_DP = 9.5f
private const val LABEL_MIN_RADIUS_DP = 8.5f
private const val HOT_MESSAGES_PER_HOUR = 60f
/** Markers fade out toward the limb (see [limbFade]), so they can stay in layout until it. */
private const val MIN_VISIBLE_COS = 0f
private const val MERGE_OVERLAP = 0.85f
private const val MERGE_GAP_DP = 1.5f
private const val GLOW_SCALE = 2.3f
private const val AMBIENT_GLOW_SCALE = 1.8f
private const val MARKER_MAX_RADIUS_DP = CHAT_MAX_RADIUS_DP
