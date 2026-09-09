package com.bitchat.watch.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.features.voice.normalizeAmplitudeSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MAX_RECORDING_MS = 10_000L
private const val MIN_RECORDING_MS = 600L
private const val AMPLITUDE_POLL_MS = 80L
private const val LIVE_BARS = 32

/**
 * Push-to-talk recording controller: start on press, stop on release, 10 s cap, ~80 ms
 * amplitude polls into a rolling live-waveform buffer. Hoisted to screen level so the
 * full-screen overlay can render outside the edge-button slot.
 */
class VoiceNoteController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val recorderFactory: () -> VoiceRecorder,
    private val onSendVoice: (String) -> Unit
) {
    private var recorder: VoiceRecorder? = null

    var recording by mutableStateOf(false)
        private set
    var isLive by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var liveSamples by mutableStateOf(FloatArray(LIVE_BARS))
        private set

    private var pollJob: Job? = null
    private var startedAt = 0L

    fun start() {
        if (recording) return
        val activeRecorder = recorderFactory()
        activeRecorder.start() ?: return
        recorder = activeRecorder
        isLive = activeRecorder.isLive
        startedAt = System.currentTimeMillis()
        elapsedMs = 0L
        liveSamples = FloatArray(LIVE_BARS)
        recording = true
        WearHaptics.knock(context)
        pollJob = scope.launch {
            while (true) {
                delay(AMPLITUDE_POLL_MS)
                val amp = normalizeAmplitudeSample(recorder?.pollAmplitude() ?: 0)
                liveSamples = liveSamples.copyOfRange(1, LIVE_BARS) + amp
                val elapsed = System.currentTimeMillis() - startedAt
                elapsedMs = elapsed
                if (elapsed >= MAX_RECORDING_MS) {
                    stop(send = true)
                    break
                }
            }
        }
    }

    fun stop(send: Boolean) {
        if (!recording) return
        recording = false
        // The send path clicks; the cancel path stays silent here because the caller
        // plays its own reject haptic.
        if (send) WearHaptics.click(context)
        pollJob?.cancel()
        pollJob = null
        val file = recorder?.stop(canceled = !send)
        recorder = null
        isLive = false
        val elapsed = System.currentTimeMillis() - startedAt
        if (send && file != null && elapsed >= MIN_RECORDING_MS) {
            onSendVoice(file.absolutePath)
        } else {
            file?.delete()
        }
    }
}

@Composable
fun rememberVoiceNoteController(
    recorderFactory: () -> VoiceRecorder,
    onSendVoice: (String) -> Unit
): VoiceNoteController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { VoiceNoteController(context, scope, recorderFactory, onSendVoice) }
}
