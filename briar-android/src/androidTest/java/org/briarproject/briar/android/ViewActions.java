package org.briarproject.briar.android;

import android.app.Activity;
import android.util.Log;
import android.view.View;

import org.hamcrest.Matcher;

import java.util.concurrent.TimeoutException;

import androidx.test.espresso.PerformException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.util.HumanReadables.describe;
import static androidx.test.espresso.util.TreeIterables.breadthFirstViewTraversal;
import static androidx.test.runner.lifecycle.Stage.RESUMED;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.SECONDS;

public class ViewActions {

	private final static long TIMEOUT_MS = SECONDS.toMillis(10);
	private final static long WAIT_MS = 50;

	public static void waitFor(final Matcher<View> viewMatcher) {
		onView(isRoot()).perform(waitUntilMatches(hasDescendant(viewMatcher)));
	}

	public static void waitFor(final Class<? extends Activity> clazz) {
		onView(isRoot()).perform(waitForActivity(clazz, RESUMED, TIMEOUT_MS));
	}

	public static void waitFor(final Class<? extends Activity> clazz,
			long timeout) {
		onView(isRoot()).perform(waitForActivity(clazz, RESUMED, timeout));
	}

	public static ViewAction waitUntilMatches(Matcher<View> viewMatcher) {
		return waitUntilMatches(viewMatcher, TIMEOUT_MS);
	}

	private static ViewAction waitUntilMatches(Matcher<View> viewMatcher,
			long timeout) {
		return new CustomViewAction(timeout) {
			@Override
			protected boolean exitConditionTrue(View view) {
				for (View child : breadthFirstViewTraversal(view)) {
					if (viewMatcher.matches(child)) return true;
				}
				return false;
			}

			@Override
			public String getDescription() {
				return "Wait for view matcher " + viewMatcher +
						" to match within " + timeout + " milliseconds.";
			}
		};
	}

	public static ViewAction waitForActivity(Class<? extends Activity> clazz,
			Stage stage, long timeout) {
		return new CustomViewAction(timeout) {
			@Override
			protected boolean exitConditionTrue(View view) {
				boolean found = false;
				ActivityLifecycleMonitor lifecycleMonitor =
						ActivityLifecycleMonitorRegistry.getInstance();
				log(lifecycleMonitor);
				for (Activity a : lifecycleMonitor
						.getActivitiesInStage(stage)) {
					if (a.getClass().equals(clazz)) found = true;
				}
				return found;
			}

			private void log(ActivityLifecycleMonitor lifecycleMonitor) {
				log(lifecycleMonitor, Stage.PRE_ON_CREATE);
				log(lifecycleMonitor, Stage.CREATED);
				log(lifecycleMonitor, Stage.STARTED);
				log(lifecycleMonitor, Stage.RESUMED);
				log(lifecycleMonitor, Stage.PAUSED);
				log(lifecycleMonitor, Stage.STOPPED);
				log(lifecycleMonitor, Stage.RESTARTED);
				log(lifecycleMonitor, Stage.DESTROYED);
			}

			private void log(ActivityLifecycleMonitor lifecycleMonitor,
					Stage stage) {
				for (Activity a : lifecycleMonitor
						.getActivitiesInStage(stage)) {
					Log.e("TEST", a.getClass().getSimpleName() +
							" is in state " + stage);
				}
			}

			@Override
			public String getDescription() {
				return "Wait for activity " + clazz.getName() + " in stage " +
						stage.name() + " within " + timeout +
						" milliseconds.";
			}
		};
	}

	private static abstract class CustomViewAction implements ViewAction {
		private final long timeout;

		public CustomViewAction() {
			this(TIMEOUT_MS);
		}

		public CustomViewAction(long timeout) {
			this.timeout = timeout;
		}

		@Override
		public Matcher<View> getConstraints() {
			return isDisplayed();
		}

		@Override
		public void perform(UiController uiController, View view) {
			uiController.loopMainThreadUntilIdle();
			long endTime = currentTimeMillis() + timeout;
			do {
				if (exitConditionTrue(view)) return;
				uiController.loopMainThreadForAtLeast(WAIT_MS);
			}
			while (currentTimeMillis() < endTime);

			throw new PerformException.Builder()
					.withActionDescription(getDescription())
					.withViewDescription(describe(view))
					.withCause(new TimeoutException())
					.build();
		}

		protected abstract boolean exitConditionTrue(View view);
	}

}
