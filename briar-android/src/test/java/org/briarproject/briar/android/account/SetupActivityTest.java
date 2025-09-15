package org.briarproject.briar.android.account;

import org.briarproject.briar.R;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.clearText;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 21)
public class SetupActivityTest {

	@Rule
	public ActivityScenarioRule<SetupActivity> rule =
			new ActivityScenarioRule<>(SetupActivity.class);

	@Test
	public void testPasswordMatchUI() {
		moveToSetPasswordFragment();

		// error shown when passwords don't match, button is disabled
		onView(withId(R.id.password_entry)).perform(typeText("123456"));
		onView(withId(R.id.password_confirm)).perform(typeText("654321"));
		onView(withText(R.string.passwords_do_not_match))
				.check(matches(isDisplayed()));
		onView(withId(R.id.next)).check(matches(not(isEnabled())));

		// confirming correct password, removes error, enables button
		onView(withId(R.id.password_confirm)).perform(clearText());
		onView(withId(R.id.password_confirm)).perform(replaceText("123456"));
		onView(withText(R.string.passwords_do_not_match)).check(doesNotExist());
		onView(withId(R.id.next)).check(matches(isEnabled()));

		// clicking the button shows progress bar, no doze because SDK_INT==21
		onView(withId(R.id.next)).perform(scrollTo());
		onView(withId(R.id.next)).perform(click());
		onView(withId(R.id.progress)).check(matches(isDisplayed()));
	}

	private void moveToSetPasswordFragment() {
		onView(withId(R.id.nickname_entry)).perform(typeText("test"));
		onView(withId(R.id.next)).perform(click());
		onView(withId(R.id.password_entry)).check(matches(isDisplayed()));
	}
}
