package com.bitchat.android.ui.media

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.bitchat.android.R
import com.bitchat.android.features.video.LiveVideoCapture
import com.bitchat.android.features.video.LiveVideoLimits
import com.bitchat.android.features.video.LiveVideoQuality
import com.bitchat.android.features.video.LiveVideoTarget
import com.bitchat.android.features.video.VideoTranscoder
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private enum class StudioMode { LIVE, CLIP }
private enum class StudioPhase { IDLE, RUNNING, FINISHING, PROCESSING }

/**
 * Full-screen camera for video: go live to the current conversation, record a short clip, or
 * pick a video from the gallery. The viewfinder is cropped to the stream's 3:4 frame, so the
 * sender sees exactly what viewers receive.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun VideoStudio(
    peer: String?,
    channel: String?,
    actions: VideoComposerActions,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val liveTarget: LiveVideoTarget? = remember { actions.createLiveTarget(peer, channel) }
    var mode by remember { mutableStateOf(if (liveTarget != null) StudioMode.LIVE else StudioMode.CLIP) }
    var phase by remember { mutableStateOf(StudioPhase.IDLE) }
    var quality by remember { mutableStateOf(LiveVideoQuality.LOW) }
    var micOn by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var confirmEnd by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var uplinkKbps by remember { mutableDoubleStateOf(0.0) }
    val captureRef = remember { AtomicReference<LiveVideoCapture?>(null) }
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState()
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val outgoingDir = remember { File(context.filesDir, "videos/outgoing").apply { mkdirs() } }
    val latestOnClose by rememberUpdatedState(onClose)

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true || hasPermission(context, Manifest.permission.CAMERA)
        micOn = result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filterNot { hasPermission(context, it) }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    fun finish(canceled: Boolean) {
        val capture = captureRef.getAndSet(null) ?: return
        val wasLive = mode == StudioMode.LIVE
        phase = StudioPhase.FINISHING
        scope.launch {
            val file = withContext(Dispatchers.IO) { capture.stop(canceled) }
            if (file == null) {
                if (!canceled) Toast.makeText(context, R.string.video_too_short, Toast.LENGTH_SHORT).show()
                phase = StudioPhase.IDLE
                if (wasLive) latestOnClose()
                return@launch
            }
            if (wasLive) {
                actions.onLiveFinished(peer, channel, file.absolutePath)
                latestOnClose()
                return@launch
            }
            // Clips are recorded to fit; re-encode the rare one that overshoots the budget. Clips are
            // renamed away from `video_<streamID>` so receivers never treat them as a live final.
            val clip = File(outgoingDir, "clip_${System.currentTimeMillis()}.mp4")
            val ready = if (file.length() <= LiveVideoLimits.MAX_VIDEO_FILE_BYTES) {
                if (file.renameTo(clip)) clip else file
            } else {
                phase = StudioPhase.PROCESSING
                withContext(Dispatchers.IO) {
                    VideoTranscoder.transcode(context, Uri.fromFile(file), clip)?.file.also { file.delete() }
                }
            }
            if (ready != null) actions.onVideoReady(peer, channel, ready.absolutePath)
            else Toast.makeText(context, R.string.video_prepare_failed, Toast.LENGTH_SHORT).show()
            latestOnClose()
        }
    }

    fun start() {
        val target = if (mode == StudioMode.LIVE) liveTarget ?: return else null
        val preset = if (mode == StudioMode.LIVE) quality else LiveVideoQuality.CLIP
        val capture = LiveVideoCapture(
            outputDirectory = outgoingDir,
            target = target,
            quality = preset,
            audioEnabled = micOn && hasPermission(context, Manifest.permission.RECORD_AUDIO)
        )
        if (capture.start() == null) {
            Toast.makeText(context, R.string.video_camera_failed, Toast.LENGTH_SHORT).show()
            return
        }
        captureRef.set(capture)
        startedAt = System.currentTimeMillis()
        phase = StudioPhase.RUNNING
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        phase = StudioPhase.PROCESSING
        scope.launch {
            val out = File(outgoingDir, "clip_${System.currentTimeMillis()}.mp4")
            val result = withContext(Dispatchers.IO) { VideoTranscoder.transcode(context, uri, out) }
            if (result != null) {
                if (result.trimmed) Toast.makeText(context, R.string.video_trimmed, Toast.LENGTH_SHORT).show()
                actions.onVideoReady(peer, channel, result.file.absolutePath)
                latestOnClose()
            } else {
                Toast.makeText(context, R.string.video_prepare_failed, Toast.LENGTH_SHORT).show()
                phase = StudioPhase.IDLE
            }
        }
    }

    // Uplink stats and the clip length limit while running.
    LaunchedEffect(phase) {
        while (phase == StudioPhase.RUNNING) {
            val capture = captureRef.get() ?: break
            uplinkKbps = capture.stats().sendKbps
            if (mode == StudioMode.CLIP && capture.elapsedMs() >= LiveVideoLimits.MAX_CLIP_DURATION_MS) {
                finish(canceled = false)
                break
            }
            delay(250)
        }
    }

    // Camera binding: a preview for the sender plus a YUV stream for the encoder.
    DisposableEffect(cameraGranted, useFrontCamera, lifecycleOwner) {
        if (!cameraGranted) return@DisposableEffect onDispose { }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider { request -> surfaceRequests.value = request }
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor) { proxy ->
                        try {
                            val image = proxy.image
                            if (image != null) captureRef.get()?.offerFrame(image, proxy.imageInfo.rotationDegrees)
                        } finally {
                            proxy.close()
                        }
                    }
                }
            val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }.onFailure { Log.w("VideoStudio", "Camera bind failed: ${it.message}") }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            surfaceRequests.value = null
            runCatching { provider?.unbindAll() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the screen ends whatever is running and still delivers it.
            captureRef.getAndSet(null)?.let { capture ->
                val wasLive = mode == StudioMode.LIVE
                Thread {
                    val file = capture.stop(canceled = false)
                    if (file != null) {
                        if (wasLive) {
                            actions.onLiveFinished(peer, channel, file.absolutePath)
                        } else if (file.length() <= LiveVideoLimits.MAX_VIDEO_FILE_BYTES) {
                            val clip = File(outgoingDir, "clip_${System.currentTimeMillis()}.mp4")
                            actions.onVideoReady(peer, channel, (if (file.renameTo(clip)) clip else file).absolutePath)
                        }
                    }
                }.start()
            }
            analysisExecutor.shutdown()
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)
    ) {
        ImmersiveDialogEffect()
        BackHandler(enabled = true) {
            when {
                phase == StudioPhase.RUNNING && mode == StudioMode.LIVE -> confirmEnd = true
                phase == StudioPhase.RUNNING -> finish(canceled = false)
                phase == StudioPhase.IDLE -> onClose()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                StudioTopBar(
                    running = phase == StudioPhase.RUNNING,
                    live = mode == StudioMode.LIVE,
                    startedAt = startedAt,
                    uplinkKbps = uplinkKbps,
                    quality = quality,
                    onClose = {
                        if (phase == StudioPhase.RUNNING && mode == StudioMode.LIVE) confirmEnd = true
                        else if (phase == StudioPhase.RUNNING) finish(canceled = true)
                        else if (phase == StudioPhase.IDLE) onClose()
                    },
                    onFlip = { useFrontCamera = !useFrontCamera }
                )

                // Viewfinder at the stream's aspect ratio.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF111111))
                    ) {
                        val request = surfaceRequest
                        if (cameraGranted && request != null) {
                            CameraXViewfinder(
                                surfaceRequest = request,
                                implementationMode = ImplementationMode.EMBEDDED,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (!cameraGranted) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.video_camera_permission),
                                    color = Color.White.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center
                                )
                                TextButton(onClick = {
                                    permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                                }) { Text(stringResource(R.string.video_grant_camera), color = Color.White) }
                            }
                        }
                        if (phase == StudioPhase.RUNNING && mode == StudioMode.CLIP) {
                            VideoOverlayPill(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(10.dp)
                            ) {
                                ClipTimer(startedAt)
                            }
                        }
                    }
                    if (phase == StudioPhase.FINISHING || phase == StudioPhase.PROCESSING) {
                        Column(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(
                                    if (phase == StudioPhase.PROCESSING) R.string.video_compressing else R.string.video_finishing
                                ),
                                color = Color.White
                            )
                        }
                    }
                }

                StudioControls(
                    mode = mode,
                    phase = phase,
                    liveAvailable = liveTarget != null,
                    quality = quality,
                    micOn = micOn,
                    startedAt = startedAt,
                    onMode = { if (phase == StudioPhase.IDLE) mode = it },
                    onQuality = { if (phase == StudioPhase.IDLE) quality = it },
                    onMic = {
                        if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                            permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        } else {
                            micOn = !micOn
                            captureRef.get()?.setMuted(!micOn)
                        }
                    },
                    onGallery = {
                        if (phase == StudioPhase.IDLE) {
                            galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        }
                    },
                    onShutter = {
                        when (phase) {
                            StudioPhase.IDLE -> if (cameraGranted) start()
                            StudioPhase.RUNNING -> if (mode == StudioMode.LIVE) confirmEnd = true else finish(canceled = false)
                            else -> Unit
                        }
                    }
                )
            }
        }

        if (confirmEnd) {
            AlertDialog(
                onDismissRequest = { confirmEnd = false },
                title = { Text(stringResource(R.string.video_end_live_title)) },
                text = { Text(stringResource(R.string.video_end_live_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmEnd = false
                        finish(canceled = false)
                    }) { Text(stringResource(R.string.video_end_live_confirm), color = LiveRed) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}

@Composable
private fun StudioTopBar(
    running: Boolean,
    live: Boolean,
    startedAt: Long,
    uplinkKbps: Double,
    quality: LiveVideoQuality,
    onClose: () -> Unit,
    onFlip: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp)
    ) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close), tint = Color.White)
        }
        if (running && live) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LiveBadge(startedAtMs = startedAt)
                Text(
                    text = stringResource(R.string.video_uplink, uplinkKbps, stringResource(quality.labelRes())),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = BitchatFontFamily
                )
            }
        }
        IconButton(onClick = onFlip, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Filled.Cameraswitch, contentDescription = stringResource(R.string.cd_switch_camera), tint = Color.White)
        }
    }
}

@Composable
private fun ClipTimer(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val remaining = (LiveVideoLimits.MAX_CLIP_DURATION_MS - (now - startedAt)).coerceAtLeast(0L)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(LiveRed, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${formatVideoDuration(now - startedAt)} · -${formatVideoDuration(remaining + 999)}",
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = BitchatFontFamily
        )
    }
}

@Composable
private fun StudioControls(
    mode: StudioMode,
    phase: StudioPhase,
    liveAvailable: Boolean,
    quality: LiveVideoQuality,
    micOn: Boolean,
    startedAt: Long,
    onMode: (StudioMode) -> Unit,
    onQuality: (LiveVideoQuality) -> Unit,
    onMic: () -> Unit,
    onGallery: () -> Unit,
    onShutter: () -> Unit
) {
    val idle = phase == StudioPhase.IDLE
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(visible = idle && mode == StudioMode.LIVE, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveVideoQuality.entries.filter { it.isLivePreset }.forEach { preset ->
                        val selected = preset == quality
                        Text(
                            text = stringResource(preset.labelRes()),
                            color = if (selected) Color.Black else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
                                .clickable { onQuality(preset) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(quality.hintRes()),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StudioRoundButton(enabled = idle, onClick = onGallery) {
                Icon(Icons.Filled.VideoLibrary, contentDescription = stringResource(R.string.cd_pick_video), tint = Color.White)
            }
            ShutterButton(
                live = mode == StudioMode.LIVE,
                running = phase == StudioPhase.RUNNING,
                startedAt = startedAt,
                enabled = phase == StudioPhase.IDLE || phase == StudioPhase.RUNNING,
                onClick = onShutter
            )
            StudioRoundButton(enabled = true, onClick = onMic) {
                Icon(
                    imageVector = if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = stringResource(if (micOn) R.string.cd_mute else R.string.cd_unmute),
                    tint = if (micOn) Color.White else LiveRed
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        AnimatedVisibility(visible = idle, enter = fadeIn(), exit = fadeOut()) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ModeLabel(
                    text = stringResource(R.string.video_mode_live),
                    selected = mode == StudioMode.LIVE,
                    enabled = liveAvailable,
                    onClick = { onMode(StudioMode.LIVE) }
                )
                ModeLabel(
                    text = stringResource(R.string.video_mode_clip),
                    selected = mode == StudioMode.CLIP,
                    enabled = true,
                    onClick = { onMode(StudioMode.CLIP) }
                )
            }
        }
        if (idle && !liveAvailable) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.video_live_unavailable),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = when {
            !enabled -> Color.White.copy(alpha = 0.3f)
            selected -> Color(0xFFFFC107)
            else -> Color.White.copy(alpha = 0.8f)
        },
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun StudioRoundButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Live: red disc that morphs into a stop square. Clip: record button with a time-limit ring. */
@Composable
private fun ShutterButton(live: Boolean, running: Boolean, startedAt: Long, enabled: Boolean, onClick: () -> Unit) {
    val innerSize by animateDpAsState(if (running) 30.dp else 60.dp, label = "shutterInner")
    val corner by animateDpAsState(if (running) 8.dp else 30.dp, label = "shutterCorner")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(50)
        }
    }
    val clipProgress by animateFloatAsState(
        if (running && !live) ((now - startedAt).toFloat() / LiveVideoLimits.MAX_CLIP_DURATION_MS).coerceIn(0f, 1f) else 0f,
        label = "clipProgress"
    )
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = Color.White, radius = size.minDimension / 2 - 3.dp.toPx(), style = Stroke(width = 4.dp.toPx()))
            if (clipProgress > 0f) {
                drawArc(
                    color = LiveRed,
                    startAngle = -90f,
                    sweepAngle = 360f * clipProgress,
                    useCenter = false,
                    topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                    size = ComposeSize(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(corner))
                .background(LiveRed),
            contentAlignment = Alignment.Center
        ) {
            if (live && !running) {
                Text(
                    text = stringResource(R.string.video_live_badge),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

private fun LiveVideoQuality.labelRes(): Int = when (this) {
    LiveVideoQuality.LOW -> R.string.video_quality_low
    LiveVideoQuality.MEDIUM -> R.string.video_quality_medium
    LiveVideoQuality.HIGH -> R.string.video_quality_high
    LiveVideoQuality.CLIP -> R.string.video_mode_clip
}

private fun LiveVideoQuality.hintRes(): Int = when (this) {
    LiveVideoQuality.LOW -> R.string.video_quality_low_hint
    LiveVideoQuality.MEDIUM -> R.string.video_quality_medium_hint
    LiveVideoQuality.HIGH -> R.string.video_quality_high_hint
    LiveVideoQuality.CLIP -> R.string.video_quality_low_hint
}

private fun hasPermission(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
