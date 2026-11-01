package com.bitchat.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ObserveAsEventsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eventsSentBeforeStart_areDeliveredOnceAfterStart() {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnUiThread {
            registry = LifecycleRegistry.createUnsafe(owner)
            registry.currentState = Lifecycle.State.CREATED
        }
        val channel = Channel<String>(Channel.BUFFERED)
        val received = mutableListOf<String>()
        channel.trySend("first")
        channel.trySend("second")

        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ObserveAsEvents(channel.receiveAsFlow()) { received += it }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), received)

        compose.runOnUiThread { registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        assertEquals(listOf("first", "second"), received)

        compose.runOnUiThread { registry.currentState = Lifecycle.State.CREATED }
        compose.runOnUiThread { registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        assertEquals(listOf("first", "second"), received)
    }
}
