package com.bitchat.android.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class PeerRadarItem(
    val peerID: String,
    val displayName: String,
    val distanceMeters: Float,
    val trueBearingDeg: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryMapSheet(
    isPresented: Boolean,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    if (!isPresented) return

    val colorScheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val myLocation by viewModel.myTelemetryLocation.collectAsStateWithLifecycle()
    val peerLocations by viewModel.peerTelemetryLocations.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val headingDegrees by rememberCompassHeadingDegrees()

    val peersForMap by remember(myLocation, peerLocations, peerNicknames) {
        derivedStateOf {
            val mine = myLocation ?: return@derivedStateOf emptyList()
            peerLocations.mapNotNull { (peerID, peerLoc) ->
                val results = FloatArray(3)
                Location.distanceBetween(
                    mine.latitude,
                    mine.longitude,
                    peerLoc.latitude,
                    peerLoc.longitude,
                    results
                )
                val display = peerNicknames[peerID] ?: peerID.take(8)
                PeerRadarItem(
                    peerID = peerID,
                    displayName = display,
                    distanceMeters = results[0],
                    trueBearingDeg = normalizeDegrees(results[1])
                )
            }.sortedBy { it.distanceMeters }
        }
    }

    BitchatBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.telemetry_heading_format,
                        normalizeDegrees(headingDegrees).toInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurface.copy(alpha = 0.8f)
                )

                if (myLocation == null) {
                    Text(
                        text = stringResource(R.string.telemetry_waiting_for_my_location),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                } else {
                    TelemetryRadarCanvas(
                        peers = peersForMap,
                        headingDegrees = headingDegrees,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    )
                }

                if (peersForMap.isEmpty()) {
                    Text(
                        text = stringResource(R.string.telemetry_no_peer_locations),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else {
                    peersForMap.forEach { item ->
                        val relativeBearing = normalizeDegrees(item.trueBearingDeg - headingDegrees)
                        Text(
                            text = stringResource(
                                R.string.telemetry_peer_row_format,
                                item.displayName,
                                item.distanceMeters.toInt(),
                                cardinalFromDegrees(relativeBearing)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface
                        )
                    }
                }
            }

            BitchatSheetTopBar(
                title = {
                    BitchatSheetTitle(text = stringResource(R.string.telemetry_map_title))
                },
                backgroundAlpha = 0f,
                actions = {},
                onClose = onDismiss
            )
        }
    }
}

@Composable
private fun TelemetryRadarCanvas(
    peers: List<PeerRadarItem>,
    headingDegrees: Float,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val maxDistance = max(20f, (peers.maxOfOrNull { it.distanceMeters } ?: 20f) * 1.2f)
    val ringColor = colorScheme.outline.copy(alpha = 0.35f)
    val centerColor = Color(0xFF007AFF)
    val peerColor = Color(0xFFFF9500)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = min(size.width, size.height) / 2f - 8.dp.toPx()

            // Rings + crosshair
            drawCircle(color = ringColor, radius = maxRadius, center = center, style = Stroke(width = 2.dp.toPx()))
            drawCircle(color = ringColor, radius = maxRadius * 0.66f, center = center, style = Stroke(width = 1.dp.toPx()))
            drawCircle(color = ringColor, radius = maxRadius * 0.33f, center = center, style = Stroke(width = 1.dp.toPx()))
            drawLine(ringColor, Offset(center.x, center.y - maxRadius), Offset(center.x, center.y + maxRadius), strokeWidth = 1.dp.toPx())
            drawLine(ringColor, Offset(center.x - maxRadius, center.y), Offset(center.x + maxRadius, center.y), strokeWidth = 1.dp.toPx())

            // You at center
            drawCircle(color = centerColor, radius = 7.dp.toPx(), center = center)

            // Phone "forward" indicator at top
            drawCircle(
                color = centerColor.copy(alpha = 0.35f),
                radius = 6.dp.toPx(),
                center = Offset(center.x, center.y - maxRadius + 10.dp.toPx())
            )

            peers.forEachIndexed { index, peer ->
                val relative = normalizeDegrees(peer.trueBearingDeg - headingDegrees)
                val theta = Math.toRadians(relative.toDouble())
                val normalizedRadius = (peer.distanceMeters / maxDistance).coerceIn(0f, 1f)
                val radius = normalizedRadius * maxRadius
                val x = center.x + (sin(theta) * radius).toFloat()
                val y = center.y - (cos(theta) * radius).toFloat()

                drawCircle(color = peerColor, radius = 6.dp.toPx(), center = Offset(x, y))
                if (index == 0) {
                    drawCircle(color = peerColor.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = Offset(x, y))
                }
            }
        }
    }
}

@Composable
private fun rememberCompassHeadingDegrees(): State<Float> {
    val context = LocalContext.current
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
                private val orientation = FloatArray(3)

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    heading.value = normalizeDegrees(azimuthDeg)
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
