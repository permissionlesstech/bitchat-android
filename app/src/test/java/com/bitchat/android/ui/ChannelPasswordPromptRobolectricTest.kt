package com.bitchat.android.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The password dialog is driven by the prompt state that the channel flow raises. Unit tests run
 * without merged Android resources, which Material's AlertDialog needs, so a plain dialog with the
 * same inputs stands in for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChannelPasswordPromptRobolectricTest {

    private val compose = createComposeRule()

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

    private val channel = "#synthetic-channel"
    private val showPrompt = MutableStateFlow(false)
    private val promptChannel = MutableStateFlow<String?>(null)
    private val submitted = mutableListOf<Pair<String, String>>()
    private var submitResult = false

    private fun setContent() {
        compose.setContent {
            ChannelPasswordPrompt(
                showPrompt = showPrompt,
                promptChannel = promptChannel,
                onSubmit = { target, password ->
                    submitted += target to password
                    submitResult
                },
                onDismiss = {
                    showPrompt.value = false
                    promptChannel.value = null
                },
                dialog = { show, channelName, password, onPasswordChange, onConfirm, _ ->
                    if (show && channelName != null) {
                        Column {
                            BasicText(channelName)
                            BasicTextField(password, onPasswordChange)
                            BasicText("Join", Modifier.clickable(onClick = onConfirm))
                        }
                    }
                }
            )
        }
    }

    private fun raisePrompt() {
        promptChannel.value = channel
        showPrompt.value = true
        compose.waitForIdle()
    }

    private fun dialogIsOpen() =
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `dialog stays closed until the prompt is raised`() {
        setContent()
        compose.waitForIdle()
        assertFalse(dialogIsOpen())

        raisePrompt()

        assertTrue(dialogIsOpen())
        compose.onNodeWithText(channel, substring = true).assertIsDisplayed()
    }

    @Test
    fun `an accepted password submits for the prompted channel and closes the dialog`() {
        submitResult = true
        setContent()
        raisePrompt()

        compose.onNode(hasSetTextAction()).performTextInput("synthetic-password")
        compose.onNodeWithText("Join").performClick()
        compose.waitForIdle()

        assertEquals(listOf(channel to "synthetic-password"), submitted)
        assertFalse(showPrompt.value)
        assertFalse(dialogIsOpen())
    }

    @Test
    fun `an empty password is not submitted`() {
        setContent()
        raisePrompt()

        compose.onNodeWithText("Join").performClick()
        compose.waitForIdle()

        assertTrue(submitted.isEmpty())
        assertTrue(dialogIsOpen())
    }

    @Test
    fun `a reopened prompt does not keep the previously typed password`() {
        setContent()
        raisePrompt()
        compose.onNode(hasSetTextAction()).performTextInput("synthetic-password")
        compose.waitForIdle()

        showPrompt.value = false
        compose.waitForIdle()
        raisePrompt()
        compose.onNodeWithText("Join").performClick()
        compose.waitForIdle()

        assertTrue(submitted.isEmpty())
    }
}
