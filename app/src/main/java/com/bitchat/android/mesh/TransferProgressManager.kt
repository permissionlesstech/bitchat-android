package com.bitchat.android.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

data class TransferProgressEvent(
    val transferId: String,
    val sent: Int,
    val total: Int,
    val completed: Boolean
)

object TransferProgressManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<TransferProgressEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<TransferProgressEvent> = _events

    fun start(id: String, total: Int) { emit(id, 0, total, false) }
    fun progress(id: String, sent: Int, total: Int) { emit(id, sent, total, sent >= total) }
    fun complete(id: String, total: Int) { emit(id, total, total, true) }

    private fun emit(id: String, sent: Int, total: Int, done: Boolean) {
        scope.launch { _events.emit(TransferProgressEvent(id, sent, total, done)) }
    }
}

