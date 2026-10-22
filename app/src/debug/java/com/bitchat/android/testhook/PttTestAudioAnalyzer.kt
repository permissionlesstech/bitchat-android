package com.bitchat.android.testhook

import android.media.MediaCodec
import android.media.MediaExtractor
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

internal data class PttTestAudioAnalysis(
    val decodedSamples: Long,
    val rms: Double,
    val silentBlockFraction: Double,
    val longestSilentBlockRun: Int,
    val zeroCrossingsPerSecond: Double
)

/** Debug-only objective check of the exact ADTS stream assembled by live PTT. */
internal object PttTestAudioAnalyzer {
    private const val BLOCK_SAMPLES = 1_024
    private const val SILENT_BLOCK_RMS = 0.015
    private const val SAMPLE_RATE = 16_000.0

    fun analyze(file: File): PttTestAudioAnalysis {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString("mime")?.startsWith("audio/") == true
            } ?: error("received live stream has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString("mime") ?: error("received live stream has no audio MIME")
            val activeDecoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            decoder = activeDecoder

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var idlePolls = 0
            var decodedSamples = 0L
            var sumSquares = 0.0
            var zeroCrossings = 0L
            var previousSample: Short? = null
            var blockSquares = 0.0
            var blockSamples = 0
            var blocks = 0
            var silentBlocks = 0
            var silentRun = 0
            var longestSilentRun = 0

            while (!outputEnded && idlePolls < 500) {
                if (!inputEnded) {
                    val inputIndex = activeDecoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = activeDecoder.getInputBuffer(inputIndex) ?: error("null decoder input")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            activeDecoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            activeDecoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = activeDecoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> idlePolls++
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> idlePolls = 0
                    else -> if (outputIndex >= 0) {
                        idlePolls = 0
                        activeDecoder.getOutputBuffer(outputIndex)?.let { output ->
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.LITTLE_ENDIAN)
                            val pcm = output.asShortBuffer()
                            while (pcm.hasRemaining()) {
                                val sample = pcm.get()
                                val normalized = sample.toDouble() / Short.MAX_VALUE.toDouble()
                                val square = normalized * normalized
                                sumSquares += square
                                blockSquares += square
                                decodedSamples++
                                blockSamples++
                                previousSample?.let { previous ->
                                    if ((previous < 0 && sample >= 0) || (previous >= 0 && sample < 0)) {
                                        zeroCrossings++
                                    }
                                }
                                previousSample = sample
                                if (blockSamples == BLOCK_SAMPLES) {
                                    val blockRms = sqrt(blockSquares / blockSamples)
                                    blocks++
                                    if (blockRms < SILENT_BLOCK_RMS) {
                                        silentBlocks++
                                        silentRun++
                                        longestSilentRun = maxOf(longestSilentRun, silentRun)
                                    } else {
                                        silentRun = 0
                                    }
                                    blockSquares = 0.0
                                    blockSamples = 0
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        activeDecoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (!outputEnded) error("received live stream decoder did not finish")
            if (decodedSamples == 0L) error("received live stream decoded to no PCM")
            if (blockSamples > 0) {
                val blockRms = sqrt(blockSquares / blockSamples)
                blocks++
                if (blockRms < SILENT_BLOCK_RMS) {
                    silentBlocks++
                    silentRun++
                    longestSilentRun = maxOf(longestSilentRun, silentRun)
                }
            }
            return PttTestAudioAnalysis(
                decodedSamples = decodedSamples,
                rms = sqrt(sumSquares / decodedSamples),
                silentBlockFraction = if (blocks == 0) 1.0 else silentBlocks.toDouble() / blocks,
                longestSilentBlockRun = longestSilentRun,
                zeroCrossingsPerSecond = zeroCrossings * SAMPLE_RATE / decodedSamples
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }
}
