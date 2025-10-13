package com.bitchat.android.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
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
    backgroundColor: Color,
    onStart: () -> Unit,
    onAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onFinish: (filePath: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordedFilePath by remember { mutableStateOf<String?>(null) }
    var recordingStart by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()
    var ampJob by remember { mutableStateOf<Job?>(null) }

    // Ensure latest callbacks are used inside gesture coroutine
    val latestOnStart = rememberUpdatedState(onStart)
    val latestOnAmplitude = rememberUpdatedState(onAmplitude)
    val latestOnFinish = rememberUpdatedState(onFinish)

    Box(
        modifier = modifier
            .size(32.dp)
            .background(backgroundColor, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (!isRecording) {
                            if (micPermission.status !is PermissionStatus.Granted) {
                                micPermission.launchPermissionRequest()
                                return@detectTapGestures
                            }
                            val rec = VoiceRecorder(context)
                            val f = rec.start()
                            recorder = rec
                            isRecording = f != null
                            recordedFilePath = f?.absolutePath
                            recordingStart = System.currentTimeMillis()
                            if (isRecording) {
                                latestOnStart.value()
                                // Haptic "knock" when recording starts
                                try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                // Start amplitude polling loop
                                ampJob?.cancel()
                                ampJob = scope.launch {
                                    while (isActive && isRecording) {
                                        val amp = recorder?.pollAmplitude() ?: 0
                                        val elapsedMs = (System.currentTimeMillis() - recordingStart).coerceAtLeast(0L)
                                        latestOnAmplitude.value(amp, elapsedMs)
                                        // Auto-stop after 10 seconds
                                        if (elapsedMs >= 10_000 && isRecording) {
                                            val file = recorder?.stop()
                                            isRecording = false
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
                            if (isRecording) {
                                // Extend recording for 500ms after release to avoid clipping
                                delay(500)
                            }
                            if (isRecording) {
                                val file = recorder?.stop()
                                isRecording = false
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
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(com.bitchat.android.R.string.cd_record_voice),
            tint = Color.Black,
            modifier = Modifier.size(20.dp)
        )
    }
}
