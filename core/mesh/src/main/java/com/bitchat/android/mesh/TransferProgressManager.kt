package com.bitchat.android.mesh

import com.bitchat.android.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

data class TransferProgressEvent(
    val transferId: String,
    val sent: Int,
    val total: Int,
    val completed: Boolean,
    val failed: Boolean = false
)

@Singleton
class TransferProgressManager @Inject constructor(
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val _events = MutableSharedFlow<TransferProgressEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<TransferProgressEvent> = _events

    fun start(id: String, total: Int) { emit(id, 0, total, false) }
    fun progress(id: String, sent: Int, total: Int) { emit(id, sent, total, sent >= total) }
    fun complete(id: String, total: Int) { emit(id, total, total, true) }
    fun fail(id: String) { emit(id, 0, 0, done = true, failed = true) }

    private fun emit(id: String, sent: Int, total: Int, done: Boolean, failed: Boolean = false) {
        scope.launch { _events.emit(TransferProgressEvent(id, sent, total, done, failed)) }
    }
}
