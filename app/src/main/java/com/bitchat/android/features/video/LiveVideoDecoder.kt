package com.bitchat.android.features.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes released live video units to Bitmaps and paces them by presentation time behind a
 * small jitter buffer, so bursty mesh delivery still plays smoothly. A decoder error drops
 * back to waiting for the next keyframe rather than surfacing corrupt pictures.
 */
internal class LiveVideoDecoder(
    private val onFrame: (Bitmap) -> Unit,
    private val jitterMs: Long = 350L
) {
    companion object {
        private const val TAG = "LiveVideoDecoder"
        private const val QUEUE_CAPACITY = 96
        private const val POOL_SIZE = 3
        private const val REBASE_LATE_MS = 1_500L
        private const val REBASE_EARLY_MS = 3_000L
    }

    private class QueuedUnit(val ptsMs: Long, val keyframe: Boolean, val data: ByteArray)

    private val queue = LinkedBlockingQueue<QueuedUnit>(QUEUE_CAPACITY)
    private val stopped = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val resync = AtomicBoolean(false)
    private val decodedFrames = AtomicInteger()
    @Volatile private var width = 0
    @Volatile private var height = 0
    private val worker = Thread(::loop, "bitchat-video-decode").also(Thread::start)

    val framesDecoded: Int get() = decodedFrames.get()

    fun configure(config: LiveVideoConfig) {
        width = config.width
        height = config.height
    }

    fun submit(unit: LiveVideoOutput.Video) {
        if (stopped.get()) return
        if (!queue.offer(QueuedUnit(unit.ptsMs, unit.keyframe, unit.data))) {
            // Falling behind: drop the backlog and resume from the next keyframe.
            queue.clear()
            if (unit.keyframe) queue.offer(QueuedUnit(unit.ptsMs, true, unit.data))
        }
    }

    /** After units were withheld (app in background), restart cleanly at the next keyframe. */
    fun resyncAtKeyframe() {
        queue.clear()
        resync.set(true)
    }

    fun finishAfterDrain() = finishing.set(true)

    fun stop() {
        stopped.set(true)
        worker.interrupt()
    }

    private fun loop() {
        var codec: MediaCodec? = null
        var configuredSize = 0 to 0
        var needKeyframe = true
        var baseMs: Long? = null
        val pool = arrayOfNulls<Bitmap>(POOL_SIZE)
        var poolIndex = 0
        var pixels: IntArray? = null
        val info = MediaCodec.BufferInfo()

        fun release() {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }

        fun present(index: Int, ptsUs: Long) {
            val decoder = codec ?: return
            val image = runCatching { decoder.getOutputImage(index) }.getOrNull()
            if (image != null) {
                val ptsMs = ptsUs / 1_000L
                val now = System.currentTimeMillis()
                var base = baseMs ?: (now - ptsMs + jitterMs).also { baseMs = it }
                var due = base + ptsMs
                if (due < now - REBASE_LATE_MS || due > now + REBASE_EARLY_MS) {
                    base = now - ptsMs + jitterMs
                    baseMs = base
                    due = base + ptsMs
                }
                val (bitmap, buffer) = YuvToBitmap.convert(image, pool[poolIndex], pixels)
                image.close()
                pool[poolIndex] = bitmap
                pixels = buffer
                poolIndex = (poolIndex + 1) % POOL_SIZE
                decoder.releaseOutputBuffer(index, false)
                val wait = due - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait.coerceAtMost(REBASE_EARLY_MS))
                decodedFrames.incrementAndGet()
                onFrame(bitmap)
            } else {
                decoder.releaseOutputBuffer(index, false)
            }
        }

        try {
            while (!stopped.get()) {
                val unit = queue.poll(30L, TimeUnit.MILLISECONDS)
                if (unit == null) {
                    if (finishing.get() && queue.isEmpty()) break
                    codec?.let { drainOutput(it, info, ::present) }
                    continue
                }
                if (resync.getAndSet(false)) needKeyframe = true
                if (needKeyframe && !unit.keyframe) continue
                if (unit.keyframe) {
                    val size = width to height
                    if (codec == null || size != configuredSize) {
                        release()
                        val csd = H264NalUnits.parameterSets(unit.data) ?: continue
                        if (width <= 0 || height <= 0) continue
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                            setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                        }
                        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                            configure(format, null, null, 0)
                            start()
                        }
                        configuredSize = size
                    }
                    needKeyframe = false
                }
                val decoder = codec ?: continue
                try {
                    var queued = false
                    var attempts = 0
                    while (!queued && attempts++ < 20 && !stopped.get()) {
                        val index = decoder.dequeueInputBuffer(10_000L)
                        if (index >= 0) {
                            decoder.getInputBuffer(index)?.apply {
                                clear()
                                put(unit.data)
                            }
                            decoder.queueInputBuffer(index, 0, unit.data.size, unit.ptsMs * 1_000L, 0)
                            queued = true
                        } else {
                            drainOutput(decoder, info, ::present)
                        }
                    }
                    drainOutput(decoder, info, ::present)
                } catch (error: MediaCodec.CodecException) {
                    Log.w(TAG, "Decoder error; waiting for next keyframe: ${error.diagnosticInfo}")
                    release()
                    needKeyframe = true
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Decoder state error; waiting for next keyframe: ${error.message}")
                    release()
                    needKeyframe = true
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.w(TAG, "Live video decode stopped: ${error.message}")
        } finally {
            release()
        }
    }

    private fun drainOutput(decoder: MediaCodec, info: MediaCodec.BufferInfo, present: (Int, Long) -> Unit) {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0L)
            when {
                index >= 0 -> {
                    if (info.size > 0 || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                        present(index, info.presentationTimeUs)
                    } else {
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit
            }
        }
    }
}
