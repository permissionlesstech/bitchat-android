package org.briarproject.briar.android.settings;

import android.content.Intent;
import android.view.Gravity;

import org.briarproject.briar.R;
import org.briarproject.briar.android.BriarUiTestComponent;
import org.briarproject.briar.android.ScreenshotTest;
import org.briarproject.briar.android.navdrawer.NavDrawerActivity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.espresso.contrib.DrawerActions;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.contrib.DrawerMatchers.isClosed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withChild;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.briarproject.briar.android.ViewActions.waitUntilMatches;
import static org.briarproject.briar.android.util.UiUtils.hasScreenLock;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class SettingsActivityScreenshotTest extends ScreenshotTest {

	@Rule
	public CleanAccountTestRule<SettingsActivity> testRule =
			new CleanAccountTestRule<>(SettingsActivity.class);

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
	}

	@Test
	public void changeTheme() {
		onView(withText(R.string.settings_button))
				.check(matches(isDisplayed()));

		onView(withText(R.string.display_settings_title))
				.perform(waitUntilMatches(isDisplayed()))
				.perform(click(), click());

		screenshot("manual_dark_theme_settings", testRule.getActivity());

		// switch to dark theme
		onView(withText(R.string.pref_theme_title))
				.check(matches(isDisplayed()))
				.perform(click());
		onView(withText(R.string.pref_theme_dark))
				.check(matches(isDisplayed()))
				.perform(click());

		openNavDrawer();

		screenshot("manual_dark_theme_nav_drawer", testRule.getActivity());

		// switch to back to light theme
		onView(withText(R.string.settings_button))
				.check(matches(isDisplayed()))
				.perform(click());
		onView(withText(R.string.display_settings_title))
				.check(matches(isDisplayed()))
				.perform(click());
		onView(withText(R.string.pref_theme_title))
				.check(matches(isDisplayed()))
				.perform(click());
		onView(withText(R.string.pref_theme_light))
				.check(matches(isDisplayed()))
				.perform(click());
	}

	@Test
	public void appLock() {
		assumeTrue("device has no screen lock",
				hasScreenLock(getApplicationContext()));

		onView(withText(R.string.security_settings_title))
				.perform(waitUntilMatches(isDisplayed()))
				.perform(click(), click());

		// ensure app lock is displayed and enabled
		onView(withText(R.string.pref_lock_title))
				.check(matches(isDisplayed()))
				.perform(waitUntilMatches(isEnabled()))
				.perform(click());
		onView(withChild(withText(R.string.pref_lock_timeout_title)))
				.check(matches(isDisplayed()))
				.check(matches(isEnabled()));

		screenshot("manual_app_lock", testRule.getActivity());

		openNavDrawer();

		screenshot("manual_app_lock_nav_drawer", testRule.getActivity());
	}

	@Test
	public void torSettings() {
		// click network/connections settings
		onView(withText(R.string.network_settings_title))
				.perform(waitUntilMatches(isDisplayed()))
				.perform(click(), click());

		// wait for settings to get loaded and enabled
		onView(withText(R.string.tor_network_setting))
				.check(matches(isDisplayed()))
				.perform(waitUntilMatches(isEnabled()));

		screenshot("manual_tor_settings", testRule.getActivity());
	}

	private void openNavDrawer() {
		// start main activity
		Intent i =
				new Intent(testRule.getActivity(), NavDrawerActivity.class);
		testRule.getActivity().startActivity(i);

		// open navigation drawer
		onView(withId(R.id.drawer_layout))
				.check(matches(isClosed(Gravity.START)))
				.perform(DrawerActions.open());
	}

}
