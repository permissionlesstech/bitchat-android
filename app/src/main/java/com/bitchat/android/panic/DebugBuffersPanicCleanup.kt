package com.bitchat.android.panic

import com.bitchat.android.ui.debug.DebugSettingsManager
import javax.inject.Inject

/** Empties the debug screen's buffers, which hold peer IDs and nicknames for the life of the process. */
class DebugBuffersPanicCleanup @Inject constructor(
    private val debug: DebugSettingsManager,
) : PanicCleanup {
    override val name: String = "debug buffers"

    override fun run(): Boolean {
        debug.clearBuffers()
        return true
    }
}
