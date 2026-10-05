package com.bitchat.android.nostr

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GeohashMessageHandlerStartupRobolectricTest {
    @Before
    @After
    fun resetProcessPreferences() {
        PoWPreferenceManager.resetToDefaults()
        // Robolectric reuses this Kotlin singleton between test methods. Model a new process.
        ReflectionHelpers.setStaticField(PoWPreferenceManager::class.java, "isInitialized", false)
    }

    @Test
    fun `saved filter rejects an unmined message before activity initialization`() {
        checkStartupDelivery(enabled = true, expectedMessages = 0)
    }

    @Test
    fun `disabled saved filter accepts an unmined message`() {
        checkStartupDelivery(enabled = false, expectedMessages = 1)
    }

    private fun checkStartupDelivery(enabled: Boolean, expectedMessages: Int) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("pow_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pow_enabled", enabled)
            .putInt("pow_difficulty", 12)
            .commit()

        val dispatcher = UnconfinedTestDispatcher()
        val scope = TestScope(dispatcher)
        val state = ChatState(scope = scope)
        val dataManager = DataManager(application)
        var deliveredMessages = 0
        val handler = GeohashMessageHandler(
            application = application,
            repo = GeohashRepository(application, state, dataManager),
            scope = scope,
            dataManager = dataManager,
            addChannelMessage = { _, _ -> deliveredMessages++ },
            signatureVerificationDispatcher = dispatcher
        )
        // Synthetic channel and key. No location services or relay connections are used.
        val geohash = "zzzzzz"
        val identity = NostrIdentity.generate()
        val event = NostrEvent(
            pubkey = identity.publicKeyHex,
            createdAt = 1_700_000_000,
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = listOf(listOf("g", geohash)),
            content = "startup test message"
        ).sign(identity.privateKeyHex)

        handler.onEvent(event, geohash)

        assertEquals(expectedMessages, deliveredMessages)
    }
}
