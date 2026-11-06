package com.bitchat.android.features.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H264NalUnitsTest {
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33)
    private val filler = byteArrayOf(0x0C, 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte())

    @Test
    fun splitsMixedStartCodes() {
        val data = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps + byteArrayOf(0, 0, 0, 1) + idr
        val nals = H264NalUnits.split(data)
        assertEquals(3, nals.size)
        assertArrayEquals(idr, nals[2])
        assertEquals(H264NalUnits.TYPE_IDR, H264NalUnits.type(nals[2]))
    }

    @Test
    fun liftsParameterSetsAndStripsThemForMuxing() {
        val keyframe = H264NalUnits.join(listOf(sps, pps, idr))
        val (csd0, csd1) = H264NalUnits.parameterSets(keyframe)!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps, csd0)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + pps, csd1)
        assertArrayEquals(H264NalUnits.join(listOf(idr)), H264NalUnits.withoutParameterSets(keyframe))
        assertNull(H264NalUnits.parameterSets(H264NalUnits.join(listOf(idr))))
    }

    @Test
    fun dropsFillerOnly() {
        val unit = H264NalUnits.join(listOf(idr, filler))
        assertArrayEquals(H264NalUnits.join(listOf(idr)), H264NalUnits.withoutFiller(unit))
        val clean = H264NalUnits.join(listOf(idr))
        assertArrayEquals(clean, H264NalUnits.withoutFiller(clean))
    }
}
