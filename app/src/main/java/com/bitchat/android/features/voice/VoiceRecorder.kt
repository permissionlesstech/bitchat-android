
package com.bitchat.android.features.voice

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple MediaRecorder wrapper that records to M4A (AAC) for wide compatibility.
 * The resulting file has MIME audio/mp4.
 */
class VoiceRecorder(private val context: Context) {
    companion object { private const val TAG = "VoiceRecorder" }

    private var recorder: MediaRecorder? = null
    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private var outFile: File? = null

    fun start(): File? {
        stop() // ensure previous session closed
        return try {
            val dir = File(context.filesDir, "voicenotes/outgoing").apply { mkdirs() }
            val name = "voice_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
            val file = File(dir, name)
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            // Target: 16 kHz AAC @ 20 kbps ≈ 2.5 KB/sec
            // Lower sample rate and bitrate for compact, speech-optimized recordings
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(20_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start() 
            recorder = rec
            outFile = file
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            null
        }
    }

    fun pollAmplitude(): Int {
        return try {
            val amp = recorder?.maxAmplitude ?: 0
            _amplitude.value = amp
            amp
        } catch (_: Exception) { 0 }
    }

    fun stop(): File? {
        try {
            recorder?.apply {
                try { stop() } catch (_: Exception) {}
                try { reset() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val f = outFile
        recorder = null
        outFile = null
        return f
    }
}
