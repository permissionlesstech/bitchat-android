package com.bitchat.android.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back dispatch on a real device.
 *
 * These exist because Back dispatch has no unit-test cover and cannot get any:
 * enabling `unitTests.isIncludeAndroidResources` drops collection from 622 tests
 * to 506 and fails 24 with "failed to configure", because targetSdk 37 exceeds
 * Robolectric's maximum.
 *
 * They drive the real MainActivity and replace no binding, which is what lets
 * them skip HiltTestApplication and a custom runner entirely.
 */
@RunWith(AndroidJUnit4::class)
class BackDispatchTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    /**
     * The saved-state round trip, which has no other cover.
     *
     * Recreating the Activity runs the rememberSaveable saver in MainActivity:
     * it encodes the back stack with NavKeySerializer into a Bundle and decodes
     * it again. A route key that is not @Serializable fails here and nowhere
     * else, because nothing else forces the encoder to run.
     *
     * "Don't keep activities" cannot stand in for this: the mesh foreground
     * service keeps the Activity alive, so the setting never destroys it.
     */
    @Test
    fun theBackStackSurvivesActivityRecreation() {
        rule.awaitChat()

        rule.activityRule.scenario.recreate()

        rule.awaitChat()
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun backAtTheRootDestinationLeavesTheApp() {
        rule.awaitChat()

        rule.pressBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.activity.isFinishing }
        assertTrue(rule.activity.isFinishing)
    }

    @Test
    fun backFromAboutReturnsToChatInsteadOfLeaving() {
        rule.awaitChat()
        val openAbout = rule.openAboutLabel()
        rule.onNodeWithContentDescription(openAbout).assertIsDisplayed()

        rule.openAbout()
        // About is a full-screen destination, so the header it was opened from
        // is gone rather than sitting behind a sheet.
        rule.onNodeWithContentDescription(openAbout).assertDoesNotExist()

        rule.pressBack()

        assertFalse(rule.activity.isFinishing)
        rule.onNodeWithContentDescription(openAbout).assertIsDisplayed()
    }

    /**
     * The half of saved-state restore that only a pushed destination can show.
     *
     * With just the root on the stack, restoring and re-seeding are
     * indistinguishable. Pushing About first makes them differ: a stack that
     * failed to restore falls back to chat.
     */
    @Test
    fun aPushedDestinationSurvivesActivityRecreation() {
        rule.awaitChat()
        rule.openAbout()

        rule.activityRule.scenario.recreate()

        rule.awaitAbout()
        rule.onNodeWithContentDescription(rule.openAboutLabel()).assertDoesNotExist()
        assertFalse(rule.activity.isFinishing)

        // Leave the stack at its root. The stack is saved and restored, which is
        // what this test asserts, so a destination left pushed here is restored
        // into the next test's Activity and starts it on About.
        rule.pressBack()
    }

    /**
     * Two quick Back presses, the second while the first screen is still
     * sliding out, never leave the app. Each may pop a screen, so either About
     * or chat may end on top; what must not happen is the second press
     * reaching the Activity and finishing it.
     */
    @Test
    fun twoQuickBackPressesNeverLeaveTheApp() {
        rule.awaitChat()
        rule.navigateTo(AboutRoute)
        rule.navigateTo(DebugSettingsRoute)
        rule.waitUntil(5_000) { rule.topOfStack() == DebugSettingsRoute }
        rule.waitForIdle()

        rule.mainClock.autoAdvance = false
        rule.pressSystemBack()
        rule.mainClock.advanceTimeBy(40)
        rule.pressSystemBack()
        rule.mainClock.autoAdvance = true

        rule.waitUntil(5_000) { rule.topOfStack() == ChatRoute || rule.topOfStack() == AboutRoute }
        assertFalse(rule.activity.isFinishing)
    }
}
