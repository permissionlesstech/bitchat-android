package com.bitchat.android.navigation

import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.AppPhase
import com.bitchat.android.R
import com.bitchat.android.onboarding.SystemDeviceReadiness
import com.bitchat.android.wifiaware.WifiAwareSupport

internal typealias MainActivityRule =
    AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/**
 * Puts a fresh install in the state of a user who finished onboarding, so the
 * Activity opens on chat.
 *
 * Call it from @BeforeClass. The compose rule launches the Activity before
 * @Before runs, and onboarding picks the root during that first composition.
 *
 * Granting permissions is not enough on its own. Until onboarding is marked
 * complete, the app treats every launch as a first launch and shows the
 * permission explanation before it looks at a single grant. The grants come
 * from SystemDeviceReadiness itself, so a permission the app starts requiring
 * is granted here without editing this file.
 *
 * Bluetooth and location services are the device's own settings and are left
 * alone. A device with either switched off still opens on onboarding.
 */
internal object OnboardedApp {

    fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val readiness = SystemDeviceReadiness(context) { WifiAwareSupport.isSupported(context) }
        val grants = readiness.requiredPermissions() +
            readiness.optionalPermissions() +
            listOfNotNull(readiness.backgroundLocationPermission())
        grants.forEach { permission -> shell("pm grant ${context.packageName} $permission") }
        shell("dumpsys deviceidle whitelist +${context.packageName}")
        readiness.markOnboardingComplete()
    }

    /**
     * Runs a shell command and waits for it to finish.
     *
     * The output has to be read, not just closed. executeShellCommand hands
     * back a pipe and the command runs while it is open, so closing it
     * straight away can tear the command down before it does anything, and
     * it reports no error when that happens.
     */
    private fun shell(command: String) {
        val pipe = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }
}

/**
 * About is opened from the brand button, which already carries a localised
 * accessibility label. Resolved from resources rather than hardcoded so the
 * tests do not depend on the device's language.
 */
internal fun MainActivityRule.openAboutLabel(): String =
    activity.getString(R.string.cd_open_about)

/**
 * Waits for chat, with the app finished starting.
 *
 * Chat is the root from the first frame, so its header on screen does not mean
 * start-up is over. Initialisation runs on the app gate's retained scope, so
 * recreating the Activity mid-start-up no longer fails it; waiting for READY
 * still keeps a test from acting on chat before the mesh is attached and the
 * launch intent handled.
 */
internal fun MainActivityRule.awaitChat() {
    val openAbout = openAboutLabel()
    waitUntil(timeoutMillis = 15_000) {
        appPhase() == AppPhase.READY &&
            onAllNodesWithContentDescription(openAbout).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
}

/**
 * Opens About from the brand button.
 *
 * The button holds a single tap back for a moment in case it becomes the triple
 * tap that wipes the app, and it waits with a coroutine delay that waitForIdle
 * does not see. So this waits for About itself rather than for idleness.
 */
internal fun MainActivityRule.openAbout() {
    onNodeWithContentDescription(openAboutLabel()).performClick()
    awaitAbout()
}

internal fun MainActivityRule.awaitAbout() {
    val infoTab = activity.getString(R.string.about_tab_info)
    waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithText(infoTab, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
}

private fun MainActivityRule.appPhase(): AppPhase {
    var phase = AppPhase.CHECKING
    runOnUiThread { phase = activity.startupGate.phase.value }
    return phase
}

internal fun MainActivityRule.pressBack() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

/**
 * Presses Back the way the device does, into whichever window has focus.
 *
 * pressBack calls the Activity's dispatcher, which a sheet never sees: a sheet
 * is its own window and takes a real Back press before the Activity does.
 */
internal fun MainActivityRule.pressSystemBack(times: Int = 1) {
    // No idling between presses: a second press that waits for the first to
    // settle is two separate presses, not a quick double one.
    repeat(times) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }
    waitForIdle()
}

internal fun MainActivityRule.navigateTo(route: NavKey) {
    runOnUiThread { activity.navigator.goTo(route) }
    waitForIdle()
}

/** Waits for the peer list's own title, not just for its route on the stack. */
internal fun MainActivityRule.awaitPeerList() {
    val title = activity.getString(R.string.your_network)
    waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun MainActivityRule.topOfStack(): NavKey? = backStack().lastOrNull()

internal fun MainActivityRule.backStack(): List<NavKey> {
    var stack: List<NavKey> = emptyList()
    runOnUiThread { stack = activity.navigator.backStack.toList() }
    return stack
}

/** A bottom sheet announces itself to accessibility with a pane title. */
internal val isSheet = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)

internal fun MainActivityRule.awaitSheet() {
    waitUntil(timeoutMillis = 5_000) { onAllNodes(isSheet).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}
