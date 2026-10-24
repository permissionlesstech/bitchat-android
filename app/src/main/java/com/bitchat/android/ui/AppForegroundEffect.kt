package com.bitchat.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.nostr.NearbyNotesController

/**
 * Tells live voice and nearby notes whether the app is in the foreground.
 *
 * Composed by the Activity, above navigation, because foreground is a property
 * of the process, not of any screen. It used to live in ChatScreen, which was
 * fine while everything else was a sheet over chat. A destination replaces chat
 * instead, and chat leaving composition reported the app as backgrounded: live
 * voice stopped as soon as About opened, and would stop on opening any other
 * full-screen destination, private chat included.
 */
@Composable
fun AppForegroundEffect() {
    val context = LocalContext.current
    val nearbyNotesController = remember { NearbyNotesController.shared }
    val liveVoiceManager = remember(context) { LiveVoiceManager.getInstance(context) }
    val processLifecycleOwner = remember { ProcessLifecycleOwner.get() }

    DisposableEffect(processLifecycleOwner, nearbyNotesController, liveVoiceManager) {
        val lifecycle = processLifecycleOwner.lifecycle
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                nearbyNotesController.updateAppForeground(true)
                liveVoiceManager.setAppForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                nearbyNotesController.updateAppForeground(false)
                liveVoiceManager.setAppForeground(false)
            }
        }

        lifecycle.addObserver(observer)
        nearbyNotesController.updateAppForeground(
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )

        onDispose {
            lifecycle.removeObserver(observer)
            nearbyNotesController.updateAppForeground(false)
            liveVoiceManager.setAppForeground(false)
        }
    }
}
