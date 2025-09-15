package org.briarproject.briar.android;

import android.app.Activity;
import android.util.Log;

import com.jraska.falcon.Falcon.UnableToTakeScreenshotException;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.test.TestDataCreator;
import org.junit.ClassRule;

import javax.inject.Inject;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import tools.fastlane.screengrab.FalconScreenshotStrategy;
import tools.fastlane.screengrab.Screengrab;
import tools.fastlane.screengrab.locale.LocaleTestRule;

public abstract class ScreenshotTest extends UiTest {

	@ClassRule
	public static final LocaleTestRule localeTestRule = new LocaleTestRule();

	@Inject
	protected TestDataCreator testDataCreator;
	@Inject
	protected ConnectionRegistry connectionRegistry;
	@Inject
	protected Clock clock;

	protected void screenshot(String name, ActivityScenarioRule<?> rule) {
		rule.getScenario().onActivity(activity -> screenshot(name, activity));
	}

	protected void screenshot(String name, Activity activity) {
		try {
			Screengrab.screenshot(name, new FalconScreenshotStrategy(activity));
		} catch (UnableToTakeScreenshotException e) {
			Log.e("Screengrab", "Error taking screenshot", e);
		} catch (RuntimeException e) {
			if (e.getMessage() == null ||
					!e.getMessage().equals("Unable to capture screenshot."))
				throw e;
			// The tests should still pass when run from AndroidStudio
			// without manually granting permissions like fastlane does.
			Log.w("Screengrab", "Permission to write screenshot is missing.");
		}
	}

	protected long getMinutesAgo(int minutes) {
		return clock.currentTimeMillis() - minutes * 60 * 1000;
	}

}
