package com.bitchat.android.testhook

import android.app.Activity
import com.bitchat.android.ui.ChatSessionMesh
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MessageSender
import com.bitchat.android.ui.PanicClear
import com.bitchat.android.ui.PrivateChatSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.components.ActivityComponent

/**
 * The chat session's objects, for device tests that drive the session
 * directly rather than through a screen. Debug builds only.
 *
 * The chat screen's ViewModel belongs to its navigation entry, so a test
 * cannot fetch it from the Activity; these are the retained instances every
 * screen shares.
 */
@EntryPoint
@InstallIn(ActivityComponent::class)
interface ChatSessionTestAccess {
    fun chatState(): ChatState
    fun messageSender(): MessageSender
    fun panicClear(): PanicClear
    fun privateChatSession(): PrivateChatSession
    fun sessionMesh(): ChatSessionMesh

    companion object {
        fun of(activity: Activity): ChatSessionTestAccess =
            EntryPointAccessors.fromActivity(activity, ChatSessionTestAccess::class.java)
    }
}
