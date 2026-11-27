package com.bitchat.android.onboarding

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.AppPhase
import com.bitchat.android.MainActivity
import com.bitchat.android.navigation.OnboardedApp
import com.bitchat.android.navigation.OnboardingRoute
import com.bitchat.android.navigation.PrivateChatRoute
import com.bitchat.android.testhook.ReadinessOverride
import com.bitchat.android.ui.NotificationManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A notification opened while onboarding covers the chat, and onboarding finished while the
 * Activity was stopped (the user still in system settings): init reaches READY before the chat
 * is the root on its own clock, and the chat the notification named must still open once the
 * Activity is back. Measured on API 37: composition re-roots to chat while the Activity is stopped.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBehindOnboardingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun bluetoothOff() {
        OnboardedApp.prepare()
        ReadinessOverride.snapshot = ready().copy(bluetooth = BluetoothStatus.DISABLED)
    }

    @After
    fun clear() = ReadinessOverride.clear()

    @Test
    fun theNotifiedChatOpensWhenOnboardingFinishedWhileStopped() {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, true)
            .putExtra(NotificationManager.EXTRA_PEER_ID, PEER)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            await("onboarding") { scenario.read { it.navigator.backStack.toList() } == listOf(OnboardingRoute) }

            scenario.moveToState(Lifecycle.State.CREATED)
            ReadinessOverride.snapshot = ready()
            ReadinessOverride.emit(SystemEvent.BluetoothOn)
            await("init finished while stopped") { scenario.read { it.startupGate.phase.value } == AppPhase.READY }

            scenario.moveToState(Lifecycle.State.RESUMED)
            await("the notified chat") { scenario.read { it.navigator.backStack.lastOrNull() } == PrivateChatRoute(PEER) }
        }
    }

    private fun <T> ActivityScenario<MainActivity>.read(block: (MainActivity) -> T): T {
        var value: T? = null
        onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    private companion object {
        const val PEER = "0123456789abcdef"
    }
}
