package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrBackgroundEventProcessorTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        AppStateStore.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppStateStore.clear()
    }

    @Test
    fun `cold start processes more events than the removed handoff queue capacity`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val processor = NostrBackgroundEventProcessor(application, scope)
        val identity = NostrIdentity.generate()
        val expectedIds = mutableSetOf<String>()

        repeat(300) { index ->
            val event = NostrEvent(
                pubkey = identity.publicKeyHex,
                createdAt = index + 1,
                kind = NostrKind.EPHEMERAL_EVENT,
                tags = listOf(listOf("g", "u4pruy")),
                content = "message-$index"
            ).sign(identity.privateKeyHex)
            expectedIds += event.id

            processor.onGeohashMessage(
                event = event,
                geohash = "u4pruy"
            )
        }

        withTimeout(5_000) {
            while (AppStateStore.channelMessages.value["geo:u4pruy"].orEmpty().size < 300) {
                kotlinx.coroutines.yield()
            }
        }

        assertEquals(
            expectedIds,
            AppStateStore.channelMessages.value["geo:u4pruy"].orEmpty().map { it.id }.toSet()
        )
    }
}
