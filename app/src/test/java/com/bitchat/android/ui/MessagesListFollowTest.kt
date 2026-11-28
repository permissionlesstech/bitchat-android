package com.bitchat.android.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import java.util.Date
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The message list follows new arrivals while the reader is at the latest message.
 *
 * Public, channel and geohash timelines are capped, so once one is full every arrival evicts the
 * oldest message and the list size stays constant. Following must not depend on the size changing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessagesListFollowTest {

    private val compose = createComposeRule()

    // Unit tests run without merged Android resources, so Robolectric does not know the host
    // activity the compose rule launches. Register it before the rule starts.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(object : ExternalResource() {
            override fun before() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
            }
        })
        .around(compose)

    private val meshService = mock<MeshService> { on { myPeerID } doReturn "0000000000000000" }
    private var messages by mutableStateOf(List(TimelineSize) { msg(it) })
    private var scrolledUp: Boolean? = null

    private fun setContent() {
        compose.setContent {
            MessagesList(
                messages = messages,
                currentUserNickname = "me",
                meshService = meshService,
                modifier = Modifier.size(360.dp, 640.dp),
                conversationKey = "geo:test",
                onScrolledUpChanged = { scrolledUp = it }
            )
        }
        compose.waitForIdle()
    }

    /** Mirrors the timeline retention cap: the oldest message makes room for the new one. */
    private fun arriveAtCapacity(count: Int) {
        repeat(count) {
            messages = messages.drop(1) + msg(TimelineSize + it)
            compose.waitForIdle()
        }
    }

    @Test
    fun `a full timeline keeps following arrivals that evict the oldest message`() {
        setContent()

        arriveAtCapacity(10)

        assertFalse("following stopped once the size stopped changing", scrolledUp ?: false)
        compose.onNodeWithText(body(TimelineSize + 9), substring = true).assertIsDisplayed()
    }

    @Test
    fun `a full timeline does not pull a reader back down after they scroll away`() {
        setContent()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(TimelineSize - 1)
        compose.waitForIdle()

        arriveAtCapacity(3)

        assertTrue("reader was yanked back to the latest message", scrolledUp ?: false)
    }

    private fun msg(index: Int) = BitchatMessage(
        id = "m$index",
        sender = "alice",
        content = body(index),
        timestamp = Date(index * 1_000L),
    )

    private fun body(index: Int) = "synthetic message $index"

    private companion object {
        const val TimelineSize = 40
    }
}
