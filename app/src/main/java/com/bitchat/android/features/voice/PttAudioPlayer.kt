package com.bitchat.android.features.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small jitter-buffered AAC player for foreground live bursts. */
internal class PttAudioPlayer {
    companion object {
        private const val TAG = "PttAudioPlayer"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_DURATION_US = 64_000L
        private const val JITTER_FRAMES = 6
        private const val JITTER_DEADLINE_MS = 500L
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    private val frames = LinkedBlockingQueue<ByteArray>(128)
    private val stopped = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val startedAt = System.currentTimeMillis()
    private val worker = Thread(::playbackLoop, "bitchat-ptt-playback").also(Thread::start)

    fun enqueue(accessUnits: List<ByteArray>) {
        if (stopped.get()) return
        accessUnits.forEach { frames.offer(it.copyOf()) }
    }

    fun finishAfterDrain() {
        finishing.set(true)
    }

    fun stop() {
        stopped.set(true)
        worker.interrupt()
    }

    private fun playbackLoop() {
        var decoder: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            while (
                !stopped.get() && frames.size < JITTER_FRAMES &&
                System.currentTimeMillis() - startedAt < JITTER_DEADLINE_MS &&
                !finishing.get()
            ) {
                Thread.sleep(10L)
            }
            if (stopped.get() || (frames.isEmpty() && finishing.get())) return

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                // AudioSpecificConfig: AAC-LC, 16 kHz (index 8), mono.
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x14, 0x08)))
            }
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4_096)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer)
                .build()
            track.play()

            val info = MediaCodec.BufferInfo()
            var presentationUs = 0L
            var inputEnded = false
            var outputEnded = false
            while (!stopped.get() && !outputEnded) {
                if (!inputEnded) {
                    val frame = frames.poll(25L, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        val index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (index >= 0) {
                            decoder.getInputBuffer(index)?.apply {
                                clear()
                                put(frame)
                            }
                            decoder.queueInputBuffer(index, 0, frame.size, presentationUs, 0)
                            presentationUs += FRAME_DURATION_US
                        } else {
                            frames.offer(frame)
                        }
                    } else if (finishing.get()) {
                        val index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (index >= 0) {
                            decoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                presentationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        }
                    }
                }

                when (val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        decoder.getOutputBuffer(index)?.let { output ->
                            if (info.size > 0) {
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val pcm = ByteArray(info.size)
                                output.get(pcm)
                                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.w(TAG, "Live playback stopped: ${error.message}")
        } finally {
            stopped.set(true)
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
    }
}
