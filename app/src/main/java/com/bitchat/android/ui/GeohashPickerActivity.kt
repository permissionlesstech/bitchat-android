package com.bitchat.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Pinch
import androidx.compose.material.icons.outlined._360
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.bitchat.android.R
import com.bitchat.android.geohash.Geohash
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.GeohashActivityMonitor
import com.bitchat.android.nostr.GeohashActivitySnapshot
import com.bitchat.android.nostr.GeohashChannelActivity
import com.bitchat.android.nostr.estimateDistinctPeople
import com.bitchat.android.ui.globe.GlobeActivityColors
import com.bitchat.android.ui.globe.GlobeColors
import com.bitchat.android.ui.globe.GlobeMath
import com.bitchat.android.ui.globe.GlobeMarker
import com.bitchat.android.ui.globe.GlobeState
import com.bitchat.android.ui.globe.GlobeView
import com.bitchat.android.ui.globe.LandData
import com.bitchat.android.ui.globe.compactCount
import com.bitchat.android.ui.globe.markerColor
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class GeohashPickerActivity : OrientationAwareActivity() {

    companion object {
        const val EXTRA_INITIAL_GEOHASH = "initial_geohash"
        const val EXTRA_RESULT_GEOHASH = "result_geohash"
    }

    private val activityMonitor by lazy { GeohashActivityMonitor(applicationContext) }

    override fun onStart() {
        super.onStart()
        activityMonitor.start()
    }

    override fun onStop() {
        activityMonitor.stop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialGeohash = intent.getStringExtra(EXTRA_INITIAL_GEOHASH)?.trim()?.lowercase()
        var initLat = 20.0
        var initLon = 10.0

        if (!initialGeohash.isNullOrEmpty()) {
            try {
                val (lat, lon) = Geohash.decodeToCenter(initialGeohash)
                initLat = lat
                initLon = lon
            } catch (_: Throwable) {}
        } else {
            // If no initial geohash, try to use the user's coarsest location
            val locationManager = LocationChannelManager.getInstance(applicationContext)
            val channels = locationManager.availableChannels.value
            if (!channels.isNullOrEmpty()) {
                val coarsestChannel = channels.minByOrNull { it.geohash.length }
                if (coarsestChannel != null) {
                    try {
                        val (lat, lon) = Geohash.decodeToCenter(coarsestChannel.geohash)
                        initLat = lat
                        initLon = lon
                    } catch (_: Throwable) {}
                }
            }
        }

        // Always open on the whole globe, turning to face the selected location (if any) rather
        // than diving into it, so the world's activity stays in view.
        val initialPrecision = 1
        val targetLat = initLat
        val targetLon = initLon

        setContent {
            BitchatTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                val globeState = remember {
                    GlobeState(
                        targetLat = targetLat,
                        targetLon = targetLon,
                        initialPrecision = initialPrecision,
                        startZoomedOut = true
                    ).apply {
                        introTarget = Triple(targetLat, targetLon, initialPrecision)
                    }
                }

                LaunchedEffect(globeState) { globeState.attach(scope) }

                val land by produceState<List<LandData.Ring>?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) { LandData.load(context) }
                }
                val borders by produceState<List<LandData.Ring>>(initialValue = emptyList()) {
                    value = withContext(Dispatchers.IO) { LandData.loadBorders(context) }
                }
                val cities by produceState<List<LandData.City>>(initialValue = emptyList()) {
                    value = withContext(Dispatchers.IO) { LandData.loadCities(context) }
                }

                val activity by activityMonitor.snapshot.collectAsState()
                val activityStatus by activityMonitor.status.collectAsState()

                // Pull in the relays nearest wherever the user parks the globe.
                LaunchedEffect(globeState) {
                    snapshotFlow {
                        if (globeState.isInMotion) null
                        else globeState.centerLat to globeState.centerLon
                    }.collect { center ->
                        if (center != null) {
                            activityMonitor.focusOn(center.first, center.second)
                        }
                    }
                }

                val colorScheme = MaterialTheme.colorScheme
                val palette = LocalBitchatPalette.current
                val dark = colorScheme.background.luminance() < 0.5f
                val globeColors = remember(colorScheme, dark) {
                    if (dark) {
                        GlobeColors(
                            accent = colorScheme.primary,
                            land = Color(0xFF16241B),
                            coastline = colorScheme.primary.copy(alpha = 0.45f),
                            border = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            oceanCenter = Color(0xFF0A1410),
                            oceanEdge = Color(0xFF020604),
                            atmosphere = colorScheme.primary,
                            graticule = colorScheme.onSurface.copy(alpha = 0.055f),
                            grid = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            label = colorScheme.onSurfaceVariant,
                            labelHalo = colorScheme.background,
                            star = colorScheme.onSurface
                        )
                    } else {
                        GlobeColors(
                            accent = colorScheme.primary,
                            land = Color(0xFFBCD2C0),
                            coastline = colorScheme.primary.copy(alpha = 0.5f),
                            border = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            oceanCenter = Color(0xFFEAF2EC),
                            oceanEdge = Color(0xFFD4E2D7),
                            atmosphere = colorScheme.primary,
                            graticule = colorScheme.onSurface.copy(alpha = 0.08f),
                            grid = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            label = colorScheme.onSurfaceVariant,
                            labelHalo = colorScheme.background,
                            star = colorScheme.onSurfaceVariant
                        )
                    }
                }
                val activityColors = remember(colorScheme, palette) {
                    GlobeActivityColors(
                        quiet = colorScheme.secondary,
                        active = colorScheme.primary,
                        hot = palette.accentOrange,
                        text = Color.White,
                        quietText = colorScheme.onSurface,
                        selection = colorScheme.onSurface
                    )
                }

                val labelTypeface = remember { ResourcesCompat.getFont(context, R.font.geist_mono_medium) }
                val labelTypefaceBold = remember { ResourcesCompat.getFont(context, R.font.geist_mono_semibold) }

                // Geohashes of a tapped cluster, shown as a pick list until one is chosen.
                var cluster by remember { mutableStateOf<List<String>?>(null) }
                var hasInteracted by remember { mutableStateOf(false) }
                LaunchedEffect(globeState) {
                    snapshotFlow { globeState.isInteracting }.filter { it }.first()
                    hasInteracted = true
                }

                fun focusChannel(geohash: String) {
                    val (lat, lon) = Geohash.decodeToCenter(geohash)
                    val zoom = GlobeMath.zoomForPrecision(geohash.length, globeState.baseRadiusPx, globeState.screenMinPx)
                    globeState.animateTo(lat, lon, zoom, geohash.length, durationMs = 650)
                }

                fun onMarkerTap(marker: GlobeMarker) {
                    hasInteracted = true
                    if (!marker.isCluster) {
                        cluster = null
                        focusChannel(marker.primary.geohash)
                        return
                    }
                    cluster = marker.channels.map { it.geohash }
                    val (lat, lon) = marker.centroid
                    val zoom = (globeState.zoom * CLUSTER_ZOOM_STEP).coerceAtMost(GlobeMath.MAX_ZOOM)
                    globeState.animateTo(lat, lon, zoom, null, durationMs = 600)
                }

                fun join(geohash: String) {
                    if (geohash.isEmpty()) return
                    setResult(Activity.RESULT_OK, Intent().apply { putExtra(EXTRA_RESULT_GEOHASH, geohash) })
                    finish()
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colorScheme.background)
                ) {
                    land?.let { rings ->
                        GlobeView(
                            state = globeState,
                            colors = globeColors,
                            land = rings,
                            borders = borders,
                            cities = cities,
                            labelTypeface = labelTypeface,
                            labelTypefaceBold = labelTypefaceBold,
                            modifier = Modifier.fillMaxSize(),
                            activity = activity.channels,
                            activityColors = activityColors,
                            onMarkerTap = ::onMarkerTap,
                            onBackgroundTap = { cluster = null }
                        )
                    }

                    // Scrims keep the floating chrome legible over bright land.
                    EdgeScrim(Alignment.TopCenter, colorScheme.background, 132.dp)
                    EdgeScrim(Alignment.BottomCenter, colorScheme.background, 220.dp)

                    PickerTopBar(
                        status = activityStatus,
                        snapshot = activity,
                        onClose = { finish() },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    ZoomRail(
                        precision = globeState.precision,
                        canZoomOut = globeState.canZoomOut,
                        onZoomIn = { globeState.animatePrecision(globeState.precision + 1) },
                        onZoomOut = { globeState.zoomOut() },
                        onWholeGlobe = {
                            cluster = null
                            globeState.animateTo(
                                globeState.centerLat,
                                globeState.centerLon,
                                GlobeMath.MIN_ZOOM,
                                1,
                                durationMs = 700
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            .widthIn(max = 520.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        GestureHints(
                            visible = !hasInteracted,
                            showTapHint = activity.channels.isNotEmpty()
                        )
                        PickerCard {
                            AnimatedContent(
                                targetState = cluster,
                                transitionSpec = {
                                    fadeIn(tween(BitchatMotion.EMPHASIZED_MS)) togetherWith
                                        fadeOut(tween(BitchatMotion.QUICK_MS))
                                },
                                label = "pickerCard"
                            ) { clusterGeohashes ->
                                if (clusterGeohashes != null) {
                                    ClusterList(
                                        geohashes = clusterGeohashes,
                                        snapshot = activity,
                                        colors = activityColors,
                                        onPick = { geohash ->
                                            cluster = null
                                            focusChannel(geohash)
                                        },
                                        onDismiss = { cluster = null }
                                    )
                                } else {
                                    SelectionSummary(
                                        geohash = globeState.selectedGeohash,
                                        level = levelForLength(globeState.precision),
                                        coverage = coverageString(globeState.precision),
                                        channel = activity.channel(globeState.selectedGeohash),
                                        busiestInside = busiestInside(activity, globeState.selectedGeohash),
                                        colors = activityColors,
                                        onJoin = { join(globeState.selectedGeohash) },
                                        onFocus = ::focusChannel
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun levelForLength(length: Int): GeohashChannelLevel {
        return when (length) {
            in 0..2 -> GeohashChannelLevel.REGION
            in 3..4 -> GeohashChannelLevel.PROVINCE
            5 -> GeohashChannelLevel.CITY
            6 -> GeohashChannelLevel.NEIGHBORHOOD
            7 -> GeohashChannelLevel.BLOCK
            else -> GeohashChannelLevel.BUILDING
        }
    }

    private fun coverageString(precision: Int): String {
        val maxMeters = when (precision) {
            2 -> 1_250_000.0
            3 -> 156_000.0
            4 -> 39_100.0
            5 -> 4_890.0
            6 -> 1_220.0
            7 -> 153.0
            8 -> 38.2
            9 -> 4.77
            10 -> 1.19
            else -> if (precision <= 1) 5_000_000.0 else 1.19 * Math.pow(0.25, (precision - 10).toDouble())
        }
        val km = maxMeters / 1000.0
        return when {
            km >= 100 -> "~${String.format(java.util.Locale.US, "%.0f", km)} km"
            km >= 1 -> "~${String.format(java.util.Locale.US, "%.1f", km)} km"
            else -> "~${String.format(java.util.Locale.US, "%.0f", maxMeters)} m"
        }
    }
}

private const val CLUSTER_ZOOM_STEP = 2.6f
private val ChromeShape = RoundedCornerShape(22.dp)
private val ControlSize = 44.dp

@Composable
private fun BoxScope.EdgeScrim(alignment: Alignment, color: Color, height: Dp) {
    val top = alignment == Alignment.TopCenter
    Box(
        Modifier
            .align(alignment)
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (top) listOf(color.copy(alpha = 0.72f), Color.Transparent)
                    else listOf(Color.Transparent, color.copy(alpha = 0.78f))
                )
            )
    )
}

/**
 * Floating surface shared by every piece of picker chrome. Opaque on purpose: a translucent
 * surface lets its own shadow show through as a muddy inner box.
 */
@Composable
private fun ChromeSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = ChromeShape,
    content: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = shape,
        color = colorScheme.surface,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.55f)),
        shadowElevation = 10.dp,
        content = content
    )
}

@Composable
private fun PickerTopBar(
    status: GeohashActivityMonitor.Status,
    snapshot: GeohashActivitySnapshot,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChromeSurface(shape = CircleShape) {
            IconButton(onClick = onClose, modifier = Modifier.size(ControlSize)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.weight(1f))
        LiveActivityChip(status = status, snapshot = snapshot)
    }
}

/** "● LIVE  💬 20  👤 202", the at-a-glance pulse of the whole map. */
@Composable
private fun LiveActivityChip(
    status: GeohashActivityMonitor.Status,
    snapshot: GeohashActivitySnapshot
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val live = status == GeohashActivityMonitor.Status.LIVE
    val dotColor = when (status) {
        GeohashActivityMonitor.Status.LIVE -> colorScheme.primary
        GeohashActivityMonitor.Status.CONNECTING -> palette.accentOrange
        GeohashActivityMonitor.Status.OFFLINE -> palette.textTertiary
    }
    val breathe by rememberInfiniteTransition(label = "liveDot").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "liveDotAlpha"
    )
    val description = stringResource(R.string.cd_globe_activity, snapshot.activeChats, snapshot.totalOnline)
    ChromeSurface(
        shape = CircleShape,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Row(
            modifier = Modifier
                .height(ControlSize)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(if (status == GeohashActivityMonitor.Status.OFFLINE) 1f else breathe)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = stringResource(
                    when (status) {
                        GeohashActivityMonitor.Status.LIVE -> R.string.globe_live
                        GeohashActivityMonitor.Status.CONNECTING -> R.string.globe_connecting
                        GeohashActivityMonitor.Status.OFFLINE -> R.string.globe_offline
                    }
                ).uppercase(),
                fontFamily = BitchatFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = if (live) colorScheme.primary else colorScheme.onSurfaceVariant
            )
            if (live || snapshot.channels.isNotEmpty()) {
                StatValue(Icons.Outlined.ChatBubbleOutline, compactCount(snapshot.activeChats))
                StatValue(Icons.Filled.Person, compactCount(snapshot.totalOnline))
            }
        }
    }
}

@Composable
private fun StatValue(
    icon: ImageVector,
    value: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = value,
            fontFamily = BitchatFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = textColor
        )
    }
}

@Composable
private fun ZoomRail(
    precision: Int,
    canZoomOut: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onWholeGlobe: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ChromeSurface(shape = CircleShape) {
            IconButton(onClick = onWholeGlobe, modifier = Modifier.size(ControlSize)) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = stringResource(R.string.cd_show_whole_globe),
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        ChromeSurface(shape = RoundedCornerShape(ControlSize / 2)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val canZoomIn = precision < GlobeMath.MAX_PRECISION
                IconButton(
                    onClick = onZoomIn,
                    enabled = canZoomIn,
                    modifier = Modifier.size(ControlSize)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.cd_increase_precision),
                        tint = railIconTint(canZoomIn)
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.width(20.dp),
                    color = colorScheme.outline.copy(alpha = 0.6f)
                )
                // Current geohash length: the "zoom level" the + and − buttons step through.
                Text(
                    text = precision.toString(),
                    fontFamily = BitchatFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                HorizontalDivider(
                    modifier = Modifier.width(20.dp),
                    color = colorScheme.outline.copy(alpha = 0.6f)
                )
                IconButton(
                    onClick = onZoomOut,
                    enabled = canZoomOut,
                    modifier = Modifier.size(ControlSize)
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = stringResource(R.string.cd_decrease_precision),
                        tint = railIconTint(canZoomOut)
                    )
                }
            }
        }
    }
}

/** An explicit tint overrides IconButton's disabled colour, so dim it here. */
@Composable
private fun railIconTint(enabled: Boolean): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else DISABLED_ICON_ALPHA)

/** First-run gesture coaching; fades for good once the globe is touched. */
@Composable
private fun GestureHints(visible: Boolean, showTapHint: Boolean) {
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(GESTURE_HINT_TIMEOUT_MS)
        timedOut = true
    }
    AnimatedVisibility(
        visible = visible && !timedOut,
        enter = fadeIn(tween(BitchatMotion.EMPHASIZED_MS)) +
            slideInVertically(tween(BitchatMotion.EMPHASIZED_MS)) { it / 2 },
        exit = fadeOut(tween(BitchatMotion.EMPHASIZED_MS)) +
            slideOutVertically(tween(BitchatMotion.EMPHASIZED_MS)) { it / 2 }
    ) {
        ChromeSurface(shape = CircleShape) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GestureHint(Icons.Outlined._360, stringResource(R.string.globe_hint_drag))
                GestureHint(Icons.Outlined.Pinch, stringResource(R.string.globe_hint_pinch))
                if (showTapHint) {
                    GestureHint(Icons.Outlined.TouchApp, stringResource(R.string.globe_hint_tap))
                }
            }
        }
    }
}

private const val GESTURE_HINT_TIMEOUT_MS = 12_000L
/** Material's disabled content alpha. */
private const val DISABLED_ICON_ALPHA = 0.38f

@Composable
private fun GestureHint(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
        Text(
            text = label,
            fontFamily = BitchatFontFamily,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun PickerCard(content: @Composable () -> Unit) {
    ChromeSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SelectionSummary(
    geohash: String,
    level: GeohashChannelLevel,
    coverage: String,
    channel: GeohashChannelActivity?,
    busiestInside: GeohashChannelActivity?,
    colors: GlobeActivityColors,
    onJoin: () -> Unit,
    onFocus: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = if (geohash.isNotEmpty()) "#$geohash" else "…",
                    fontFamily = BitchatFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${level.displayName.lowercase()} · $coverage",
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (channel != null) {
                    ChannelActivityLine(channel, colors, Modifier.padding(top = 4.dp))
                } else {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(6.dp).background(palette.textTertiary.copy(alpha = 0.6f), CircleShape))
                        Text(
                            text = stringResource(R.string.globe_no_activity),
                            fontFamily = BitchatFontFamily,
                            fontSize = 12.sp,
                            color = palette.textTertiary
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onJoin,
                enabled = geohash.isNotEmpty(),
                shape = CircleShape,
                contentPadding = PaddingValues(start = 20.dp, end = 16.dp),
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.join),
                    fontFamily = BitchatFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        if (busiestInside != null) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                color = colorScheme.outline.copy(alpha = 0.5f)
            )
            ChannelRow(
                channel = busiestInside,
                colors = colors,
                leadingIcon = Icons.Outlined.LocalFireDepartment,
                onClick = { onFocus(busiestInside.geohash) }
            )
        }
    }
}

/**
 * The liveliest conversation nested inside [geohash], offered as a one-tap jump when the
 * selection itself is quieter. Chats rank above heartbeats.
 */
private fun busiestInside(snapshot: GeohashActivitySnapshot, geohash: String): GeohashChannelActivity? {
    if (geohash.isEmpty()) return null
    val selectedMessages = snapshot.channel(geohash)?.messages ?: 0
    return snapshot.channels
        .filter { it.geohash.length > geohash.length && it.geohash.startsWith(geohash) && it.messages > selectedMessages }
        .maxWithOrNull(compareBy<GeohashChannelActivity> { it.messages }.thenBy { it.people })
}

/** Heat dot, people and messages/hour — the same encoding as the marker on the globe. */
@Composable
private fun ChannelActivityLine(
    channel: GeohashChannelActivity,
    colors: GlobeActivityColors,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.cd_channel_activity, channel.people, channel.messages)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(8.dp).background(markerColor(channel.messages, colors), CircleShape))
        StatValue(Icons.Filled.Person, compactCount(channel.people))
        if (channel.messages > 0) {
            StatValue(Icons.Outlined.ChatBubbleOutline, "${compactCount(channel.messages)}/h")
        }
    }
}

@Composable
private fun ClusterList(
    geohashes: List<String>,
    snapshot: GeohashActivitySnapshot,
    colors: GlobeActivityColors,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val channels = remember(geohashes, snapshot) {
        geohashes.mapNotNull(snapshot::channel).sortedByDescending { it.people }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatValue(
                Icons.Outlined.Layers,
                compactCount(channels.size),
                tint = colorScheme.primary
            )
            Spacer(Modifier.width(14.dp))
            StatValue(Icons.Filled.Person, compactCount(estimateDistinctPeople(channels)))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        channels.take(MAX_CLUSTER_ROWS).forEach { channel ->
            ChannelRow(channel, colors, onClick = { onPick(channel.geohash) })
        }
    }
}

private const val MAX_CLUSTER_ROWS = 4

@Composable
private fun ChannelRow(
    channel: GeohashChannelActivity,
    colors: GlobeActivityColors,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = markerColor(channel.messages, colors), modifier = Modifier.size(16.dp))
        } else {
            Box(Modifier.size(10.dp).background(markerColor(channel.messages, colors), CircleShape))
        }
        Text(
            text = "#${channel.geohash}",
            fontFamily = BitchatFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        StatValue(Icons.Filled.Person, compactCount(channel.people))
        if (channel.messages > 0) {
            StatValue(Icons.Outlined.ChatBubbleOutline, "${compactCount(channel.messages)}/h")
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}
