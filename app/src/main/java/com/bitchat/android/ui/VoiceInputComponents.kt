package com.bitchat.android.ui

import android.Manifest
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import com.bitchat.android.features.voice.VoiceRecorder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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
    recorderFactory: (() -> VoiceRecorder)? = null,
    courtesyActive: Boolean = false,
    /**
     * Recording state as the composer sees it. Drives the active tint so the button and the
     * pill's border change together instead of one lagging the other.
     */
    isRecording: Boolean = false,
    /**
     * Consulted the instant the finger lifts, with the final pointer position in root
     * coordinates: when it lands inside the slide-to-cancel target, the recording is
     * discarded instead of sent. Receiving the position here (instead of reading composed
     * state) keeps the verdict exact even for a slide-and-lift within a single frame.
     */
    shouldCancel: (Offset) -> Boolean = { false },
    /**
     * Finger position in root coordinates while a capture is live (drives the magnetic
     * cancel target); null once the gesture ends.
     */
    onTrackFinger: (Offset?) -> Unit = {},
    onStart: (isLive: Boolean) -> Unit,
    onAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onFinish: (filePath: String) -> Unit,
    /**
     * Invoked whenever a recording ends without producing a file — permission denied, recorder
     * failure, the button being torn down mid-capture, or a deliberate slide-to-cancel.
     */
    onCancel: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var isCapturing by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordedFilePath by remember { mutableStateOf<String?>(null) }
    var recordingStart by remember { mutableStateOf(0L) }
    var buttonCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val scope = rememberCoroutineScope()
    var ampJob by remember { mutableStateOf<Job?>(null) }

    // Ensure latest callbacks are used inside gesture coroutine
    val latestOnStart = rememberUpdatedState(onStart)
    val latestOnAmplitude = rememberUpdatedState(onAmplitude)
    val latestOnFinish = rememberUpdatedState(onFinish)
    val latestOnCancel = rememberUpdatedState(onCancel)
    val latestShouldCancel = rememberUpdatedState(shouldCancel)
    val latestOnTrackFinger = rememberUpdatedState(onTrackFinger)
    val latestRecorderFactory = rememberUpdatedState(recorderFactory)

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
                runCatching { recorder?.stop(canceled = true) }
                recorder = null
                recordedFilePath = null
                latestOnTrackFinger.value(null)
                latestOnCancel.value()
            }
        }
    }

    // Same disc, same sizing and the same press feedback as the camera and send buttons.
    ComposerActionSurface(
        isActive = isRecording || isCapturing || courtesyActive,
        isPressed = isCapturing,
        activeColor = if (courtesyActive && !isRecording && !isCapturing) {
            androidx.compose.ui.graphics.Color(0xFFFFB300)
        } else androidx.compose.ui.graphics.Color.Unspecified,
        modifier = modifier
            .onGloballyPositioned { buttonCoords = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Guard 1: ignore anything arriving before the swap animation settled.
                    if (System.currentTimeMillis() - composedAt < ArmDelayMs) {
                        return@awaitEachGesture
                    }
                    // Guard 2: never start a second capture on top of a live one.
                    if (isCapturing) return@awaitEachGesture

                    if (micPermission.status !is PermissionStatus.Granted) {
                        micPermission.launchPermissionRequest()
                        return@awaitEachGesture
                    }

                    // Guard 3: require a deliberate hold. An up (or a stolen pointer) inside the
                    // arm window means the press was never a hold; only the timeout means the
                    // finger is still down.
                    var stolenDuringArm = false
                    val releasedEarly = withTimeoutOrNull(HoldToRecordMs) {
                        waitForUpOrCancellation().also { if (it == null) stolenDuringArm = true }
                    }
                    if (releasedEarly != null || stolenDuringArm) return@awaitEachGesture

                    val rec = latestRecorderFactory.value?.invoke() ?: VoiceRecorder(context)
                    val startedFile = rec.start()
                    if (startedFile == null) {
                        // Recorder refused to start; make sure the caller does not sit in a
                        // recording state that never began.
                        runCatching { rec.stop(canceled = true) }
                        latestOnCancel.value()
                        return@awaitEachGesture
                    }

                    recorder = rec
                    recordedFilePath = startedFile.absolutePath
                    recordingStart = System.currentTimeMillis()
                    isCapturing = true
                    latestOnStart.value(rec.isLive)
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
                                val path = file?.absolutePath ?: recordedFilePath?.takeIf { File(it).isFile }
                                recordedFilePath = null
                                latestOnTrackFinger.value(null)
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

                    // Track the finger in root coordinates until it lifts, so the composer can
                    // run the magnetic slide-to-cancel target. A cancelled pointer (stolen by a
                    // scroller) ends the capture the same way a lift does.
                    var finalPos: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        finalPos = buttonCoords?.localToRoot(change.position)
                        finalPos?.let { latestOnTrackFinger.value(it) }
                        if (!change.pressed) break
                    }

                    // Cancelling discards immediately; sending keeps a short tail so the last
                    // syllable is not clipped (an early pointer event simply ends the tail).
                    // The verdict is computed from the final pointer coordinate directly —
                    // reading recomposed state here could be one frame stale.
                    val cancel = finalPos?.let { latestShouldCancel.value(it) } == true
                    latestOnTrackFinger.value(null)
                    if (isCapturing && !cancel) {
                        withTimeoutOrNull(ReleaseTailMs) { awaitPointerEvent() }
                    }
                    if (isCapturing) {
                        val file = recorder?.stop(canceled = cancel)
                        isCapturing = false
                        recorder = null
                        val path = file?.absolutePath ?: recordedFilePath?.takeIf { File(it).isFile }
                        recordedFilePath = null
                        if (cancel) {
                            path?.let { runCatching { File(it).delete() } }
                            try {
                                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            } catch (_: Exception) {
                            }
                            latestOnCancel.value()
                        } else {
                            buzz()
                            if (!path.isNullOrBlank()) {
                                latestOnFinish.value(path)
                            } else {
                                latestOnCancel.value()
                            }
                        }
                    }
                    ampJob?.cancel()
                    ampJob = null
                }
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
