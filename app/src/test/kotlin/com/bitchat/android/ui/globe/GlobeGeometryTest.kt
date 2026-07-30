package com.bitchat.android.ui.globe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlobeGeometryTest {

    private val square = listOf(
        DiscPt(-0.5f, -0.5f, front = true),
        DiscPt(0.5f, -0.5f, front = true),
        DiscPt(0.5f, 0.5f, front = true),
        DiscPt(-0.5f, 0.5f, front = true)
    )

    @Test
    fun closedFullyVisibleRing_connectsLastPointToFirst() {
        val run = buildFrontRuns(square, closed = true).single()

        assertEquals(square.size + 1, run.size)
        assertEquals(run.first(), run.last())
    }

    @Test
    fun openFullyVisibleLine_doesNotConnectLastPointToFirst() {
        val run = buildFrontRuns(square, closed = false).single()

        assertEquals(square.size, run.size)
        assertNotEquals(run.first(), run.last())
    }
}
