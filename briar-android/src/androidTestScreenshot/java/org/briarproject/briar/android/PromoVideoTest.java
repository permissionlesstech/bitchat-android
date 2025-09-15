package org.briarproject.briar.android;

import android.view.View;

import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.contact.PendingContactState;
import org.briarproject.briar.R;
import org.briarproject.briar.android.account.SetupActivity;
import org.briarproject.briar.android.contact.add.remote.PendingContactListActivity;
import org.briarproject.briar.android.navdrawer.NavDrawerActivity;
import org.briarproject.briar.android.splash.SplashScreenActivity;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.inject.Inject;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static java.lang.Thread.sleep;
import static org.briarproject.android.dontkillmelib.DozeUtils.needsDozeWhitelisting;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.ID;
import static org.briarproject.briar.android.OverlayTapViewAction.visualClick;
import static org.briarproject.briar.android.ViewActions.waitFor;
import static org.briarproject.briar.android.ViewActions.waitUntilMatches;
import static org.hamcrest.CoreMatchers.allOf;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PromoVideoTest extends ScreenshotTest {

	// we can leave isFilming to false (to speed up CI)
	// and only set it to true when doing recordings
	private static final boolean isFilming = false;

	private static final int DELAY_SMALL = isFilming ? 4_000 : 0;
	private static final int DELAY_MEDIUM = isFilming ? 7_500 : 0;
	private static final int DELAY_LONG = isFilming ? 10_000 : 0;

	@Rule
	public ActivityScenarioRule<SplashScreenActivity> testRule =
			new ActivityScenarioRule<>(SplashScreenActivity.class);

	@Inject
	protected ContactManager contactManager;

	private OverlayView overlayView;

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
		accountManager.deleteAccount();
	}

	@Test
	public void createAccountAddContact() throws Throwable {
		if (isFilming) {
			// Using this breaks emulator CI tests for some reason.
			// Only use it for filming for now until we have time to debug this.
			overlayView = OverlayView.attach(getApplicationContext());
		}

		// Splash screen shows logo
		onView(withId(R.id.logoView))
				.perform(waitUntilMatches(isDisplayed()));

		// It takes a long time for SetupActivity to start after the splash,
		// (because it is shown longer for videos), so increase timeout.
		if (!isFilming) waitFor(SetupActivity.class, 30_000);

		// Note: We use waiting code only when not filming,
		//       to make the test reliable for CI. Otherwise, we used fixed
		//       delays to deterministically align with subtitles.

		sleep(DELAY_LONG);

		// Enter username
		onView(withText(R.string.setup_title))
				.perform(waitUntilMatches(isDisplayed()));
		sleep(DELAY_SMALL);
		onView(withId(R.id.nickname_entry))
				.check(matches(isDisplayed()))
				.perform(replaceText(USERNAME));
		closeKeyboard(withId(R.id.nickname_entry));

		sleep(DELAY_SMALL);

		doClick(withId(R.id.next));

		sleep(DELAY_MEDIUM);

		// Enter password
		doClick(withId(R.id.password_entry), 1000);
		onView(withId(R.id.password_entry))
				.check(matches(isDisplayed()))
				.perform(replaceText(PASSWORD));
		sleep(DELAY_SMALL);
		doClick(withId(R.id.password_confirm), 1000);
		onView(withId(R.id.password_confirm))
				.check(matches(isDisplayed()))
				.perform(replaceText(PASSWORD));

		sleep(DELAY_SMALL);
		// click next or create account
		doClick(withId(R.id.next));

		sleep(DELAY_SMALL);

		// White-list Doze if needed
		if (needsDozeWhitelisting(getApplicationContext())) {
			doClick(withText(R.string.dnkm_doze_button));
			UiDevice device = UiDevice.getInstance(getInstrumentation());
			UiObject allowButton = device.findObject(
					new UiSelector().className("android.widget.Button")
							.index(1));
			allowButton.click();
			doClick(withId(R.id.next));
		}

		lifecycleManager.waitForStartup();
		assertTrue(accountManager.hasDatabaseKey());

		sleep(DELAY_SMALL);

		// wait for contact list to be shown
		if (!isFilming) waitFor(NavDrawerActivity.class);

		// clicking the FAB doesn't work, so we click its inner FAB as well
		onView(withId(R.id.speedDial))
				.check(matches(isDisplayed()))
				.perform(click());
		doClick(withId(R.id.fab_main)); // this is inside R.id.speedDial
		sleep(DELAY_MEDIUM);

		// click adding contact at a distance menu item
		doClick(withText(R.string.add_contact_remotely_title));
		sleep(DELAY_LONG);

		// enter briar:// link
		String link =
				"briar://ab54fpik6sjyetzjhlwto2fv7tspibx2uhpdnei4tdidkvjpbphvy";
		doClick(withId(R.id.pasteButton));
		onView(withId(R.id.linkInput))
				.perform(waitUntilMatches(isDisplayed()))
				.perform(replaceText(link));
		sleep(DELAY_MEDIUM);

		doClick(withId(R.id.addButton));
		sleep(DELAY_MEDIUM);

		// enter contact alias
		String contactName = getApplicationContext()
				.getString(R.string.screenshot_bob);
		doClick(withId(R.id.contactNameInput), 1000);
		onView(withId(R.id.contactNameInput))
				.perform(waitUntilMatches(isDisplayed()))
				.perform(replaceText(contactName));
		sleep(DELAY_SMALL);
		closeKeyboard(withId(R.id.contactNameInput));
		sleep(DELAY_SMALL);

		// add pending contact
		onView(withId(R.id.addButton)).perform(scrollTo());
		doClick(withId(R.id.addButton));
		sleep(DELAY_LONG);

		// wait for pending contact list activity to be shown
		if (!isFilming) {
			waitFor(PendingContactListActivity.class);
			waitFor(allOf(withText(R.string.pending_contact_requests),
					isDisplayed()));
		}

		// remove pending contact
		for (Pair<PendingContact, PendingContactState> p : contactManager
				.getPendingContacts()) {
			contactManager.removePendingContact(p.getFirst().getId());
		}
		// add contact and make them appear online
		Contact bob = testDataCreator.addContact(contactName, false, true);
		sleep(DELAY_SMALL);
		connectionRegistry.registerIncomingConnection(bob.getId(), ID, () -> {
		});

		sleep(DELAY_LONG);

		// wait for contact list to be shown
		if (!isFilming) {
			waitFor(NavDrawerActivity.class);
			waitFor(allOf(withText(R.string.contact_list_button),
					isDisplayed()));
			waitFor(allOf(withId(R.id.recyclerView), isDisplayed()));
		}

		// click on new contact
		doItemClick(withId(R.id.recyclerView), 0);

		sleep(DELAY_MEDIUM);

		// bring up keyboard
		doClick(withId(R.id.input_text), DELAY_SMALL);

		String msg1 = getApplicationContext()
				.getString(R.string.screenshot_message_1);
		onView(withId(R.id.input_text))
				.perform(waitUntilMatches(isEnabled()))
				.perform(replaceText(msg1));

		sleep(DELAY_SMALL);

		doClick(withId(R.id.compositeSendButton));

		sleep(DELAY_SMALL);

		// send emoji
		doClick(withId(R.id.emoji_toggle), DELAY_SMALL);
		onView(withId(R.id.input_text))
				.perform(replaceText("\uD83D\uDE0E"));
		sleep(DELAY_SMALL);
		doClick(withId(R.id.compositeSendButton));

		// close keyboard
		closeKeyboard(withId(R.id.compositeSendButton));

		sleep(DELAY_LONG);
	}

	private void doClick(final Matcher<View> viewMatcher, long sleepMs)
			throws InterruptedException {
		doClick(viewMatcher);
		if (isFilming) sleep(sleepMs);
	}

	private void doClick(final Matcher<View> viewMatcher)
			throws InterruptedException {
		if (isFilming) {
			onView(viewMatcher)
					.perform(waitUntilMatches(isDisplayed()))
					.perform(visualClick(overlayView));
			sleep(500);
		}
		onView(viewMatcher)
				.perform(waitUntilMatches(allOf(isDisplayed(), isEnabled())))
				.perform(click());
	}

	private void doItemClick(final Matcher<View> viewMatcher, int pos)
			throws InterruptedException {
		if (isFilming) {
			onView(viewMatcher).perform(
					actionOnItemAtPosition(pos, visualClick(overlayView)));
			sleep(500);
		}
		onView(viewMatcher).perform(
				actionOnItemAtPosition(pos, click()));
	}

	private void closeKeyboard(final Matcher<View> viewMatcher)
			throws InterruptedException {
		if (isFilming) sleep(750);
		onView(viewMatcher).perform(closeSoftKeyboard());
	}

}
