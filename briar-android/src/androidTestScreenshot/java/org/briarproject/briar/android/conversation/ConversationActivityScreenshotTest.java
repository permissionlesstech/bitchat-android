package org.briarproject.briar.android.conversation;

import android.content.Context;
import android.content.Intent;

import org.briarproject.briar.android.BriarUiTestComponent;
import org.briarproject.briar.android.ScreenshotTest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.briarproject.briar.android.conversation.ConversationActivity.CONTACT_ID;

@RunWith(AndroidJUnit4.class)
public class ConversationActivityScreenshotTest extends ScreenshotTest {

	@Rule
	public ActivityTestRule<ConversationActivity> testRule =
			new ActivityTestRule<>(ConversationActivity.class, false, false);

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
	}

	@Test
	public void messaging() {
		Context targetContext = getApplicationContext();
		Intent intent = new Intent(targetContext, ConversationActivity.class);
		intent.putExtra(CONTACT_ID, 1);
		testRule.launchActivity(intent);

		// TODO add test data and wait for it do appear

		screenshot("manual_messaging", testRule.getActivity());
	}

}
