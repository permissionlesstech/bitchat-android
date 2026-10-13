package com.bitchat.android.navigation

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A destination shown as a sheet over chat.
 *
 * The chat user sheet stands in for every sheet destination; what is under
 * test is the sheet scene, not its content. It is opened through the navigator
 * rather than a message long-press.
 */
@RunWith(AndroidJUnit4::class)
class SheetDestinationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private val sheetRoute = ChatUserRoute(nickname = "someone", messageId = null)

    @Test
    fun aSheetOpensOverChatAndBackClosesIt() {
        rule.awaitChat()

        rule.navigateTo(sheetRoute)
        rule.awaitSheet()
        // Chat stays composed beneath the sheet rather than being replaced.
        assertTrue(chatIsComposed())

        rule.pressSystemBack()

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitUntil(timeoutMillis = 5_000) { !sheetIsShowing() }
        assertFalse(rule.activity.isFinishing)
    }

    /**
     * The sheet's window keeps taking Back while it animates out, and popping
     * with nothing left to pop finishes the app.
     */
    @Test
    fun twoQuickBackPressesOnASheetCloseOnlyTheSheet() {
        rule.awaitChat()
        rule.navigateTo(sheetRoute)
        rule.awaitSheet()

        rule.pressSystemBack(times = 2)

        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
        rule.waitUntil(timeoutMillis = 5_000) { !sheetIsShowing() }
        assertFalse(rule.activity.isFinishing)
        assertTrue(chatIsComposed())
    }

    /** A pop that does not start in the sheet, such as a reset, still closes it. */
    @Test
    fun aSheetPoppedFromOutsideCloses() {
        rule.awaitChat()
        rule.navigateTo(sheetRoute)
        rule.awaitSheet()

        rule.runOnUiThread { rule.activity.navigator.goBack() }

        rule.waitUntil(timeoutMillis = 5_000) { !sheetIsShowing() }
        assertEquals(ChatRoute, rule.topOfStack())
        assertFalse(rule.activity.isFinishing)
    }

    /**
     * A sheet popped from code, such as by its Cancel button, stays on screen
     * while it slides down, and its window still takes Back. That press must
     * not pop again: only chat is left, so it would finish the app.
     */
    @Test
    fun backWhileASheetSlidesAwayStaysInTheApp() {
        rule.awaitChat()
        rule.navigateTo(sheetRoute)
        rule.awaitSheet()

        rule.runOnUiThread { rule.activity.navigator.goBack() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

        rule.waitUntil(timeoutMillis = 5_000) { !sheetIsShowing() }
        assertFalse(rule.activity.isFinishing)
        assertTrue(chatIsComposed())
    }

    @Test
    fun aSheetSurvivesActivityRecreation() {
        rule.awaitChat()
        rule.navigateTo(sheetRoute)
        rule.awaitSheet()

        rule.activityRule.scenario.recreate()

        rule.awaitSheet()
        assertEquals(sheetRoute, rule.topOfStack())

        rule.pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
    }

    /**
     * A key with arguments goes through the saved-state encoder as a data
     * class rather than an object, which only restoring it exercises.
     */
    @Test
    fun aSheetWithArgumentsSurvivesActivityRecreation() {
        rule.awaitChat()
        val route = ChatUserRoute(nickname = "someone", messageId = null)
        rule.navigateTo(route)
        rule.awaitSheet()

        rule.activityRule.scenario.recreate()

        rule.awaitSheet()
        assertEquals(route, rule.topOfStack())

        rule.pressSystemBack()
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == ChatRoute }
    }

    private fun sheetIsShowing(): Boolean =
        rule.onAllNodes(isSheet).fetchSemanticsNodes().isNotEmpty()

    private fun chatIsComposed(): Boolean =
        rule.onAllNodesWithContentDescription(rule.openAboutLabel())
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
}
