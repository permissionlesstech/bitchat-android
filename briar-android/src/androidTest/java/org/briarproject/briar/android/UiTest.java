package org.briarproject.briar.android;

import android.app.Activity;
import android.content.Intent;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.ClassRule;

import javax.inject.Inject;

import androidx.test.espresso.intent.rule.IntentsTestRule;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;


@SuppressWarnings("WeakerAccess")
public abstract class UiTest {

	@ClassRule
	public static final ScreenshotOnFailureRule screenshotOnFailureRule =
			new ScreenshotOnFailureRule();

	protected final String USERNAME =
			getApplicationContext().getString(R.string.screenshot_alice);
	protected static final String PASSWORD = "123456";

	@Inject
	protected AccountManager accountManager;
	@Inject
	protected LifecycleManager lifecycleManager;
	@Inject
	protected SettingsManager settingsManager;

	public UiTest() {
		BriarTestComponentApplication app = getApplicationContext();
		inject((BriarUiTestComponent) app.getApplicationComponent());
	}

	protected abstract void inject(BriarUiTestComponent component);

	protected void startActivity(Class<? extends Activity> clazz) {
		Intent i = new Intent(getApplicationContext(), clazz);
		i.addFlags(FLAG_ACTIVITY_NEW_TASK);
		getApplicationContext().startActivity(i);
	}

	@NotNullByDefault
	protected class CleanAccountTestRule<A extends Activity>
			extends IntentsTestRule<A> {

		public CleanAccountTestRule(Class<A> activityClass) {
			super(activityClass);
		}

		@Override
		protected void beforeActivityLaunched() {
			super.beforeActivityLaunched();
			accountManager.deleteAccount();
			accountManager.createAccount(USERNAME, PASSWORD);
			Intent serviceIntent =
					new Intent(getApplicationContext(), BriarService.class);
			getApplicationContext().startService(serviceIntent);
			try {
				lifecycleManager.waitForStartup();
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
		}
	}

}
