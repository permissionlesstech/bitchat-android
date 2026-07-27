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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the button must be held before a recording starts.
 *
 * Push-to-talk should require intent. Firing on raw pointer-down meant a stray touch started a
 * recording, and it also made the control impossible to defend against pointer events it should
 * never have seen (see [ArmDelayMs]).
 */
private const val HoldToRecordMs = 220L

/**
 * How long the button ignores presses after entering composition.
 *
 * The action cluster swaps send out for camera+microphone the instant the field is cleared, which
 * puts the microphone exactly where the send button was a frame earlier. Tapping send quickly
 * could hand the microphone a pointer-down whose matching pointer-up had already been delivered
 * to the send button that no longer exists — leaving the gesture waiting for a release that will
 * never come, stuck in "recording" until the composable is disposed. Refusing presses until the
 * swap animation has settled removes that whole class of failure.
 */
private const val ArmDelayMs = 350L

/** Hard cap on a single recording. */
private const val MaxRecordingMs = 10_000L

/** Tail kept after release so the last syllable is not clipped. */
private const val ReleaseTailMs = 500L

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
    onFinish: (filePath: String) -> Unit,
    /**
     * Invoked whenever a recording ends without producing a file — permission denied, recorder
     * failure, or the button being torn down mid-capture. The caller needs this to clear its own
     * recording state; without it a failed capture left the composer stuck in recording mode.
     */
    onCancel: () -> Unit = {}
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
    val latestOnCancel = rememberUpdatedState(onCancel)

    // Set when this instance was composed, so presses inherited from whatever occupied this spot
    // beforehand can be rejected.
    val composedAt = remember { System.currentTimeMillis() }

    fun buzz() {
        try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (_: Exception) {
        }
    }

    // Last line of defence: if the button is removed while capturing — the cluster swapping to
    // send, the sheet closing, the screen going away — release the recorder and tell the caller,
    // so nothing is left holding the microphone or showing a recording UI.
    DisposableEffect(Unit) {
        onDispose {
            ampJob?.cancel()
            ampJob = null
            if (isCapturing) {
                isCapturing = false
                runCatching { recorder?.stop() }
                recorder = null
                recordedFilePath = null
                latestOnCancel.value()
            }
        }
    }

    // Same disc, same sizing and the same press feedback as the camera and send buttons.
    ComposerActionSurface(
        isActive = isRecording || isCapturing,
        isPressed = isCapturing,
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Guard 1: ignore anything arriving before the swap animation settled.
                        if (System.currentTimeMillis() - composedAt < ArmDelayMs) {
                            return@detectTapGestures
                        }
                        // Guard 2: never start a second capture on top of a live one.
                        if (isCapturing) return@detectTapGestures

                        if (micPermission.status !is PermissionStatus.Granted) {
                            micPermission.launchPermissionRequest()
                            return@detectTapGestures
                        }

                        // Guard 3: require a deliberate hold. `tryAwaitRelease` returns true on
                        // release and false on cancellation; either way the press was not a hold,
                        // so nothing should happen. Only a timeout means the finger is still down.
                        val stillHeld = withTimeoutOrNull(HoldToRecordMs) {
                            tryAwaitRelease()
                        } == null
                        if (!stillHeld) return@detectTapGestures

                        val rec = VoiceRecorder(context)
                        val startedFile = rec.start()
                        if (startedFile == null) {
                            // Recorder refused to start; make sure the caller does not sit in a
                            // recording state that never began.
                            runCatching { rec.stop() }
                            latestOnCancel.value()
                            return@detectTapGestures
                        }

                        recorder = rec
                        recordedFilePath = startedFile.absolutePath
                        recordingStart = System.currentTimeMillis()
                        isCapturing = true
                        latestOnStart.value()
                        buzz()

                        ampJob?.cancel()
                        ampJob = scope.launch {
                            while (isActive && isCapturing) {
                                val amp = recorder?.pollAmplitude() ?: 0
                                val elapsed =
                                    (System.currentTimeMillis() - recordingStart).coerceAtLeast(0L)
                                latestOnAmplitude.value(amp, elapsed)

                                if (elapsed >= MaxRecordingMs && isCapturing) {
                                    val file = recorder?.stop()
                                    isCapturing = false
                                    recorder = null
                                    val path = file?.absolutePath ?: recordedFilePath
                                    recordedFilePath = null
                                    buzz()
                                    // Always report the outcome, even when the file is unusable,
                                    // or the caller stays stuck showing the waveform.
                                    if (!path.isNullOrBlank()) {
                                        latestOnFinish.value(path)
                                    } else {
                                        latestOnCancel.value()
                                    }
                                    break
                                }
                                delay(80)
                            }
                        }

                        try {
                            tryAwaitRelease()
                        } finally {
                            if (isCapturing) {
                                // Keep going briefly past the release so the tail is not clipped.
                                delay(ReleaseTailMs)
                            }
                            if (isCapturing) {
                                val file = recorder?.stop()
                                isCapturing = false
                                recorder = null
                                val path = file?.absolutePath ?: recordedFilePath
                                recordedFilePath = null
                                buzz()
                                if (!path.isNullOrBlank()) {
                                    latestOnFinish.value(path)
                                } else {
                                    latestOnCancel.value()
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
