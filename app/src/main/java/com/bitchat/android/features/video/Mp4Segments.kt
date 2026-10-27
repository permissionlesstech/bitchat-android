package com.bitchat.android.features.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins recordings of one live stream that a viewer captured across dropouts.
 *
 * Every segment comes from the same sender encoder, so codec parameters match and samples can
 * be copied without re-encoding. Each segment is placed at its offset on the sender's clock
 * (its first video presentation time), so the dropout plays back as a held frame.
 */
object Mp4Segments {
    private const val TAG = "Mp4Segments"
    private const val MAX_SAMPLE_BYTES = 1 shl 20

    private class Sample(val video: Boolean, val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** [segments] pairs each file with its first video timestamp on the sender's clock. */
    fun join(segments: List<Pair<File, Long>>, output: File): Boolean {
        if (segments.isEmpty()) return false
        val baseUs = segments.first().second
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        val samples = mutableListOf<Sample>()
        var lastEndUs = -1L
        for ((file, firstPtsUs) in segments) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackIsVideo = BooleanArray(extractor.trackCount)
                for (track in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    trackIsVideo[track] = mime.startsWith("video/")
                    if (trackIsVideo[track] && videoFormat == null) videoFormat = format
                    if (mime.startsWith("audio/") && audioFormat == null) audioFormat = format
                    extractor.selectTrack(track)
                }
                val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
                val segment = mutableListOf<Sample>()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val track = extractor.sampleTrackIndex
                    val data = ByteArray(size)
                    buffer.position(0)
                    buffer.get(data, 0, size)
                    segment += Sample(trackIsVideo[track], data, extractor.sampleTime, extractor.sampleFlags)
                    extractor.advance()
                }
                if (segment.isEmpty()) continue
                // Rebase each segment to its own start (muxers differ on whether they do), place
                // it on the sender's clock, and never let it overlap the previous one.
                val segmentStart = segment.minOf { it.ptsUs }
                val offsetUs = maxOf(firstPtsUs - baseUs, lastEndUs + 1)
                segment.forEach { samples += Sample(it.video, it.data, it.ptsUs - segmentStart + offsetUs, it.flags) }
                lastEndUs = offsetUs + (segment.maxOf { it.ptsUs } - segmentStart)
            } catch (error: Exception) {
                Log.w(TAG, "Skipping unreadable segment: ${error.message}")
            } finally {
                extractor.release()
            }
        }
        val video = videoFormat ?: return false
        var muxer: MediaMuxer? = null
        return try {
            output.delete()
            val mediaMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mediaMuxer
            val videoTrack = mediaMuxer.addTrack(video)
            val audioTrack = audioFormat?.let(mediaMuxer::addTrack) ?: -1
            mediaMuxer.start()
            val lastPts = longArrayOf(-1L, -1L)
            samples.sortedBy { it.ptsUs }.forEach { sample ->
                val slot = if (sample.video) 0 else 1
                val track = if (sample.video) videoTrack else audioTrack
                if (track < 0 || sample.ptsUs <= lastPts[slot]) return@forEach
                val flags = if (sample.flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                val info = MediaCodec.BufferInfo().apply { set(0, sample.data.size, sample.ptsUs, flags) }
                mediaMuxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
                lastPts[slot] = sample.ptsUs
            }
            mediaMuxer.stop()
            true
        } catch (error: Exception) {
            Log.w(TAG, "Join failed: ${error.message}")
            output.delete()
            false
        } finally {
            runCatching { muxer?.release() }
        }
    }
}
