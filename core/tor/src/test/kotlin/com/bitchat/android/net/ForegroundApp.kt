package com.bitchat.android.net

import kotlinx.coroutines.flow.flowOf

/** A [TorAppBackground] for tests that never leaves the foreground. */
internal object ForegroundApp : TorAppBackground {
    override val inBackground = flowOf(false)
}
