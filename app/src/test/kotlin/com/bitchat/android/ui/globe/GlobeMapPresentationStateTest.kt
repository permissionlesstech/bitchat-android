package com.bitchat.android.ui.globe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeMapPresentationStateTest {
    @Test
    fun partialUpdateAfterCompleteLoadKeepsCompleteMapThroughCancellation() {
        val completeData = GlobeMapData(detailZoom = 6)
        val priorityOnlyData = GlobeMapData(detailZoom = 7)
        var state = GlobeMapPresentationState().showComplete(
            result(completeData)
        )

        state = state.startLoading().showPartial(result(priorityOnlyData))

        assertSame(completeData, state.uiState.data)
        assertTrue(state.uiState.isLoading)

        state = state.cancelLoading()

        assertSame(completeData, state.uiState.data)
        assertFalse(state.uiState.isLoading)
    }

    @Test
    fun failedReplacementAfterCompleteLoadKeepsCompleteMap() {
        val completeData = GlobeMapData(detailZoom = 6)
        val incompleteData = GlobeMapData(detailZoom = 7)
        val state = GlobeMapPresentationState()
            .showComplete(result(completeData))
            .showComplete(result(incompleteData, failedTileCount = 1))

        assertSame(completeData, state.uiState.data)
        assertTrue(state.uiState.hasError)
        assertSame(completeData, state.lastCompleteData)
    }

    @Test
    fun firstLoadCanStillDisplayPriorityTilesProgressively() {
        val priorityOnlyData = GlobeMapData(detailZoom = 4)
        val state = GlobeMapPresentationState()
            .showPartial(result(priorityOnlyData))

        assertSame(priorityOnlyData, state.uiState.data)
        assertTrue(state.uiState.isLoading)
    }

    private fun result(
        data: GlobeMapData,
        failedTileCount: Int = 0
    ): GlobeMapLoadResult = GlobeMapLoadResult(
        data = data,
        requestedTileCount = 5,
        failedTileCount = failedTileCount
    )
}
