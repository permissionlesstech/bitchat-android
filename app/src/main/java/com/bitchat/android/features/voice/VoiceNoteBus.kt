package com.bitchat.android.features.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

data class VoiceNoteEvent(
    val fromPeerID: String,
    val filePath: String,
    val mimeType: String
)

object VoiceNoteBus {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<VoiceNoteEvent>(replay = 0, extraBufferCapacity = 16)
    val events: SharedFlow<VoiceNoteEvent> = _events

    fun emit(event: VoiceNoteEvent) {
        scope.launch { _events.emit(event) }
    }
}

