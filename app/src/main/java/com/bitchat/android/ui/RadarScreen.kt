package com.bitchat.android.ui

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.services.AppStateStore
import kotlin.math.*
import kotlinx.coroutines.delay

private data class RadarPeer(
    val peerID: String,
    val displayName: String,
    val distance: Double,
    val bearing: Double,
    val opacity: Float
)

@Composable
fun RadarScreen(viewModel: ChatViewModel) {
    val myLocation by viewModel.myTelemetryLocation.collectAsStateWithLifecycle()
    val peerLocations by viewModel.peerTelemetryLocations.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val headingDegrees by rememberCompassHeadingDegrees(myLocation)
    val myNickname by viewModel.nickname.collectAsStateWithLifecycle()

    // Available zoom levels (in meters)
    val zoomLevels = listOf(5f, 10f, 25f, 50f, 100f, 250f, 500f, 1000f)
    var selectedZoomLevel by remember { mutableStateOf(100f) }

    // Ticker to refresh data expiry every second
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }

    // Compute active peers list with distance, bearing, and opacity calculations
    val activePeers = remember(myLocation, peerLocations, peerNicknames, selectedZoomLevel, tick) {
        val mine = myLocation ?: return@remember emptyList<RadarPeer>()
        val now = System.currentTimeMillis()

        peerLocations.mapNotNull { (peerID, peerLoc) ->
            val ageMs = now - peerLoc.timestampMs
            if (ageMs >= 10 * 60 * 1000) return@mapNotNull null // Drop completely after 10 minutes

            val ageSec = ageMs / 1000.0
            val opacity = if (ageSec <= 180.0) {
                1.0f
            } else {
                // Linear fade to 40% (0.4) between 3 mins and 10 mins
                val fraction = (ageSec - 180.0) / (600.0 - 180.0)
                (1.0f - fraction * 0.6f).toFloat().coerceIn(0.4f, 1.0f)
            }

            val distance = RadarMathEngine.calculateDistance(
                mine.latitude, mine.longitude,
                peerLoc.latitude, peerLoc.longitude
            )
            val bearing = RadarMathEngine.calculateBearing(
                mine.latitude, mine.longitude,
                peerLoc.latitude, peerLoc.longitude
            )

            RadarPeer(
                peerID = peerID,
                displayName = peerNicknames[peerID] ?: peerID.take(8),
                distance = distance,
                bearing = bearing,
                opacity = opacity
            )
        }
    }

    // Pure black background layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Futuristic grid background lines (very subtle)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 40.dp.toPx()
            val gridColor = Color.White.copy(alpha = 0.02f)
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
                x += gridSpacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
                y += gridSpacing
            }
        }

        // Top Header Info Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "BITCHAT RADAR",
                color = Color(0xFF007AFF),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "PEERS DETECTED: ${activePeers.size}",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HEADING: ${headingDegrees.toInt()}° ${cardinalFromDegrees(headingDegrees)}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "SELF: ${myNickname.ifBlank { viewModel.myPeerID.take(8) }}",
                    color = Color(0xFF007AFF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Radar view or waiting screen
        val mineLoc = myLocation
        if (mineLoc == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF007AFF),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "WAITING FOR GPS LOCATION FIX...",
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                RadarCanvasView(
                    peers = activePeers,
                    headingDegrees = headingDegrees,
                    maxDistanceRange = selectedZoomLevel.toDouble()
                )
            }
        }

        // Scanning Status indicator
        if (mineLoc != null && activePeers.isEmpty()) {
            Text(
                text = "SCANNING BLE MESH NETWORK...",
                color = Color(0xFF007AFF).copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
            )
        }

        // Bottom Zoom Controls Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "RANGE SCALE",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(4.dp)
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                zoomLevels.forEach { zoom ->
                    val isSelected = zoom == selectedZoomLevel
                    val zoomLabel = if (zoom >= 1000f) "${(zoom/1000).toInt()}km" else "${zoom.toInt()}m"
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) Color(0xFF007AFF) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedZoomLevel = zoom }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = zoomLabel,
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarCanvasView(
    peers: List<RadarPeer>,
    headingDegrees: Float,
    maxDistanceRange: Double
) {
    // Rings color system
    val ringColor = Color.White.copy(alpha = 0.15f)
    val ringTextColor = android.graphics.Color.GRAY
    val centerColor = Color(0xFF007AFF)
    val peerColor = Color(0xFFFF3B30)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val margin30DpPx = with(density) { 30.dp.toPx() }
        val maxRadiusPx = (min(widthPx, heightPx) / 2f - margin30DpPx).coerceAtLeast(1f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = maxRadiusPx

            // 1. Draw Concentric Reference Rings
            val rings = listOf(0.25f, 0.50f, 1.00f)
            rings.forEach { ratio ->
                drawCircle(
                    color = ringColor,
                    radius = maxRadius * ratio,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // Draw crosshair axes
            drawLine(ringColor.copy(alpha = 0.08f), Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 1.dp.toPx())
            drawLine(ringColor.copy(alpha = 0.08f), Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 1.dp.toPx())

            // 2. Draw Compass Cardinals (N, E, S, W) Rotating on the Outer Circle
            val cardinalPoints = listOf(
                "N" to 0.0,
                "E" to 90.0,
                "S" to 180.0,
                "W" to 270.0
            )
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    textSize = 12.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                }
                cardinalPoints.forEach { (label, trueBearing) ->
                    val relativeAngle = RadarMathEngine.calculateRelativeAngle(trueBearing, headingDegrees.toDouble())
                    val angleRad = Math.toRadians(relativeAngle)
                    // Position cardinal just outside the max radius
                    val cx = center.x + (sin(angleRad) * (maxRadius + 16.dp.toPx())).toFloat()
                    val cy = center.y - (cos(angleRad) * (maxRadius + 16.dp.toPx())).toFloat()

                    paint.color = if (label == "N") android.graphics.Color.RED else android.graphics.Color.WHITE
                    // Adjust Y to center the text vertically
                    val textY = cy - ((paint.descent() + paint.ascent()) / 2)
                    nativeCanvas.drawText(label, cx, textY, paint)
                }

                // Draw ring distance indicators
                val ringPaint = android.graphics.Paint().apply {
                    color = ringTextColor
                    textSize = 10.sp.toPx()
                    textAlign = android.graphics.Paint.Align.LEFT
                    alpha = 150
                }
                rings.forEach { ratio ->
                    val distanceVal = (maxDistanceRange * ratio).toInt()
                    val label = "${distanceVal}m"
                    val rx = center.x + 6.dp.toPx()
                    val ry = center.y - (maxRadius * ratio) - 4.dp.toPx()
                    nativeCanvas.drawText(label, rx, ry, ringPaint)
                }
            }

            // 3. Draw Local User in Center with Outer Glow Pulse
            drawCircle(
                color = centerColor.copy(alpha = 0.15f),
                radius = 16.dp.toPx(),
                center = center
            )
            drawCircle(
                color = centerColor,
                radius = 7.dp.toPx(),
                center = center
            )

            // Draw Forward heading pointer (triangle pointing forward/up relative to phone direction)
            val pointerY = center.y - maxRadius
            drawCircle(
                color = centerColor.copy(alpha = 0.8f),
                radius = 4.dp.toPx(),
                center = Offset(center.x, pointerY)
            )
        }

        // 4. Draw Peers with Animated North Coordinate and Heading Rotation (Sensor Fusion)
        peers.forEach { peer ->
            // Retrieve targeting positions relative to North using exact maxRadiusPx from BoxWithConstraints
            val (targetX, targetY) = RadarMathEngine.toCartesian(
                distance = peer.distance,
                relativeAngleDegrees = peer.bearing,
                maxDistance = maxDistanceRange,
                maxRadius = maxRadiusPx.toDouble()
            )

            AnimatePeerCoordinates(
                peerID = peer.peerID,
                xNorthTarget = targetX.toFloat(),
                yNorthTarget = targetY.toFloat()
            ) { animX, animY ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // Rotate coordinate by negative device heading to align with screen viewpoint
                    val theta = Math.toRadians(-headingDegrees.toDouble())
                    val cosT = cos(theta)
                    val sinT = sin(theta)
                    val finalX = center.x + (animX * cosT - animY * sinT).toFloat()
                    val finalY = center.y + (animX * sinT + animY * cosT).toFloat()

                    // Draw peer dot + halo glow
                    drawCircle(
                        color = peerColor.copy(alpha = peer.opacity * 0.2f),
                        radius = 12.dp.toPx(),
                        center = Offset(finalX, finalY)
                    )
                    drawCircle(
                        color = peerColor.copy(alpha = peer.opacity),
                        radius = 6.dp.toPx(),
                        center = Offset(finalX, finalY)
                    )

                    // Draw label tag underneath
                    drawIntoCanvas { canvas ->
                        val nativeCanvas = canvas.nativeCanvas
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 11.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                            alpha = (peer.opacity * 255).toInt()
                        }
                        val shadowPaint = android.graphics.Paint(textPaint).apply {
                            color = android.graphics.Color.BLACK
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = 3.dp.toPx()
                            alpha = (peer.opacity * 255).toInt()
                        }
                        val labelText = "${peer.displayName} [${peer.distance.toInt()}m]"
                        val textY = finalY + 18.dp.toPx()
                        nativeCanvas.drawText(labelText, finalX, textY, shadowPaint)
                        nativeCanvas.drawText(labelText, finalX, textY, textPaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatePeerCoordinates(
    peerID: String,
    xNorthTarget: Float,
    yNorthTarget: Float,
    content: @Composable (animatedXNorth: Float, animatedYNorth: Float) -> Unit
) {
    val animatedX by animateFloatAsState(
        targetValue = xNorthTarget,
        animationSpec = tween(300, easing = LinearEasing),
        label = "x_${peerID}"
    )
    val animatedY by animateFloatAsState(
        targetValue = yNorthTarget,
        animationSpec = tween(300, easing = LinearEasing),
        label = "y_${peerID}"
    )
    content(animatedX, animatedY)
}

@Composable
private fun rememberCompassHeadingDegrees(myLocation: AppStateStore.TelemetryLocation?): State<Float> {
    val context = LocalContext.current
    val heading = remember { mutableStateOf(0f) }
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    DisposableEffect(sensorManager, myLocation) {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                private val rotationMatrix = FloatArray(9)
                private val remappedMatrix = FloatArray(9)
                private val orientation = FloatArray(3)

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
                    val displayRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        try { context.display?.rotation ?: android.view.Surface.ROTATION_0 } catch (_: Exception) { android.view.Surface.ROTATION_0 }
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
                    }

                    var axisX = SensorManager.AXIS_X
                    var axisY = SensorManager.AXIS_Y

                    when (displayRotation) {
                        android.view.Surface.ROTATION_90 -> {
                            axisX = SensorManager.AXIS_Y
                            axisY = SensorManager.AXIS_MINUS_X
                        }
                        android.view.Surface.ROTATION_180 -> {
                            axisX = SensorManager.AXIS_MINUS_X
                            axisY = SensorManager.AXIS_MINUS_Y
                        }
                        android.view.Surface.ROTATION_270 -> {
                            axisX = SensorManager.AXIS_MINUS_Y
                            axisY = SensorManager.AXIS_X
                        }
                    }

                    SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                    SensorManager.getOrientation(remappedMatrix, orientation)
                    val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    val normalizedAzimuth = normalizeDegrees(azimuthDeg)

                    val declination = if (myLocation != null) {
                        try {
                            val geoField = GeomagneticField(
                                myLocation.latitude.toFloat(),
                                myLocation.longitude.toFloat(),
                                0f,
                                myLocation.timestampMs
                            )
                            geoField.declination
                        } catch (_: Exception) {
                            0f
                        }
                    } else {
                        0f
                    }

                    heading.value = normalizeDegrees(normalizedAzimuth + declination)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    return heading
}

private fun normalizeDegrees(value: Float): Float {
    return ((value % 360f) + 360f) % 360f
}

private fun cardinalFromDegrees(bearing: Float): String {
    return when (((normalizeDegrees(bearing) + 22.5f) / 45f).toInt() % 8) {
        0 -> "N"
        1 -> "NE"
        2 -> "E"
        3 -> "SE"
        4 -> "S"
        5 -> "SW"
        6 -> "W"
        else -> "NW"
    }
}
