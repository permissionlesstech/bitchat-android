package com.bitchat.android.navigation

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An unsent message outlives chat leaving composition.
 *
 * A sheet opened over chat left it composed, so the composer kept its text for
 * free. A destination replaces chat instead, and every sheet that becomes one
 * takes chat out of composition while it is open.
 */
@RunWith(AndroidJUnit4::class)
class ComposerDraftTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val DRAFT = "unsent draft"

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    @Test
    fun anUnsentMessageSurvivesARoundTripThroughAbout() {
        rule.awaitChat()
        typeDraft()

        rule.openAbout()
        rule.pressBack()

        rule.awaitChat()
        composerHolding(DRAFT).assertExists()
    }

    @Test
    fun anUnsentMessageSurvivesActivityRecreation() {
        rule.awaitChat()
        typeDraft()

        rule.activityRule.scenario.recreate()

        rule.awaitChat()
        composerHolding(DRAFT).assertExists()
    }

    /**
     * The header's nickname is editable too, so the composer is told apart as
     * the text field that starts empty.
     */
    private fun typeDraft() {
        composerHolding("").performTextInput(DRAFT)
        composerHolding(DRAFT).assertExists()
    }

    private fun composerHolding(text: String): SemanticsNodeInteraction =
        rule.onNode(hasSetTextAction() and hasText(text))
}
