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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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

// Terminal Matrix Green Palette
private val GreenTerminal = Color(0xFF00FF66)
private val GreenTerminalDim = Color(0xFF4A7A66)
private val GreenCardBg = Color(0xFF09140D)
private val GreenCardBorder = Color(0xFF14301F)
private val CyanSelfDot = Color(0xFF00E5FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    viewModel: ChatViewModel,
    onSwitchToChat: (() -> Unit)? = null
) {
    val myLocation by viewModel.myTelemetryLocation.collectAsStateWithLifecycle()
    val peerLocations by viewModel.peerTelemetryLocations.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val headingDegrees by rememberCompassHeadingDegrees(myLocation)
    val myNickname by viewModel.nickname.collectAsStateWithLifecycle()

    val verifiedPeers by viewModel.verifiedLocationPeers.collectAsStateWithLifecycle(initialValue = emptySet())
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val incomingVerifyRequestPeer by viewModel.incomingLocationVerifyRequest.collectAsStateWithLifecycle()
    var showPeersSheet by remember { mutableStateOf(false) }

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

    // Compute active peers list with distance, bearing, and opacity calculations (verified peers only)
    val activePeers = remember(myLocation, peerLocations, peerNicknames, verifiedPeers, selectedZoomLevel, tick) {
        val mine = myLocation ?: return@remember emptyList<RadarPeer>()
        val now = System.currentTimeMillis()

        peerLocations.mapNotNull { (peerID, peerLoc) ->
            if (!verifiedPeers.contains(peerID)) return@mapNotNull null // Only show verified peers!
            val ageMs = now - peerLoc.timestampMs
            if (ageMs >= 10 * 60 * 1000) return@mapNotNull null // Drop completely after 10 minutes

            val ageSec = ageMs / 1000.0
            val opacity = if (ageSec <= 180.0) {
                1.0f
            } else {
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

    // Terminal Pitch Black background layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050805))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Dark green terminal grid overlay lines
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 40.dp.toPx()
            val gridColor = Color(0xFF00FF66).copy(alpha = 0.03f)
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

        // Top CHAT toggle button
        if (onSwitchToChat != null) {
            Button(
                onClick = onSwitchToChat,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GreenCardBg,
                    contentColor = GreenTerminal
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GreenCardBorder),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "Switch to Chat",
                        tint = GreenTerminal,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "chat",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = GreenTerminal
                    )
                }
            }
        }

        // Top PEERS verification toggle button
        Button(
            onClick = { showPeersSheet = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = GreenCardBg,
                contentColor = GreenTerminal
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GreenCardBorder),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = "Manage Peer Verification",
                    tint = GreenTerminal,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "peers",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = GreenTerminal
                )
            }
        }

        // Top Header Info Panel (Exact OG Matrix Style)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 24.dp, end = 24.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "bitchat radar",
                color = GreenTerminal,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "peers detected: ${activePeers.size}",
                color = GreenTerminalDim,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Information Pill
            Row(
                modifier = Modifier
                    .background(GreenCardBg, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(1.dp, GreenCardBorder, RoundedCornerShape(16.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "heading: ${headingDegrees.toInt()}° ${cardinalFromDegrees(headingDegrees).lowercase()}",
                    color = GreenTerminalDim,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "self: ${myNickname.ifBlank { viewModel.myPeerID.take(8) }}",
                    color = GreenTerminalDim,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Verified Self",
                    tint = GreenTerminal,
                    modifier = Modifier.size(14.dp)
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
                    color = GreenTerminal,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "waiting for gps location fix...",
                    color = GreenTerminalDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 110.dp),
                contentAlignment = Alignment.Center
            ) {
                RadarCanvasView(
                    peers = activePeers,
                    headingDegrees = headingDegrees,
                    maxDistanceRange = selectedZoomLevel.toDouble()
                )
            }
        }

        // Bottom Zoom Controls Panel (Exact OG Style)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "RANGE SCALE",
                color = GreenTerminalDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Outer Card Container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GreenCardBg, RoundedCornerShape(16.dp))
                    .border(1.dp, GreenCardBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                val row1 = listOf(5f to "5m", 10f to "10m", 25f to "25m", 50f to "50m")
                val row2 = listOf(100f to "100m", 250f to "250m", 500f to "500km", 1000f to "1km")

                // Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    row1.forEach { (zoomValue, label) ->
                        val isSelected = selectedZoomLevel == zoomValue
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { selectedZoomLevel = zoomValue },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) CyanSelfDot else GreenTerminalDim,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    row2.forEach { (zoomValue, label) ->
                        val isSelected = selectedZoomLevel == zoomValue
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { selectedZoomLevel = zoomValue },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    color = if (isSelected) CyanSelfDot else GreenTerminalDim,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Selected",
                                        tint = CyanSelfDot,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Incoming Verification Request Alert Dialog
        val requestPeerID = incomingVerifyRequestPeer
        if (requestPeerID != null) {
            val requesterName = peerNicknames[requestPeerID] ?: requestPeerID.take(8)
            AlertDialog(
                onDismissRequest = { viewModel.rejectLocationVerificationRequest(requestPeerID) },
                title = {
                    Text(
                        text = "LOCATION SHARE REQUEST",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = GreenTerminal
                    )
                },
                text = {
                    Text(
                        text = "$requesterName wants to share live radar location with you. Do you accept?",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.acceptLocationVerificationRequest(requestPeerID) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C851))
                    ) {
                        Text("ACCEPT", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { viewModel.rejectLocationVerificationRequest(requestPeerID) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                    ) {
                        Text("REJECT", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                containerColor = Color(0xFF14241A),
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Mesh Peer Verification Bottom Sheet
        if (showPeersSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPeersSheet = false },
                containerColor = Color(0xFF0A140E),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "NEARBY MESH PEERS",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = GreenTerminal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verify devices to share locations. Rejected requests have a 5-minute cooldown.",
                        fontSize = 12.sp,
                        color = GreenTerminalDim
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (connectedPeers.isEmpty()) {
                        Text(
                            text = "No mesh peers currently connected.",
                            color = Color.Gray,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(connectedPeers) { peerID ->
                                val peerName = peerNicknames[peerID] ?: peerID.take(8)
                                val isVerified = verifiedPeers.contains(peerID)
                                val remainingCooldownMs = viewModel.verifiedLocationStore.getRemainingCooldownMs(peerID)
                                val canRequest = viewModel.verifiedLocationStore.canSendRequest(peerID)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(GreenCardBg, RoundedCornerShape(8.dp))
                                        .border(1.dp, GreenCardBorder, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = peerName,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "ID: ${peerID.take(8)}",
                                            fontSize = 11.sp,
                                            color = GreenTerminalDim,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    when {
                                        isVerified -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Verified",
                                                    tint = GreenTerminal,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "VERIFIED",
                                                    color = GreenTerminal,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        !canRequest -> {
                                            val remainingSec = (remainingCooldownMs / 1000L).coerceAtLeast(1)
                                            val min = remainingSec / 60
                                            val sec = remainingSec % 60
                                            Text(
                                                text = "Cooldown (${min}m ${sec}s)",
                                                color = Color.Red.copy(alpha = 0.8f),
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        else -> {
                                            Button(
                                                onClick = {
                                                    viewModel.sendLocationVerificationRequest(peerID)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = GreenTerminal),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Text("REQUEST SHARE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
    val ringColor = Color(0xFF14301F)
    val ringTextColor = android.graphics.Color.parseColor("#4A7A66")

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
            drawLine(ringColor, Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 1.dp.toPx())
            drawLine(ringColor, Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 1.dp.toPx())

            // 2. Draw Compass Cardinals (n, e, s, w) Rotating on the Outer Circle (Exact OG Style)
            val cardinalPoints = listOf(
                "n" to 0.0,
                "e" to 90.0,
                "s" to 180.0,
                "w" to 270.0
            )
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    textSize = 14.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    color = android.graphics.Color.parseColor("#00FF66")
                }
                cardinalPoints.forEach { (label, trueBearing) ->
                    val relativeAngle = RadarMathEngine.calculateRelativeAngle(trueBearing, headingDegrees.toDouble())
                    val angleRad = Math.toRadians(relativeAngle)
                    val cx = center.x + (sin(angleRad) * (maxRadius + 18.dp.toPx())).toFloat()
                    val cy = center.y - (cos(angleRad) * (maxRadius + 18.dp.toPx())).toFloat()

                    val textY = cy - ((paint.descent() + paint.ascent()) / 2)
                    nativeCanvas.drawText(label, cx, textY, paint)
                }

                // Draw ring distance indicators
                val ringPaint = android.graphics.Paint().apply {
                    color = ringTextColor
                    textSize = 11.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
                }
                rings.forEach { ratio ->
                    val distanceVal = (maxDistanceRange * ratio).toInt()
                    val label = "${distanceVal}m"
                    val rx = center.x
                    val ry = center.y - (maxRadius * ratio) - 6.dp.toPx()
                    nativeCanvas.drawText(label, rx, ry, ringPaint)
                }
            }

            // 3. Draw Local User in Center (Cyan Self Dot)
            drawCircle(
                color = CyanSelfDot.copy(alpha = 0.25f),
                radius = 16.dp.toPx(),
                center = center
            )
            drawCircle(
                color = CyanSelfDot,
                radius = 7.dp.toPx(),
                center = center
            )

            // Draw Forward heading pointer
            val pointerY = center.y - maxRadius
            drawCircle(
                color = CyanSelfDot.copy(alpha = 0.8f),
                radius = 4.dp.toPx(),
                center = Offset(center.x, pointerY)
            )
        }

        // 4. Draw Peers with Animated North Coordinate and Heading Rotation (Sensor Fusion)
        peers.forEach { peer ->
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

                    val theta = Math.toRadians(-headingDegrees.toDouble())
                    val cosT = cos(theta)
                    val sinT = sin(theta)
                    val finalX = center.x + (animX * cosT - animY * sinT).toFloat()
                    val finalY = center.y + (animX * sinT + animY * cosT).toFloat()

                    val peerDotColor = Color(0xFF00FF66)
                    drawCircle(
                        color = peerDotColor.copy(alpha = peer.opacity * 0.25f),
                        radius = 12.dp.toPx(),
                        center = Offset(finalX, finalY)
                    )
                    drawCircle(
                        color = peerDotColor.copy(alpha = peer.opacity),
                        radius = 6.dp.toPx(),
                        center = Offset(finalX, finalY)
                    )

                    drawIntoCanvas { canvas ->
                        val nativeCanvas = canvas.nativeCanvas
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 11.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
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
    val currentMyLocation by rememberUpdatedState(myLocation)
    val heading = remember { mutableStateOf(0f) }
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    DisposableEffect(sensorManager) {
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

                    val loc = currentMyLocation
                    val declination = if (loc != null) {
                        try {
                            val geoField = GeomagneticField(
                                loc.latitude.toFloat(),
                                loc.longitude.toFloat(),
                                0f,
                                loc.timestampMs
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
