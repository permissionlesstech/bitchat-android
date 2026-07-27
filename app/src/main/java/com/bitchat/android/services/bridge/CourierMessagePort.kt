package com.bitchat.android.services.bridge

import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local delivery signal for courier-opened private messages.
 *
 * AppStateStore remains the durable in-process timeline source. This signal
 * lets the active UI run the same unread, haptic, and notification path used
 * by live mesh private messages without retaining an Activity or ViewModel.
 */
object CourierMessagePort {
    private val _messages = MutableSharedFlow<BitchatMessage>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<BitchatMessage> = _messages.asSharedFlow()

    internal fun deliver(message: BitchatMessage) {
        _messages.tryEmit(message)
    }
}
