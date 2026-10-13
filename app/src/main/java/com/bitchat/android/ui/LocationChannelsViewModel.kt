package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The chat session's side of the location channels screen: how many people
 * each channel has, and sampling the channels on screen to count them.
 *
 * Location, bookmarks and notes are process-wide managers the screen still
 * reads itself.
 */
@HiltViewModel
class LocationChannelsViewModel @Inject constructor(
    state: ChatState,
    private val mesh: ChatSessionMesh,
    private val geohashSession: GeohashSession,
) : ViewModel() {

    /** Participants seen per geohash while sampling. */
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = geohashSession.geohashParticipantCounts

    /** People on the mesh, not counting this device. */
    val meshPeopleCount: StateFlow<Int> = state.connectedPeers
        .map(::countOthers)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = countOthers(state.connectedPeers.value),
        )

    /**
     * Samples the channels the screen shows. Called again whenever they
     * change; sampling stops when the screen leaves the stack, not when it is
     * recreated.
     */
    fun sampleGeohashes(
        liveLocationGeohashes: Collection<String>,
        userSelectedGeohashes: Collection<String>,
    ) {
        geohashSession.beginGeohashSampling(
            liveLocationGeohashes = liveLocationGeohashes,
            userSelectedGeohashes = userSelectedGeohashes,
        )
    }

    override fun onCleared() {
        geohashSession.endGeohashSampling()
    }

    private fun countOthers(peers: List<String>): Int {
        val myPeerID = mesh.unified.myPeerID
        return peers.count { it != myPeerID }
    }
}
