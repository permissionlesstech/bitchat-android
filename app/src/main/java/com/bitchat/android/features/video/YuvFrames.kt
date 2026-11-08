package com.bitchat.android.features.video

import android.graphics.Bitmap
import android.media.Image
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/** A planar/semi-planar YUV 4:2:0 picture held as plain arrays (camera frame or synthetic). */
class YuvSource(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray,
    val v: ByteArray,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    /** Clockwise rotation that makes the picture upright. */
    val rotationDegrees: Int
) {
    companion object {
        fun fromImage(image: Image, rotationDegrees: Int, crop: android.graphics.Rect = image.cropRect): YuvSource {
            val planes = image.planes
            fun read(plane: Image.Plane): ByteArray {
                val buffer = plane.buffer.duplicate()
                buffer.rewind()
                return ByteArray(buffer.remaining()).also(buffer::get)
            }
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            val y = read(yPlane)
            val u = read(uPlane)
            val v = read(vPlane)
            if (crop.left == 0 && crop.top == 0) {
                return YuvSource(
                    crop.width(), crop.height(), y, yPlane.rowStride, u, v,
                    uPlane.rowStride, uPlane.pixelStride, rotationDegrees
                )
            }
            // Re-base cropped pictures so sampling can assume an origin of (0, 0).
            val width = crop.width() and 1.inv()
            val height = crop.height() and 1.inv()
            val outY = ByteArray(width * height)
            for (row in 0 until height) {
                System.arraycopy(y, (crop.top + row) * yPlane.rowStride + crop.left * yPlane.pixelStride, outY, row * width, width)
            }
            val outU = ByteArray(width / 2 * height / 2)
            val outV = ByteArray(width / 2 * height / 2)
            for (row in 0 until height / 2) {
                for (column in 0 until width / 2) {
                    val source = (crop.top / 2 + row) * uPlane.rowStride + (crop.left / 2 + column) * uPlane.pixelStride
                    outU[row * width / 2 + column] = u.getOrElse(source) { 0x80.toByte() }
                    outV[row * width / 2 + column] = v.getOrElse(source) { 0x80.toByte() }
                }
            }
            return YuvSource(width, height, outY, width, outU, outV, width / 2, 1, rotationDegrees)
        }
    }
}

/**
 * Rotates, center-crops and downsamples [YuvSource] pictures into an encoder's input image.
 * Index maps are cached per source geometry, so steady-state conversion is a gather.
 */
class YuvScaler(private val targetWidth: Int, private val targetHeight: Int) {
    private companion object {
        const val NEUTRAL: Byte = 0x80.toByte()
    }

    private var key: List<Int>? = null
    private var lumaMap = IntArray(0)
    private var chromaMap = IntArray(0)

    val width: Int get() = targetWidth
    val height: Int get() = targetHeight

    private enum class ChromaLayout { PLANAR, NV12, NV21, UNKNOWN }

    private var rowY = ByteArray(0)
    private var rowU = ByteArray(0)
    private var rowV = ByteArray(0)
    private var rowUV = ByteArray(0)
    private var layout: ChromaLayout? = null

    /**
     * Codec input buffers are often uncached hardware memory where per-byte writes cost about a
     * microsecond, so rows are gathered locally and copied in bulk.
     */
    fun writeInto(source: YuvSource, image: Image) {
        prepare(source)
        val planes = image.planes
        val yPlane = planes[0]
        if (rowY.size != targetWidth) {
            rowY = ByteArray(targetWidth)
            rowU = ByteArray(targetWidth / 2)
            rowV = ByteArray(targetWidth / 2)
            rowUV = ByteArray(targetWidth)
        }
        val yBuffer = yPlane.buffer.duplicate()
        // Hot loops read locals only: field and getter access dominates in debuggable builds.
        val width = targetWidth
        val sourceY = source.y
        val sourceU = source.u
        val sourceV = source.v
        val luma = lumaMap
        val chromaOffsets = chromaMap
        val outY = rowY
        val outU = rowU
        val outV = rowV
        var index = 0
        for (row in 0 until targetHeight) {
            for (column in 0 until width) outY[column] = sourceY[luma[index++]]
            if (yPlane.pixelStride == 1) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.put(rowY, 0, minOf(targetWidth, yBuffer.remaining()))
            } else {
                for (column in 0 until targetWidth) yBuffer.put(row * yPlane.rowStride + column * yPlane.pixelStride, rowY[column])
            }
        }

        val uPlane = planes[1]
        val vPlane = planes[2]
        val chroma = layout ?: detectLayout(uPlane, vPlane).also { layout = it }
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val half = targetWidth / 2
        index = 0
        for (row in 0 until targetHeight / 2) {
            for (column in 0 until half) {
                val offset = chromaOffsets[index++]
                outU[column] = if (offset < sourceU.size) sourceU[offset] else NEUTRAL
                outV[column] = if (offset < sourceV.size) sourceV[offset] else NEUTRAL
            }
            when (chroma) {
                ChromaLayout.PLANAR -> {
                    uBuffer.position(row * uPlane.rowStride)
                    uBuffer.put(rowU, 0, minOf(half, uBuffer.remaining()))
                    vBuffer.position(row * vPlane.rowStride)
                    vBuffer.put(rowV, 0, minOf(half, vBuffer.remaining()))
                }
                ChromaLayout.NV12, ChromaLayout.NV21 -> {
                    val first = if (chroma == ChromaLayout.NV12) rowU else rowV
                    val second = if (chroma == ChromaLayout.NV12) rowV else rowU
                    val interleaved = rowUV
                    for (column in 0 until half) {
                        interleaved[column * 2] = first[column]
                        interleaved[column * 2 + 1] = second[column]
                    }
                    val target = if (chroma == ChromaLayout.NV12) uBuffer else vBuffer
                    val other = if (chroma == ChromaLayout.NV12) vBuffer else uBuffer
                    val rowStride = if (chroma == ChromaLayout.NV12) uPlane.rowStride else vPlane.rowStride
                    target.position(row * rowStride)
                    val length = minOf(targetWidth, target.remaining())
                    target.put(rowUV, 0, length)
                    // The leading plane's buffer can end one byte short of the final interleaved sample.
                    if (length < targetWidth) other.put(row * rowStride + targetWidth - 2, rowUV[targetWidth - 1])
                }
                ChromaLayout.UNKNOWN -> for (column in 0 until half) {
                    uBuffer.put(row * uPlane.rowStride + column * uPlane.pixelStride, rowU[column])
                    vBuffer.put(row * vPlane.rowStride + column * vPlane.pixelStride, rowV[column])
                }
            }
        }
    }

    /** Finds whether U and V interleave in one buffer (and in which order) by probing aliasing. */
    private fun detectLayout(uPlane: Image.Plane, vPlane: Image.Plane): ChromaLayout {
        if (uPlane.pixelStride == 1 && vPlane.pixelStride == 1) return ChromaLayout.PLANAR
        if (uPlane.pixelStride != 2 || vPlane.pixelStride != 2 || uPlane.rowStride != vPlane.rowStride) {
            return ChromaLayout.UNKNOWN
        }
        val u = uPlane.buffer
        val v = vPlane.buffer
        if (u.capacity() < 2 || v.capacity() < 2) return ChromaLayout.UNKNOWN
        fun aliases(writer: java.nio.ByteBuffer, reader: java.nio.ByteBuffer): Boolean {
            val saved = writer.get(1)
            val probe = (reader.get(0).toInt() xor 0x5A).toByte()
            writer.put(1, probe)
            val hit = reader.get(0) == probe
            writer.put(1, saved)
            return hit
        }
        return when {
            aliases(u, v) -> ChromaLayout.NV12
            aliases(v, u) -> ChromaLayout.NV21
            else -> ChromaLayout.UNKNOWN
        }
    }

    private fun prepare(source: YuvSource) {
        val newKey = listOf(source.width, source.height, source.yRowStride, source.uvRowStride, source.uvPixelStride, source.rotationDegrees)
        if (newKey == key) return
        key = newKey
        val rotation = ((source.rotationDegrees % 360) + 360) % 360
        val uprightWidth = if (rotation == 90 || rotation == 270) source.height else source.width
        val uprightHeight = if (rotation == 90 || rotation == 270) source.width else source.height
        // Center-crop the upright picture to the target aspect ratio, then sample.
        val scale = minOf(uprightWidth.toDouble() / targetWidth, uprightHeight.toDouble() / targetHeight)
        val offsetX = (uprightWidth - targetWidth * scale) / 2.0
        val offsetY = (uprightHeight - targetHeight * scale) / 2.0

        fun sourcePoint(ux: Int, uy: Int): Pair<Int, Int> = when (rotation) {
            90 -> uy to (source.height - 1 - ux)
            180 -> (source.width - 1 - ux) to (source.height - 1 - uy)
            270 -> (source.width - 1 - uy) to ux
            else -> ux to uy
        }

        lumaMap = IntArray(targetWidth * targetHeight)
        var index = 0
        for (row in 0 until targetHeight) {
            val uy = (offsetY + (row + 0.5) * scale).toInt().coerceIn(0, uprightHeight - 1)
            for (column in 0 until targetWidth) {
                val ux = (offsetX + (column + 0.5) * scale).toInt().coerceIn(0, uprightWidth - 1)
                val (sx, sy) = sourcePoint(ux, uy)
                lumaMap[index++] = sy * source.yRowStride + sx
            }
        }
        chromaMap = IntArray((targetWidth / 2) * (targetHeight / 2))
        index = 0
        for (row in 0 until targetHeight / 2) {
            val uy = (offsetY + (row * 2 + 1) * scale).toInt().coerceIn(0, uprightHeight - 1)
            for (column in 0 until targetWidth / 2) {
                val ux = (offsetX + (column * 2 + 1) * scale).toInt().coerceIn(0, uprightWidth - 1)
                val (sx, sy) = sourcePoint(ux, uy)
                chromaMap[index++] = (sy / 2) * source.uvRowStride + (sx / 2) * source.uvPixelStride
            }
        }
    }
}

/** Deterministic moving test card, so automated mesh tests never capture a real camera. */
class SyntheticVideoPattern(private val width: Int, private val height: Int) {
    private val y = ByteArray(width * height)
    private val u = ByteArray(width / 2 * height / 2)
    private val v = ByteArray(width / 2 * height / 2)

    fun frame(index: Int): YuvSource {
        val bars = 8
        val y = y
        val width = width
        val height = height
        val barRow = ByteArray(width) { column -> (40 + (column * bars / width) * 24).toByte() }
        for (row in 0 until height) System.arraycopy(barRow, 0, y, row * width, width)
        // A bright square that sweeps across the frame, plus a binary frame counter strip.
        val box = width / 4
        val left = (index * 3) % (width - box)
        val top = (height - box) / 2 + ((index * 2) % (height / 3)) - height / 6
        for (row in top.coerceAtLeast(0) until (top + box).coerceAtMost(height)) {
            for (column in left until left + box) y[row * width + column] = 235.toByte()
        }
        for (bit in 0 until 12) {
            val on = (index shr bit) and 1 == 1
            for (row in 0 until 6) {
                for (column in bit * (width / 12) until (bit + 1) * (width / 12)) {
                    y[row * width + column] = if (on) 235.toByte() else 16.toByte()
                }
            }
        }
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                u[row * width / 2 + column] = (64 + column * 128 / (width / 2)).toByte()
                v[row * width / 2 + column] = (192 - row * 128 / (height / 2)).toByte()
            }
        }
        return YuvSource(width, height, y, width, u, v, width / 2, 1, 0)
    }
}

/** BT.601 limited-range YUV 4:2:0 -> ARGB for decoder output images. */
object YuvToBitmap {
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

    /** Synchronized: concurrent stream decoders share the plane scratch arrays. */
    @Synchronized
    fun convert(image: Image, reuse: Bitmap?, pixels: IntArray?): Pair<Bitmap, IntArray> {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val bitmap = reuse?.takeIf { it.width == width && it.height == height && it.isMutable }
            ?: createBitmap(width, height)
        val argb = pixels?.takeIf { it.size == width * height } ?: IntArray(width * height)
        val planes = image.planes
        // Bulk-copy the planes once; per-byte reads from codec memory are slow.
        val yData = readPlane(planes[0].buffer, yBytes).also { yBytes = it }
        val uData = readPlane(planes[1].buffer, uBytes).also { uBytes = it }
        val vData = readPlane(planes[2].buffer, vBytes).also { vBytes = it }
        val ySize = yData.size
        val uSize = uData.size
        val vSize = vData.size
        val left = crop.left
        val yRow = planes[0].rowStride
        val yPixel = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPixel = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPixel = planes[2].pixelStride
        var index = 0
        for (row in 0 until height) {
            val sy = row + crop.top
            val yBase = sy * yRow
            val uBase = (sy / 2) * uRow
            val vBase = (sy / 2) * vRow
            for (column in 0 until width) {
                val sx = column + left
                val yAt = yBase + sx * yPixel
                val uAt = uBase + (sx shr 1) * uPixel
                val vAt = vBase + (sx shr 1) * vPixel
                val luma = (if (yAt < ySize) yData[yAt].toInt() and 0xFF else 16) - 16
                val cb = (if (uAt < uSize) uData[uAt].toInt() and 0xFF else 128) - 128
                val cr = (if (vAt < vSize) vData[vAt].toInt() and 0xFF else 128) - 128
                val c = 298 * luma.coerceAtLeast(0)
                val r = ((c + 409 * cr + 128) shr 8).coerceIn(0, 255)
                val g = ((c - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 255)
                val b = ((c + 516 * cb + 128) shr 8).coerceIn(0, 255)
                argb[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap to argb
    }

    private fun readPlane(buffer: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
        val copy = buffer.duplicate()
        copy.rewind()
        val target = if (reuse.size == copy.remaining()) reuse else ByteArray(copy.remaining())
        copy.get(target)
        return target
    }

    /** Mean luma of a decoded picture (test hooks use it to prove frames are not blank). */
    fun meanLuma(bitmap: Bitmap): Double {
        val sampleStep = maxOf(1, (bitmap.width * bitmap.height / 4_096.0).roundToInt())
        var total = 0L
        var count = 0
        var index = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        while (index < pixels.size) {
            val pixel = pixels[index]
            total += (((pixel shr 16) and 0xFF) * 299 + ((pixel shr 8) and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
            count++
            index += sampleStep
        }
        return if (count == 0) 0.0 else total.toDouble() / count
    }
}
