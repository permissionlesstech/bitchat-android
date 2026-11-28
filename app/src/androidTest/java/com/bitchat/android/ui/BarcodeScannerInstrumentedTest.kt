package com.bitchat.android.ui

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls the same analyzer constructor as ScannerView, with minified ML Kit dependencies. */
class BarcodeScannerInstrumentedTest {
    @Test
    fun scannerCreationCompletesAfterColdStart() {
        QRCodeAnalyzer { }
    }

    @Test
    fun realAnalyzerDecodesSyntheticCameraFrameAndClosesIt() {
        val decoded = AtomicReference<String>()
        val codeReceived = CountDownLatch(1)
        val frameClosed = CountDownLatch(1)
        val analyzer = QRCodeAnalyzer {
            decoded.set(it)
            codeReceived.countDown()
        }
        val thread = HandlerThread("synthetic-scanner-frame").apply { start() }
        // A fixed, synthetic QR encodes "bitchat:test-scanner". No camera or permissions needed.
        val modules = java.util.ArrayList<String>()
        val rows = java.util.StringTokenizer(QR_MODULES)
        while (rows.hasMoreTokens()) modules.add(rows.nextToken())
        val scale = 8
        val size = modules.size * scale
        val reader = ImageReader.newInstance(size, size, ImageFormat.YUV_420_888, 2)
        val writer = ImageWriter.newInstance(reader.surface, 2)
        try {
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireNextImage() ?: return@setOnImageAvailableListener
                val info = Proxy.newProxyInstance(ImageInfo::class.java.classLoader,
                    arrayOf(ImageInfo::class.java)) { _, method, _ ->
                    when (method.name) {
                        "getRotationDegrees" -> 0
                        else -> error("Unexpected frame info: ${method.name}")
                    }
                } as ImageInfo
                val proxy = Proxy.newProxyInstance(ImageProxy::class.java.classLoader,
                    arrayOf(ImageProxy::class.java)) { _, method, _ ->
                    when (method.name) {
                        "getImage" -> image
                        "getImageInfo" -> info
                        "close" -> { image.close(); frameClosed.countDown(); null }
                        else -> error("Unexpected frame access: ${method.name}")
                    }
                } as ImageProxy
                analyzer.analyze(proxy)
            }, Handler(thread.looper))
            val input = writer.dequeueInputImage()
            input.planes.forEachIndexed { planeIndex, plane ->
                val planeSize = if (planeIndex == 0) size else size / 2
                for (y in 0 until planeSize) for (x in 0 until planeSize) {
                    val value = if (planeIndex != 0) 128 else
                        if (modules[y / scale][x / scale] == '1') 0 else 255
                    plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                }
            }
            writer.queueInputImage(input)
            assertTrue("Real release scanner must decode the frame", codeReceived.await(20, TimeUnit.SECONDS))
            assertEquals("bitchat:test-scanner", decoded.get())
            assertTrue("Analyzer must release the camera frame", frameClosed.await(5, TimeUnit.SECONDS))
        } finally {
            writer.close()
            reader.close()
            thread.quitSafely()
            thread.join(5_000)
        }
    }

    private companion object {
        const val QR_MODULES = """
            000000000000000000000000000000000
            000000000000000000000000000000000
            000000000000000000000000000000000
            000000000000000000000000000000000
            000011111110110001001011111110000
            000010000010000001000010000010000
            000010111010100111010010111010000
            000010111010110101100010111010000
            000010111010111100111010111010000
            000010000010010011100010000010000
            000011111110101010101011111110000
            000000000000010100000000000000000
            000011110010100000101100111010000
            000011001001110000101001000100000
            000011101010000001101000100000000
            000010001001000111011100011100000
            000010100011110100010111111000000
            000000110101011101111011100110000
            000001101110100010100001110100000
            000010111101011011000100100000000
            000000101111110100001111101100000
            000000000000101011011000100010000
            000011111110000110001010110110000
            000010000010010010011000110100000
            000010111010010111001111100100000
            000010111010100011100111110110000
            000010111010101111011010110100000
            000010000010110000000101011000000
            000011111110101010110011001110000
            000000000000000000000000000000000
            000000000000000000000000000000000
            000000000000000000000000000000000
            000000000000000000000000000000000
        """
    }
}
