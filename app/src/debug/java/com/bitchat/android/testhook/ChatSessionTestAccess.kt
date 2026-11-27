package com.bitchat.android.testhook

import android.app.Activity
import com.bitchat.android.di.CurrentSession
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.ui.ChatSessionHolder
import com.bitchat.android.ui.ChatSessionMesh
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.GeohashSession
import com.bitchat.android.ui.MessageSender
import com.bitchat.android.ui.NotificationManager
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
 * cannot fetch it from the Activity; these are the instances every screen of
 * the current session shares.
 */
@EntryPoint
@InstallIn(ActivityComponent::class)
interface ChatSessionTestAccess {
    fun panicClear(): PanicClear
    fun chatSessionHolder(): ChatSessionHolder

    @CurrentSession
    fun chatState(): ChatState

    @CurrentSession
    fun messageSender(): MessageSender

    @CurrentSession
    fun privateChatSession(): PrivateChatSession

    @CurrentSession
    fun sessionMesh(): ChatSessionMesh

    @CurrentSession
    fun geohashSession(): GeohashSession

    @CurrentSession
    fun notificationManager(): NotificationManager

    @CurrentSession
    fun secureIdentityStateManager(): SecureIdentityStateManager

    companion object {
        fun of(activity: Activity): ChatSessionTestAccess =
            EntryPointAccessors.fromActivity(activity, ChatSessionTestAccess::class.java)
    }
}
