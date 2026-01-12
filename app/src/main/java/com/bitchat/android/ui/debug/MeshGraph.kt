package com.bitchat.android.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.services.meshgraph.MeshGraphService
import kotlin.math.*
import kotlin.random.Random
import androidx.compose.material3.MaterialTheme
import com.bitchat.android.ui.debug.DebugSettingsManager.MeshVisualEvent

// Physics constants
private const val REPULSION_FORCE = 100000f
private const val SPRING_LENGTH = 150f
private const val SPRING_STRENGTH = 0.02f
private const val CENTER_GRAVITY = 0.02f
private const val DAMPING = 0.85f
private const val MAX_VELOCITY = 30f
private const val PULSE_DECAY = 0.05f
private const val ROUTE_DECAY = 0.02f

private class GraphNodeState(
    val id: String,
    var label: String,
    var x: Float,
    var y: Float
) {
    var vx: Float = 0f
    var vy: Float = 0f
    var isDragged: Boolean = false
    var pulseLevel: Float = 0f // 0f to 1f, used for size/glow animation
}

private class Simulation {
    val nodes = mutableMapOf<String, GraphNodeState>()
    // Storing edges as pairs of IDs
    val edges = mutableListOf<MeshGraphService.GraphEdge>()
    // Active routes being animated: List of peerIDs in order -> intensity (1.0..0.0)
    val activeRoutes = mutableListOf<Pair<List<String>, Float>>()
    
    // Bounds for initial placement and centering
    var width: Float = 1000f
    var height: Float = 1000f

    fun updateTopology(
        newNodes: List<MeshGraphService.GraphNode>,
        newEdges: List<MeshGraphService.GraphEdge>
    ) {
        // Remove stale nodes
        val newIds = newNodes.map { it.peerID }.toSet()
        nodes.keys.toList().forEach { id ->
            if (id !in newIds) nodes.remove(id)
        }

        // Add/Update nodes
        newNodes.forEach { n ->
            val existing = nodes[n.peerID]
            val displayLabel = n.nickname ?: n.peerID.take(8)
            if (existing != null) {
                existing.label = displayLabel
            } else {
                // Spawn near center with random jitter
                val angle = Random.nextFloat() * 2 * PI
                val radius = 50f + Random.nextFloat() * 50f
                nodes[n.peerID] = GraphNodeState(
                    id = n.peerID,
                    label = displayLabel,
                    x = (width / 2f) + (cos(angle) * radius).toFloat(),
                    y = (height / 2f) + (sin(angle) * radius).toFloat()
                )
            }
        }

        // Update edges
        edges.clear()
        edges.addAll(newEdges)
    }

    fun triggerNodePulse(peerID: String) {
        nodes[peerID]?.pulseLevel = 1f
    }

    fun triggerRouteAnimation(route: List<String>) {
        if (route.size > 1) {
            activeRoutes.add(route to 1f)
        }
    }

    fun step() {
        val nodeList = nodes.values.toList()
        val cx = width / 2f
        val cy = height / 2f

        // 1. Repulsion (Node-Node)
        for (i in nodeList.indices) {
            val n1 = nodeList[i]
            for (j in i + 1 until nodeList.size) {
                val n2 = nodeList[j]
                val dx = n1.x - n2.x
                val dy = n1.y - n2.y
                val distSq = dx * dx + dy * dy
                if (distSq > 0.1f) {
                    val dist = sqrt(distSq)
                    val force = REPULSION_FORCE / distSq
                    val fx = (dx / dist) * force
                    val fy = (dy / dist) * force
                    
                    if (!n1.isDragged) {
                        n1.vx += fx
                        n1.vy += fy
                    }
                    if (!n2.isDragged) {
                        n2.vx -= fx
                        n2.vy -= fy
                    }
                }
            }
        }

        // 2. Attraction (Edges)
        edges.forEach { edge ->
            val n1 = nodes[edge.a]
            val n2 = nodes[edge.b]
            if (n1 != null && n2 != null) {
                val dx = n1.x - n2.x
                val dy = n1.y - n2.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > 0.1f) {
                    val force = (dist - SPRING_LENGTH) * SPRING_STRENGTH
                    val fx = (dx / dist) * force
                    val fy = (dy / dist) * force

                    if (!n1.isDragged) {
                        n1.vx -= fx
                        n1.vy -= fy
                    }
                    if (!n2.isDragged) {
                        n2.vx += fx
                        n2.vy += fy
                    }
                }
            }
        }

        // 3. Center Gravity & Integration & Animation Decay
        nodeList.forEach { n ->
            if (!n.isDragged) {
                // Pull to center
                val dx = n.x - cx
                val dy = n.y - cy
                n.vx -= dx * CENTER_GRAVITY
                n.vy -= dy * CENTER_GRAVITY

                // Apply velocity
                val vMag = sqrt(n.vx * n.vx + n.vy * n.vy)
                if (vMag > MAX_VELOCITY) {
                    n.vx = (n.vx / vMag) * MAX_VELOCITY
                    n.vy = (n.vy / vMag) * MAX_VELOCITY
                }

                n.x += n.vx
                n.y += n.vy

                // Damping
                n.vx *= DAMPING
                n.vy *= DAMPING
            } else {
                n.vx = 0f
                n.vy = 0f
            }

            // Decay pulse
            if (n.pulseLevel > 0f) {
                n.pulseLevel = (n.pulseLevel - PULSE_DECAY).coerceAtLeast(0f)
            }
        }

        // Decay active routes
        val iter = activeRoutes.iterator()
        while (iter.hasNext()) {
            val (route, intensity) = iter.next()
            val newIntensity = intensity - ROUTE_DECAY
            if (newIntensity <= 0f) {
                iter.remove()
            } else {
                // Ugly mutation but efficient for simulation loop
                // We need to replace the pair since pairs are immutable
                // Finding index is O(N) but N is small
                val idx = activeRoutes.indexOfFirst { it.first === route && it.second == intensity }
                if (idx >= 0) {
                     activeRoutes[idx] = route to newIntensity
                }
            }
        }
    }
}

@Composable
fun ForceDirectedMeshGraph(
    nodes: List<MeshGraphService.GraphNode>,
    edges: List<MeshGraphService.GraphEdge>,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val simulation = remember { Simulation() }
    val colorScheme = MaterialTheme.colorScheme
    
    // Listen for visual events
    val debugManager = remember { DebugSettingsManager.getInstance() }
    LaunchedEffect(Unit) {
        debugManager.meshVisualEvents.collect { event ->
            when (event) {
                is MeshVisualEvent.PacketActivity -> simulation.triggerNodePulse(event.peerID)
                is MeshVisualEvent.RouteActivity -> simulation.triggerRouteAnimation(event.route)
            }
        }
    }

    // We need a state that changes on every tick to trigger redraw
    var tick by remember { mutableLongStateOf(0L) }

    // Update topology when input data changes
    LaunchedEffect(nodes, edges) {
        simulation.updateTopology(nodes, edges)
    }

    // Animation Loop
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { 
                simulation.step()
                tick++ 
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val w = maxWidth.value * density.density
        val h = maxHeight.value * density.density
        
        // Update simulation bounds if size changes
        SideEffect {
            simulation.width = w
            simulation.height = h
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Find closest node
                            val closest = simulation.nodes.values.minByOrNull { 
                                val dx = it.x - offset.x
                                val dy = it.y - offset.y
                                dx*dx + dy*dy
                            }
                            if (closest != null) {
                                val dist = sqrt((closest.x - offset.x).pow(2) + (closest.y - offset.y).pow(2))
                                if (dist < 80f) { // Touch radius
                                    closest.isDragged = true
                                }
                            }
                        },
                        onDragEnd = {
                             simulation.nodes.values.forEach { it.isDragged = false }
                        },
                        onDragCancel = {
                             simulation.nodes.values.forEach { it.isDragged = false }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dragged = simulation.nodes.values.find { it.isDragged }
                            if (dragged != null) {
                                dragged.x += dragAmount.x
                                dragged.y += dragAmount.y
                            }
                        }
                    )
                }
        ) {
            // Read tick to ensure recomposition
            val t = tick 
            
            val nodeMap = simulation.nodes
            
            // Draw Edges
            simulation.edges.forEach { edge ->
                val n1 = nodeMap[edge.a]
                val n2 = nodeMap[edge.b]
                
                if (n1 != null && n2 != null) {
                    val start = Offset(n1.x, n1.y)
                    val end = Offset(n2.x, n2.y)
                    val baseColor = Color(0xFF4A90E2)
                    
                    if (edge.isConfirmed) {
                        drawLine(
                            color = baseColor,
                            start = start,
                            end = end,
                            strokeWidth = 5f
                        )
                    } else {
                        // Unconfirmed: draw "solid" from declarer, "dashed" from other
                        val isA = (edge.confirmedBy == edge.a)
                        val solidStart = if (isA) start else end
                        val solidEnd = if (isA) end else start

                        val midX = (start.x + end.x) / 2
                        val midY = (start.y + end.y) / 2
                        val mid = Offset(midX, midY)

                        drawLine(
                            color = baseColor,
                            start = solidStart,
                            end = mid,
                            strokeWidth = 4f
                        )

                        drawLine(
                            color = baseColor.copy(alpha = 0.6f),
                            start = mid,
                            end = solidEnd,
                            strokeWidth = 4f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    }
                }
            }
            
            // Draw Active Routes (overlay)
            simulation.activeRoutes.forEach { (route, intensity) ->
                val routeColor = Color(0xFFFFD700).copy(alpha = intensity) // Gold
                val strokeW = 4f * intensity + 2f
                
                for (i in 0 until route.size - 1) {
                    val p1 = nodeMap[route[i]]
                    val p2 = nodeMap[route[i+1]]
                    if (p1 != null && p2 != null) {
                        drawLine(
                            color = routeColor,
                            start = Offset(p1.x, p1.y),
                            end = Offset(p2.x, p2.y),
                            strokeWidth = strokeW,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
            }

            // Draw Nodes
            val labelColor = colorScheme.onSurface.toArgb()
            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 12.sp.toPx()
                this.color = labelColor
            }
            
            nodeMap.values.forEach { node ->
                val center = Offset(node.x, node.y)
                val pulse = node.pulseLevel
                
                // Pulse glow
                if (pulse > 0.05f) {
                     drawCircle(
                        color = Color(0xFF00FF00).copy(alpha = pulse * 0.6f),
                        radius = 16f + (pulse * 20f),
                        center = center
                    )
                }

                drawCircle(
                    color = Color(0xFF00C851),
                    radius = 16f + (pulse * 4f), // Slight scale up
                    center = center
                )
                drawCircle(
                    color = Color.White,
                    radius = 12f + (pulse * 3f),
                    center = center,
                    style = Stroke(width = 2f)
                )
                
                // Label
                drawContext.canvas.nativeCanvas.drawText(
                    node.label,
                    node.x + 22f + (pulse * 5f), 
                    node.y + 4f,
                    textPaint
                )
            }
        }
    }
}
