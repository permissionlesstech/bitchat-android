package com.bitchat.android.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.testhook.ChatSessionTestAccess
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * There is no encrypted channel format, so what the composer sends to a password-protected
 * channel has to be refused. The transport would carry it as plaintext.
 */
@RunWith(AndroidJUnit4::class)
class PasswordProtectedChannelSendTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val CHANNEL = "#synthetic-channel"

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private val sent = CopyOnWriteArrayList<String>()

    private fun session() = ChatSessionTestAccess.of(rule.activity)

    private fun enterChannel(passwordProtected: Boolean) {
        rule.awaitChat()
        rule.runOnUiThread {
            val mesh = session().sessionMesh().unified
            session().sessionMesh().replace(object : MeshService by mesh {
                override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
                    sent += content
                }
            })
            val state = session().chatState()
            state.setJoinedChannels(setOf(CHANNEL))
            state.setPasswordProtectedChannels(if (passwordProtected) setOf(CHANNEL) else emptySet())
            state.setCurrentChannel(CHANNEL)
        }
    }

    private fun send(content: String): Boolean {
        var accepted: Boolean? = null
        rule.runOnUiThread { session().messageSender().send(content) { accepted = it } }
        rule.waitUntil(timeoutMillis = 5_000) { accepted != null }
        return accepted == true
    }

    private fun timeline() = session().chatState().getChannelMessagesValue()[CHANNEL].orEmpty()

    @Test
    fun aMessageToAProtectedChannelIsRefusedVisiblyAndNotSent() {
        enterChannel(passwordProtected = true)

        assertFalse("the composer must keep the text", send("synthetic refused message"))

        assertTrue("nothing may reach the mesh: $sent", sent.isEmpty())
        assertTrue(timeline().none { it.content == "synthetic refused message" })
        assertTrue(timeline().last().content.startsWith("not sent:"))
    }

    @Test
    fun anActionCommandToAProtectedChannelIsRefusedAndNotSent() {
        enterChannel(passwordProtected = true)

        send("/hug synthetic-peer")

        assertTrue("nothing may reach the mesh: $sent", sent.isEmpty())
        assertTrue(timeline().last().content.startsWith("not sent:"))
    }

    @Test
    fun aMessageToAnOpenChannelIsStillSent() {
        enterChannel(passwordProtected = false)

        assertTrue(send("synthetic open message"))

        assertEquals(listOf("synthetic open message"), sent.toList())
    }
}
