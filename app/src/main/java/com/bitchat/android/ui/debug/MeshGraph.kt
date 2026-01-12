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

// Physics constants
private const val REPULSION_FORCE = 200000f
private const val SPRING_LENGTH = 150f
private const val SPRING_STRENGTH = 0.05f
private const val CENTER_GRAVITY = 0.02f
private const val DAMPING = 0.9f
private const val MAX_VELOCITY = 50f

private class GraphNodeState(
    val id: String,
    var label: String,
    var x: Float,
    var y: Float
) {
    var vx: Float = 0f
    var vy: Float = 0f
    var isDragged: Boolean = false
}

private class Simulation {
    val nodes = mutableMapOf<String, GraphNodeState>()
    // Storing edges as pairs of IDs
    val edges = mutableListOf<MeshGraphService.GraphEdge>()
    
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

        // 3. Center Gravity & Integration
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
                    val color = Color(0xFF4A90E2)
                    
                    if (edge.isConfirmed) {
                        drawLine(
                            color = color,
                            start = start,
                            end = end,
                            strokeWidth = 3f
                        )
                    } else {
                        // Unconfirmed: draw "solid" from declarer, "dashed" from other
                        // Identify which end is the declarer
                        // Check if node `a` is the declarer
                        val isA = (edge.confirmedBy == edge.a)
                        
                        val solidStart = if (isA) start else end
                        val solidEnd = if (isA) end else start

                        // We need the visual midpoint, but we want the solid part to be coming from the declaring node
                        val midX = (start.x + end.x) / 2
                        val midY = (start.y + end.y) / 2
                        val mid = Offset(midX, midY)

                        // Draw solid half from declaring node to midpoint
                        drawLine(
                            color = color,
                            start = solidStart,
                            end = mid,
                            strokeWidth = 2f
                        )

                        // Draw dashed half from midpoint to the other node
                        drawLine(
                            color = color.copy(alpha = 0.6f),
                            start = mid,
                            end = solidEnd, // Note: solidEnd is the coordinate of the non-declaring node
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
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
                drawCircle(
                    color = Color(0xFF00C851),
                    radius = 16f,
                    center = center
                )
                drawCircle(
                    color = Color.White,
                    radius = 12f,
                    center = center,
                    style = Stroke(width = 2f)
                )
                
                // Label
                drawContext.canvas.nativeCanvas.drawText(
                    node.label,
                    node.x + 22f, // Increased distance slightly (was 20f)
                    node.y + 4f,
                    textPaint
                )
            }
        }
    }
}
