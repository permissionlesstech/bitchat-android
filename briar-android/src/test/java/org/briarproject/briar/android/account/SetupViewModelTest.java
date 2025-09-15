package org.briarproject.briar.android.account;

import android.app.Application;
import android.content.Context;

import org.briarproject.android.dontkillmelib.DozeHelper;
import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.crypto.PasswordStrengthEstimator;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.briarproject.briar.android.account.SetupViewModel.State;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Rule;
import org.junit.Test;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import static junit.framework.Assert.assertEquals;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
import static org.briarproject.briar.android.account.SetupViewModel.State.CREATED;
import static org.briarproject.briar.android.viewmodel.LiveEventTestUtil.getOrAwaitValue;

public class SetupViewModelTest extends BrambleMockTestCase {

	@Rule
	public final InstantTaskExecutorRule testRule =
			new InstantTaskExecutorRule();

	private final String authorName = getRandomString(MAX_AUTHOR_NAME_LENGTH);
	private final String password = getRandomString(10);

	private final Application app;
	private final Context appContext;
	private final AccountManager accountManager;
	private final DozeHelper dozeHelper;

	public SetupViewModelTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		app = context.mock(Application.class);
		appContext = context.mock(Context.class);
		accountManager = context.mock(AccountManager.class);
		dozeHelper = context.mock(DozeHelper.class);
	}

	@Test
	public void testCreateAccount() throws Exception {
		context.checking(new Expectations() {{
			oneOf(accountManager).accountExists();
			will(returnValue(false));
			allowing(dozeHelper).needToShowDoNotKillMeFragment(app);
			allowing(app).getApplicationContext();
			will(returnValue(appContext));
			allowing(appContext).getPackageManager();

			// Create the account
			oneOf(accountManager).createAccount(authorName, password);
			will(returnValue(true));
		}});

		SetupViewModel viewModel = new SetupViewModel(app,
				accountManager,
				new ImmediateExecutor(),
				context.mock(PasswordStrengthEstimator.class),
				dozeHelper);

		viewModel.setAuthorName(authorName);
		viewModel.setPassword(password);

		State state = getOrAwaitValue(viewModel.getState());
		assertEquals(CREATED, state);
	}
}
