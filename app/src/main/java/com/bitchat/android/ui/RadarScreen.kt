package com.bitchat.android.ui

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.*
import androidx.compose.ui.tooling.preview.Preview
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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

    val showVerificationSheet by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerificationSheet by viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()
    val showMeshPeerListSheet by viewModel.showMeshPeerList.collectAsStateWithLifecycle()
    val showAppInfo by viewModel.showAppInfo.collectAsStateWithLifecycle()
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()

    var showLocationNotesSheet by remember { mutableStateOf(false) }
    var showTelemetryMapSheet by remember { mutableStateOf(false) }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }

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

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val themePref by com.bitchat.android.ui.theme.ThemePreferenceManager.themeFlow.collectAsStateWithLifecycle()
    val isDark = when (themePref) {
        com.bitchat.android.ui.theme.ThemePreference.Dark -> true
        com.bitchat.android.ui.theme.ThemePreference.Light -> false
        com.bitchat.android.ui.theme.ThemePreference.System -> systemDark
    }

    val backgroundColor = if (isDark) Color(0xFF050805) else Color.White
    val greenTerminal = if (isDark) Color(0xFF00FF66) else Color(0xFF008833)
    val greenTerminalDim = if (isDark) Color(0xFF4A7A66) else Color(0xFF2E7D32)
    val greenCardBg = if (isDark) Color(0xFF09140D) else Color(0xFFF0FDF4)
    val greenCardBorder = if (isDark) Color(0xFF14301F) else Color(0xFFC8E6C9)

    // Terminal background layout (White in light mode, pitch black in dark mode)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Dark green terminal grid overlay lines
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 40.dp.toPx()
            val gridColor = if (isDark) Color(0xFF00FF66).copy(alpha = 0.03f) else Color(0xFF00AA44).copy(alpha = 0.06f)
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

        // Flex Column layout ensuring zero overlap on all screen sizes/ratios
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Common Top Header Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(2f),
                color = backgroundColor
            ) {
                TopAppBar(
                    title = {
                        ChatHeaderContent(
                            selectedPrivatePeer = null,
                            currentChannel = null,
                            nickname = myNickname,
                            viewModel = viewModel,
                            onBackClick = {},
                            onSidebarClick = { viewModel.showMeshPeerList() },
                            onTripleClick = { viewModel.panicClearAllData() },
                            onShowAppInfo = { viewModel.showAppInfo() },
                            onLocationChannelsClick = { showLocationChannelsSheet = true },
                            onLocationNotesClick = { showLocationNotesSheet = true },
                            onTelemetryMapClick = { showTelemetryMapSheet = true },
                            onPeerVerificationClick = { showPeersSheet = true },
                            onSwitchToChat = { onSwitchToChat?.invoke() },
                            isRadarMode = true
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier.height(42.dp)
                )
            }

            // 2. Centered Heading Info (heading: 12° N)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "heading: ${headingDegrees.toInt()}° ${cardinalFromDegrees(headingDegrees)}",
                    color = greenTerminal,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // 3. Radar view or waiting screen - claims 100% remaining middle height via weight(1f)
            val mineLoc = myLocation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (mineLoc == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = greenTerminal,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "waiting for gps location fix...",
                            color = greenTerminalDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    RadarCanvasView(
                        peers = activePeers,
                        headingDegrees = headingDegrees,
                        maxDistanceRange = selectedZoomLevel.toDouble(),
                        isDark = isDark,
                        onPeerClick = { peerID ->
                            viewModel.showPrivateChatSheet(peerID)
                            onSwitchToChat?.invoke()
                        }
                    )
                }
            }

            // 4. Bottom Zoom Controls Panel (Exact OG Style)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "RANGE SCALE",
                    color = greenTerminalDim,
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
                        .background(greenCardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, greenCardBorder, RoundedCornerShape(16.dp))
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
                                    color = if (isSelected) CyanSelfDot else greenTerminalDim,
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
                                        color = if (isSelected) CyanSelfDot else greenTerminalDim,
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
        
        // Sheet dialogs accessible from top header
        if (showLocationNotesSheet) {
            LocationNotesSheetPresenter(
                viewModel = viewModel,
                onDismiss = { showLocationNotesSheet = false }
            )
        }
        if (showTelemetryMapSheet) {
            TelemetryMapSheet(
                isPresented = showTelemetryMapSheet,
                viewModel = viewModel,
                onDismiss = { showTelemetryMapSheet = false }
            )
        }
        if (showLocationChannelsSheet) {
            LocationChannelsSheet(
                isPresented = showLocationChannelsSheet,
                onDismiss = { showLocationChannelsSheet = false },
                viewModel = viewModel
            )
        }
        if (showMeshPeerListSheet) {
            MeshPeerListSheet(
                isPresented = showMeshPeerListSheet,
                viewModel = viewModel,
                onDismiss = {
                    viewModel.hideMeshPeerList()
                    if (viewModel.privateChatSheetPeer.value != null || viewModel.selectedPrivateChatPeer.value != null) {
                        onSwitchToChat?.invoke()
                    }
                },
                onShowVerification = {
                    viewModel.hideMeshPeerList()
                    viewModel.showVerificationSheet(fromSidebar = true)
                }
            )
        }
        if (showVerificationSheet) {
            VerificationSheet(
                isPresented = showVerificationSheet,
                onDismiss = { viewModel.hideVerificationSheet() },
                viewModel = viewModel
            )
        }
        if (showSecurityVerificationSheet) {
            SecurityVerificationSheet(
                isPresented = showSecurityVerificationSheet,
                onDismiss = { viewModel.hideSecurityVerificationSheet() },
                viewModel = viewModel
            )
        }
        if (showAppInfo) {
            AboutSheet(
                isPresented = showAppInfo,
                onDismiss = { viewModel.hideAppInfo() }
            )
        }
        if (privateChatSheetPeer != null) {
            PrivateChatSheet(
                isPresented = true,
                peerID = privateChatSheetPeer!!,
                viewModel = viewModel,
                onDismiss = {
                    viewModel.hidePrivateChatSheet()
                    viewModel.endPrivateChat()
                }
            )
        }
    }
}

@Composable
private fun RadarCanvasView(
    peers: List<RadarPeer>,
    headingDegrees: Float,
    maxDistanceRange: Double,
    isDark: Boolean = true,
    onPeerClick: ((String) -> Unit)? = null
) {
    val ringColor = if (isDark) Color(0xFF14301F) else Color(0xFFC8E6C9)
    val ringTextColor = if (isDark) android.graphics.Color.parseColor("#4A7A66") else android.graphics.Color.parseColor("#2E7D32")
    val cardinalTextColor = if (isDark) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#008833")

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val margin30DpPx = with(density) { 30.dp.toPx() }
        val maxRadiusPx = (min(widthPx, heightPx) / 2f - margin30DpPx).coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(peers, headingDegrees, maxDistanceRange, maxRadiusPx) {
                    detectTapGestures { tapOffset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val theta = Math.toRadians(-headingDegrees.toDouble())
                        val cosT = cos(theta)
                        val sinT = sin(theta)
                        val touchRadiusPx = 30.dp.toPx()

                        val hitPeer = peers.firstOrNull { peer ->
                            val (targetX, targetY) = RadarMathEngine.toCartesian(
                                distance = peer.distance,
                                relativeAngleDegrees = peer.bearing,
                                maxDistance = maxDistanceRange,
                                maxRadius = maxRadiusPx.toDouble()
                            )
                            val finalX = center.x + (targetX * cosT - targetY * sinT).toFloat()
                            val finalY = center.y + (targetX * sinT + targetY * cosT).toFloat()
                            val dx = tapOffset.x - finalX
                            val dy = tapOffset.y - finalY
                            (dx * dx + dy * dy) <= (touchRadiusPx * touchRadiusPx)
                        }
                        if (hitPeer != null) {
                            onPeerClick?.invoke(hitPeer.peerID)
                        }
                    }
                }
        ) {
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

            // 2. Draw Compass Cardinals (N, E, S, W) Rotating on the Outer Circle (Exact OG Style)
            val cardinalPoints = listOf(
                "N" to 0.0,
                "E" to 90.0,
                "S" to 180.0,
                "W" to 270.0
            )
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    textSize = 14.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    color = cardinalTextColor
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

                    val peerDotColor = colorForPeer(peer.peerID)
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
                            color = peerDotColor.toArgb()
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
}

private val PEER_COLOR_PALETTE = listOf(
    Color(0xFF00FF66), // Matrix Neon Green
    Color(0xFF00E5FF), // Bright Cyan
    Color(0xFFFF3366), // Coral Pink / Neon Red
    Color(0xFFFFD600), // Vibrant Amber / Yellow
    Color(0xFFA066FF), // Neon Purple / Violet
    Color(0xFFFF9100), // Electric Orange
    Color(0xFF00E676), // Spring Green
    Color(0xFF1DE9B6), // Teal Mint
    Color(0xFFFF4081), // Deep Pink
    Color(0xFF7C4DFF)  // Deep Indigo
)

private fun colorForPeer(peerID: String): Color {
    val hash = kotlin.math.abs(peerID.hashCode())
    return PEER_COLOR_PALETTE[hash % PEER_COLOR_PALETTE.size]
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

@Preview(showBackground = true, backgroundColor = 0xFF050805)
@Composable
fun RadarScreenPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050805))
            .padding(top = 16.dp)
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "bitchat radar",
                color = Color(0xFF00FF66),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "peers detected: 2",
                color = Color(0xFF4A7A66),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Information Pill
            Row(
                modifier = Modifier
                    .background(Color(0xFF09140D), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(1.dp, Color(0xFF14301F), RoundedCornerShape(16.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "heading: 8° n  self: shlok",
                    color = Color(0xFF4A7A66),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Verified Self",
                    tint = Color(0xFF00FF66),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 110.dp),
            contentAlignment = Alignment.Center
        ) {
            val dummyPeers = listOf(
                RadarPeer("peer1", "alice", 45.0, 30.0, 1.0f),
                RadarPeer("peer2", "bob", 80.0, 240.0, 0.8f)
            )
            RadarCanvasView(
                peers = dummyPeers,
                headingDegrees = 8f,
                maxDistanceRange = 100.0
            )
        }

        // Bottom Zoom Controls Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "RANGE SCALE",
                color = Color(0xFF4A7A66),
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
                    .background(Color(0xFF09140D), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF14301F), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                val row1 = listOf("5m", "10m", "25m", "50m")
                val row2 = listOf("100m", "250m", "500km", "1km")

                // Row 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    row1.forEach { label ->
                        val isSelected = label == "100m"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF4A7A66),
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
                    row2.forEach { label ->
                        val isSelected = label == "100m"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF4A7A66),
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
