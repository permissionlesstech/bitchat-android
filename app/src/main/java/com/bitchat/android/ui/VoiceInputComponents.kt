package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import android.Manifest
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.bitchat.android.features.voice.VoiceRecorder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VoiceRecordButton(
    modifier: Modifier = Modifier,
    /**
     * Recording state as the composer sees it. Drives the active tint so the button and the
     * pill's border change together instead of one lagging the other.
     */
    isRecording: Boolean = false,
    onStart: () -> Unit,
    onAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onFinish: (filePath: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var isCapturing by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordedFilePath by remember { mutableStateOf<String?>(null) }
    var recordingStart by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()
    var ampJob by remember { mutableStateOf<Job?>(null) }

    // Ensure latest callbacks are used inside gesture coroutine
    val latestOnStart = rememberUpdatedState(onStart)
    val latestOnAmplitude = rememberUpdatedState(onAmplitude)
    val latestOnFinish = rememberUpdatedState(onFinish)

    // Same disc, same sizing and the same press feedback as the camera and send buttons.
    ComposerActionSurface(
        isActive = isRecording || isCapturing,
        isPressed = isCapturing,
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!isCapturing) {
                            if (micPermission.status !is PermissionStatus.Granted) {
                                micPermission.launchPermissionRequest()
                                return@detectTapGestures
                            }
                            val rec = VoiceRecorder(context)
                            val f = rec.start()
                            recorder = rec
                            isCapturing = f != null
                            recordedFilePath = f?.absolutePath
                            recordingStart = System.currentTimeMillis()
                            if (isCapturing) {
                                latestOnStart.value()
                                // Haptic "knock" when recording starts
                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                // Start amplitude polling loop
                                ampJob?.cancel()
                                ampJob = scope.launch {
                                    while (isActive && isCapturing) {
                                        val amp = recorder?.pollAmplitude() ?: 0
                                        val elapsedMs = (System.currentTimeMillis() - recordingStart).coerceAtLeast(0L)
                                        latestOnAmplitude.value(amp, elapsedMs)
                                        // Auto-stop after 10 seconds
                                        if (elapsedMs >= 10_000 && isCapturing) {
                                            val file = recorder?.stop()
                                            isCapturing = false
                                            recorder = null
                                            val path = file?.absolutePath
                                            if (!path.isNullOrBlank()) {
                                                // Haptic "knock" on auto stop
                                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                                latestOnFinish.value(path)
                                            }
                                            break
                                        }
                                        delay(80)
                                    }
                                }
                            }
                        }
                        try {
                            awaitRelease()
                        } finally {
                            if (isCapturing) {
                                // Extend recording for 500ms after release to avoid clipping
                                delay(500)
                            }
                            if (isCapturing) {
                                val file = recorder?.stop()
                                isCapturing = false
                                recorder = null
                                val path = (file?.absolutePath ?: recordedFilePath)
                                recordedFilePath = null
                                if (!path.isNullOrBlank()) {
                                    // Haptic "knock" when recording stops
                                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                    latestOnFinish.value(path)
                                }
                            }
                            ampJob?.cancel()
                            ampJob = null
                        }
                    }
                )
            }
    ) { tint ->
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(com.bitchat.android.R.string.cd_record_voice),
            tint = tint,
            modifier = Modifier.size(ComposerIconSize)
        )
    }
}
