package com.bitchat.android.location

import android.util.Log
import com.bitchat.android.nostr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

class NigeriaLocationNotesManager private constructor() {
    companion object {
        private const val TAG = "NigeriaLocationNotesManager"
        @Volatile private var INSTANCE: NigeriaLocationNotesManager? = null
        fun getInstance(): NigeriaLocationNotesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NigeriaLocationNotesManager().also { INSTANCE = it }
            }
        }
    }

    private val _notes = MutableStateFlow<List<LocationNotesManager.Note>>(emptyList())
    val notes = _notes.asStateFlow()

    private val _scope = MutableStateFlow("ward")
    val scope = _scope.asStateFlow()

    private var subscriptionId: String? = null
    private val noteIDs = mutableSetOf<String>()
    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + scopeJob)

    fun setScope(newScope: String, location: NigeriaLocation, nostrRelayManager: NostrRelayManager, identity: NostrIdentity) {
        _scope.value = newScope
        refreshSubscription(location, nostrRelayManager)
    }

    private fun refreshSubscription(location: NigeriaLocation, nostrRelayManager: NostrRelayManager) {
        subscriptionId?.let { nostrRelayManager.unsubscribe(it) }
        noteIDs.clear()
        _notes.value = emptyList()

        val filter = when (_scope.value) {
            "state" -> NostrFilter.nigeriaNotes(state = location.state)
            "region" -> NostrFilter.nigeriaNotes(state = location.state, region = location.region)
            "lga" -> NostrFilter.nigeriaNotes(state = location.state, lga = location.lga)
            "ward" -> NostrFilter.nigeriaNotes(state = location.state, lga = location.lga, ward = location.ward)
            "constituency" -> NostrFilter.nigeriaNotes(state = location.state, constituency = location.constituency)
            else -> NostrFilter.nigeriaNotes(state = location.state, lga = location.lga, ward = location.ward)
        }

        subscriptionId = nostrRelayManager.subscribe(
            filter = filter,
            id = "ng-notes-${_scope.value}",
            handler = { event -> handleEvent(event) }
        )
    }

    private fun handleEvent(event: NostrEvent) {
        if (noteIDs.contains(event.id)) return
        noteIDs.add(event.id)

        val nickname = event.tags.find { it.size >= 2 && it[0] == "n" }?.get(1)
        val note = LocationNotesManager.Note(
            id = event.id,
            pubkey = event.pubkey,
            content = event.content,
            createdAt = event.createdAt,
            nickname = nickname
        )

        _notes.value = (_notes.value + note).sortedByDescending { it.createdAt }
    }

    fun sendNote(content: String, location: NigeriaLocation, identity: NostrIdentity, nickname: String?, nostrRelayManager: NostrRelayManager) {
        coroutineScope.launch {
            val event = NostrProtocol.createNigeriaScopedNote(
                content = content,
                location = location,
                scopeLevel = _scope.value,
                senderIdentity = identity,
                nickname = nickname
            )

            nostrRelayManager.sendEvent(event)
            handleEvent(event)
        }
    }
}
