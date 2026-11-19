package com.bitchat.android.onboarding

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.AppPhase
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.navigation.ChatRoute
import com.bitchat.android.navigation.MainActivityRule
import com.bitchat.android.navigation.OnboardedApp
import com.bitchat.android.navigation.OnboardingRoute
import com.bitchat.android.navigation.awaitChat
import com.bitchat.android.navigation.backStack
import com.bitchat.android.navigation.openAboutLabel
import com.bitchat.android.testhook.ReadinessOverride
import kotlinx.coroutines.launch
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * The main onboarding paths, on a device, with readiness faked.
 *
 * [ReadinessOverride] replaces everything onboarding reads from the device, so
 * none of this depends on the device's own Bluetooth, location or battery
 * settings. Radio changes are sent through the override.
 *
 * Each path is its own class because the compose rule launches the Activity
 * before @Before runs, and the gate reads readiness as it starts: the fake has
 * to be in place in @BeforeClass. No test taps a button that opens a system
 * dialog or settings screen.
 */
@RunWith(Enclosed::class)
class OnboardingDeviceTest {

    @RunWith(AndroidJUnit4::class)
    class FirstLaunch {
        @get:Rule
        val rule = createAndroidComposeRule<MainActivity>()

        companion object {
            // Chat on the back stack at any point from the Activity's creation,
            // which is before the test body runs and before the gate decides.
            @Volatile
            private var chatOnStack = false

            private val stackWatcher = object : ActivityLifecycleCallbacksAdapter() {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity !is MainActivity) return
                    activity.lifecycleScope.launch {
                        snapshotFlow { activity.navigator.backStack.toList() }
                            .collect { if (ChatRoute in it) chatOnStack = true }
                    }
                }
            }

            @JvmStatic
            @BeforeClass
            fun fakeReadiness() {
                setUp(ready().copy(firstLaunch = true))
                application().registerActivityLifecycleCallbacks(stackWatcher)
            }

            @JvmStatic
            @AfterClass
            fun clearReadiness() {
                application().unregisterActivityLifecycleCallbacks(stackWatcher)
                tearDown()
            }
        }

        @Test
        fun firstLaunch_showsPermissionExplanation() {
            rule.awaitText(R.string.grant_permissions)
            rule.waitUntil(timeoutMillis = 10_000) { rule.phase() == AppPhase.ONBOARDING }
            rule.assertOnOnboarding()
            // F8: chat, and so its header, never showed, not even while the gate checked.
            assertFalse("chat was on the back stack before the explanation", chatOnStack)
            assertEquals(0, rule.onAllNodesWithContentDescription(rule.openAboutLabel()).fetchSemanticsNodes().size)
        }
    }

    @RunWith(AndroidJUnit4::class)
    class ReturningBluetoothOff {
        @get:Rule
        val rule = createAndroidComposeRule<MainActivity>()

        companion object {
            @JvmStatic
            @BeforeClass
            fun fakeReadiness() = setUp(ready().copy(bluetooth = BluetoothStatus.DISABLED))

            @JvmStatic
            @AfterClass
            fun clearReadiness() = tearDown()
        }

        @Test
        fun returningBluetoothOff_showsBluetoothStepThenChatWhenTurnedOn() {
            rule.awaitText(R.string.enable_bluetooth)
            rule.assertOnOnboarding()

            ReadinessOverride.snapshot = ready()
            ReadinessOverride.emit(SystemEvent.BluetoothOn)

            rule.awaitChat()
        }
    }

    @RunWith(AndroidJUnit4::class)
    class ReturningLocationOff {
        @get:Rule
        val rule = createAndroidComposeRule<MainActivity>()

        companion object {
            @JvmStatic
            @BeforeClass
            fun fakeReadiness() = setUp(ready().copy(location = LocationStatus.DISABLED))

            @JvmStatic
            @AfterClass
            fun clearReadiness() = tearDown()
        }

        @Test
        fun returningLocationOff_checkAgainAfterFix_goesOn() {
            rule.awaitText(R.string.open_location_settings)
            rule.assertOnOnboarding()

            ReadinessOverride.snapshot = ready()
            rule.onNodeWithText(rule.activity.getString(R.string.check_again)).performClick()

            rule.awaitChat()
        }
    }

    @RunWith(AndroidJUnit4::class)
    class BatteryStep {
        @get:Rule
        val rule = createAndroidComposeRule<MainActivity>()

        companion object {
            @JvmStatic
            @BeforeClass
            fun fakeReadiness() {
                clearBatterySkip()
                setUp(ready().copy(batteryOptimization = BatteryOptimizationStatus.ENABLED))
            }

            @JvmStatic
            @AfterClass
            fun clearReadiness() {
                tearDown()
                clearBatterySkip()
            }

            /** Skipping is stored for good; a later class must not inherit it. */
            private fun clearBatterySkip() {
                targetContext().getSharedPreferences("bitchat_settings", Context.MODE_PRIVATE)
                    .edit().remove("battery_optimization_skipped").commit()
            }
        }

        @Test
        fun batteryStep_skip_reachesChat() {
            rule.awaitText(R.string.battery_optimization_skip)
            rule.assertOnOnboarding()

            rule.onNodeWithText(rule.activity.getString(R.string.battery_optimization_skip))
                .performClick()

            rule.awaitChat()
        }
    }

    @RunWith(AndroidJUnit4::class)
    class MissingRequiredPermission {
        @get:Rule
        val rule = createAndroidComposeRule<MainActivity>()

        companion object {
            @JvmStatic
            @BeforeClass
            fun fakeReadiness() = setUp(
                ready().copy(
                    requiredPermissionsGranted = false,
                    missingPermissions = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                )
            )

            @JvmStatic
            @AfterClass
            fun clearReadiness() = tearDown()
        }

        @Test
        fun missingRequiredPermission_showsPermissionExplanation() {
            rule.awaitText(R.string.grant_permissions)
            rule.assertOnOnboarding()
        }
    }
}

private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

private fun application(): Application = targetContext().applicationContext as Application

/** Activity callbacks that do nothing unless overridden. */
private abstract class ActivityLifecycleCallbacksAdapter : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * A returning user with everything ready: radios on, battery optimization off,
 * every permission granted and nothing left to ask. Each test changes one thing.
 */
private fun ready() = Readiness(
    bluetooth = BluetoothStatus.ENABLED,
    location = LocationStatus.ENABLED,
    batteryOptimization = BatteryOptimizationStatus.DISABLED,
    requiredPermissionsGranted = true,
    missingPermissions = emptyList(),
    unrequestedOptionalPermissions = emptyList(),
    backgroundLocation = BackgroundLocation.GRANTED,
    firstLaunch = false,
    batterySkipped = false,
    backgroundLocationSkipped = false,
    bleRequired = true,
)

/**
 * The real grants as well as the fake: once onboarding is through, the app
 * starts the mesh, which needs the permissions themselves.
 */
private fun setUp(readiness: Readiness) {
    OnboardedApp.prepare()
    ReadinessOverride.snapshot = readiness
}

private fun tearDown() {
    ReadinessOverride.clear()
}

private fun MainActivityRule.awaitText(resId: Int) {
    val text = activity.getString(resId)
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    waitForIdle()
}

private fun MainActivityRule.phase(): AppPhase {
    var phase = AppPhase.CHECKING
    runOnUiThread { phase = activity.startupGate.phase.value }
    return phase
}

private fun MainActivityRule.assertOnOnboarding() {
    assertEquals(AppPhase.ONBOARDING, phase())
    assertEquals(listOf(OnboardingRoute), backStack())
}
