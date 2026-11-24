package com.bitchat.android.features.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes released live video units.
 *
 * When a UI [Surface] (e.g. a TextureView) is attached, MediaCodec renders frames directly to the
 * GPU with zero CPU YUV->RGB conversion and hardware timestamp pacing. When no Surface is attached,
 * it converts Bitmaps only if a Bitmap observer is subscribed, and otherwise samples luma directly
 * from the Y plane in <0.1 ms so high-rate streams never stall behind software pixel loops.
 */
internal class LiveVideoDecoder(
    private val onFrame: (Bitmap?) -> Unit,
    private val onLumaSample: ((Double) -> Unit)? = null,
    private val needsBitmap: () -> Boolean = { true },
    private val jitterMs: Long = 350L
) {
    companion object {
        private const val TAG = "LiveVideoDecoder"
        private const val QUEUE_CAPACITY = 192
        private const val POOL_SIZE = 3
        private const val REBASE_LATE_MS = 1_500L
        private const val REBASE_EARLY_MS = 3_000L
        private const val MAX_RENDER_AHEAD_MS = 450L
    }

    private class QueuedUnit(val ptsMs: Long, val keyframe: Boolean, val data: ByteArray)

    private val queue = LinkedBlockingQueue<QueuedUnit>(QUEUE_CAPACITY)
    private val stopped = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val resync = AtomicBoolean(false)
    private val decodedFrames = AtomicInteger()
    private val surfaceRef = AtomicReference<Surface?>(null)
    private val surfaceChanged = AtomicBoolean(false)
    @Volatile private var width = 0
    @Volatile private var height = 0
    private val worker = Thread(::loop, "bitchat-video-decode").also(Thread::start)

    val framesDecoded: Int get() = decodedFrames.get()

    fun configure(config: LiveVideoConfig) {
        width = config.width
        height = config.height
    }

    fun attachSurface(surface: Surface?) {
        val valid = surface?.takeIf { it.isValid }
        if (surfaceRef.getAndSet(valid) !== valid) {
            surfaceChanged.set(true)
        }
    }

    fun detachSurface(surface: Surface) {
        if (surfaceRef.compareAndSet(surface, null)) {
            surfaceChanged.set(true)
        }
    }

    fun submit(unit: LiveVideoOutput.Video) {
        if (stopped.get()) return
        if (!queue.offer(QueuedUnit(unit.ptsMs, unit.keyframe, unit.data))) {
            // Falling behind: drop the backlog and resume from the next keyframe.
            queue.clear()
            resync.set(true)
            if (unit.keyframe) queue.offer(QueuedUnit(unit.ptsMs, true, unit.data))
        }
    }

    /** After units were withheld (app in background), restart cleanly at the next keyframe. */
    fun resyncAtKeyframe() {
        queue.clear()
        resync.set(true)
    }

    fun finishAfterDrain() = finishing.set(true)

    /** Signals drain completion and waits briefly so buffered MediaCodec frames flush cleanly. */
    fun finishAndJoin(timeoutMs: Long = 600L) {
        finishing.set(true)
        if (Thread.currentThread() !== worker) {
            runCatching { worker.join(timeoutMs) }
        }
    }

    fun stop() {
        stopped.set(true)
        worker.interrupt()
    }

    private fun loop() {
        var codec: MediaCodec? = null
        var configuredSize = 0 to 0
        var configuredSurface: Surface? = null
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
            configuredSurface = null
        }

        fun configureCodec(csd: Pair<ByteArray, ByteArray>, targetSurface: Surface?): MediaCodec {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            val surface = targetSurface?.takeIf { it.isValid }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val configured = runCatching {
                decoder.configure(format, surface, null, 0)
            }.recoverCatching {
                decoder.reset()
                val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                    setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                }
                decoder.configure(plain, surface, null, 0)
            }
            if (configured.isFailure) {
                decoder.release()
                throw configured.exceptionOrNull()!!
            }
            decoder.start()
            configuredSurface = surface
            return decoder
        }

        fun computeDueMs(ptsMs: Long): Long {
            val now = System.currentTimeMillis()
            var base = baseMs ?: (now - ptsMs + jitterMs).also { baseMs = it }
            var due = base + ptsMs
            if (due < now - REBASE_LATE_MS || due > now + REBASE_EARLY_MS) {
                base = now - ptsMs + jitterMs
                baseMs = base
                due = base + ptsMs
            }
            return due
        }

        fun present(index: Int, ptsUs: Long) {
            val decoder = codec ?: return
            val ptsMs = ptsUs / 1_000L
            if (configuredSurface != null) {
                // Zero-copy GPU presentation: let SurfaceFlinger pace by render timestamp.
                val waitMs = (computeDueMs(ptsMs) - System.currentTimeMillis()).coerceIn(0L, MAX_RENDER_AHEAD_MS)
                val renderNs = System.nanoTime() + waitMs * 1_000_000L
                runCatching {
                    decoder.releaseOutputBuffer(index, renderNs)
                }.onFailure {
                    runCatching { decoder.releaseOutputBuffer(index, false) }
                }
                decodedFrames.incrementAndGet()
                onFrame(null)
                return
            }
            val image = runCatching { decoder.getOutputImage(index) }.getOrNull()
            if (image != null) {
                val frameNum = decodedFrames.incrementAndGet()
                if (!needsBitmap()) {
                    // Headless / off-screen path: sample Y-plane luma directly (<0.05 ms), no RGB loop.
                    if (frameNum % 8 == 1) {
                        onLumaSample?.invoke(runCatching { YuvToBitmap.meanLumaFromImage(image) }.getOrDefault(0.0))
                    }
                    image.close()
                    decoder.releaseOutputBuffer(index, false)
                    onFrame(null)
                    return
                }
                val due = computeDueMs(ptsMs)
                val (bitmap, buffer) = YuvToBitmap.convert(image, pool[poolIndex], pixels)
                image.close()
                pool[poolIndex] = bitmap
                pixels = buffer
                poolIndex = (poolIndex + 1) % POOL_SIZE
                decoder.releaseOutputBuffer(index, false)
                val wait = due - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait.coerceAtMost(REBASE_EARLY_MS))
                if (frameNum % 8 == 1) {
                    onLumaSample?.invoke(runCatching { YuvToBitmap.meanLuma(bitmap) }.getOrDefault(0.0))
                }
                onFrame(bitmap)
            } else {
                decoder.releaseOutputBuffer(index, false)
            }
        }

        fun handleSurfaceChange() {
            if (!surfaceChanged.getAndSet(false)) return
            val desired = surfaceRef.get()?.takeIf { it.isValid }
            val decoder = codec ?: return
            if (desired === configuredSurface) return
            if (desired != null && configuredSurface != null) {
                // Hot-swap between two valid Surfaces without dropping to keyframe.
                val swapped = runCatching { decoder.setOutputSurface(desired) }.isSuccess
                if (swapped) {
                    configuredSurface = desired
                    return
                }
            }
            // Transitioning between Surface and bytebuffer (or swap failed): reconfigure at next keyframe.
            release()
            needKeyframe = true
        }

        try {
            while (!stopped.get()) {
                handleSurfaceChange()
                val unit = queue.poll(15L, TimeUnit.MILLISECONDS)
                if (unit == null) {
                    val active = codec
                    if (finishing.get() && queue.isEmpty()) {
                        if (active != null) {
                            // Flush any frames still buffered inside MediaCodec before exiting.
                            runCatching {
                                val inIdx = active.dequeueInputBuffer(50_000L)
                                if (inIdx >= 0) {
                                    active.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                                val flushDeadline = System.nanoTime() + 400_000_000L
                                while (System.nanoTime() < flushDeadline && !stopped.get()) {
                                    if (drainOutput(active, info, ::present, waitUs = 10_000L)) break
                                }
                            }
                        }
                        break
                    }
                    active?.let { drainOutput(it, info, ::present) }
                    continue
                }
                if (resync.getAndSet(false)) needKeyframe = true
                if (needKeyframe && !unit.keyframe) continue
                if (unit.keyframe) {
                    val size = width to height
                    val desiredSurface = surfaceRef.get()?.takeIf { it.isValid }
                    if (codec == null || size != configuredSize || desiredSurface !== configuredSurface) {
                        release()
                        val csd = H264NalUnits.parameterSets(unit.data) ?: continue
                        if (width <= 0 || height <= 0) continue
                        codec = configureCodec(csd, desiredSurface)
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

    private fun drainOutput(
        decoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        present: (Int, Long) -> Unit,
        waitUs: Long = 0L
    ): Boolean {
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, waitUs)
            when {
                index >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 || !eos) {
                        present(index, info.presentationTimeUs)
                    } else {
                        decoder.releaseOutputBuffer(index, false)
                    }
                    if (eos) return true
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                else -> Unit
            }
        }
    }
}
