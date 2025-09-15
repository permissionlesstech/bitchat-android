package org.briarproject.briar.android.account;

import android.app.Application;

import org.briarproject.android.dontkillmelib.DozeHelper;
import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.crypto.PasswordStrengthEstimator;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.logging.Logger.getLogger;
import static org.briarproject.briar.android.account.SetupViewModel.State.AUTHOR_NAME;
import static org.briarproject.briar.android.account.SetupViewModel.State.CREATED;
import static org.briarproject.briar.android.account.SetupViewModel.State.DOZE;
import static org.briarproject.briar.android.account.SetupViewModel.State.FAILED;
import static org.briarproject.briar.android.account.SetupViewModel.State.SET_PASSWORD;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class SetupViewModel extends AndroidViewModel {
	enum State {AUTHOR_NAME, SET_PASSWORD, DOZE, CREATED, FAILED}

	private static final Logger LOG =
			getLogger(SetupActivity.class.getName());

	@Nullable
	private String authorName, password;
	private final MutableLiveEvent<State> state = new MutableLiveEvent<>();
	private final MutableLiveData<Boolean> isCreatingAccount =
			new MutableLiveData<>(false);

	private final AccountManager accountManager;
	private final Executor ioExecutor;
	private final PasswordStrengthEstimator strengthEstimator;
	private final DozeHelper dozeHelper;

	@Inject
	SetupViewModel(Application app,
			AccountManager accountManager,
			@IoExecutor Executor ioExecutor,
			PasswordStrengthEstimator strengthEstimator,
			DozeHelper dozeHelper) {
		super(app);
		this.accountManager = accountManager;
		this.ioExecutor = ioExecutor;
		this.strengthEstimator = strengthEstimator;
		this.dozeHelper = dozeHelper;

		ioExecutor.execute(() -> {
			if (accountManager.accountExists()) {
				throw new AssertionError();
			} else {
				state.postEvent(AUTHOR_NAME);
			}
		});
	}

	LiveEvent<State> getState() {
		return state;
	}

	LiveData<Boolean> getIsCreatingAccount() {
		return isCreatingAccount;
	}

	void setAuthorName(String authorName) {
		this.authorName = authorName;
		state.setEvent(SET_PASSWORD);
	}

	void setPassword(String password) {
		if (authorName == null) throw new IllegalStateException();
		this.password = password;
		if (needToShowDozeFragment()) {
			state.setEvent(DOZE);
		} else {
			createAccount();
		}
	}

	float estimatePasswordStrength(String password) {
		return strengthEstimator.estimateStrength(password);
	}

	boolean needToShowDozeFragment() {
		return dozeHelper.needToShowDoNotKillMeFragment(getApplication());
	}

	void dozeExceptionConfirmed() {
		createAccount();
	}

	private void createAccount() {
		if (authorName == null) throw new IllegalStateException();
		if (password == null) throw new IllegalStateException();
		isCreatingAccount.setValue(true);
		ioExecutor.execute(() -> {
			if (accountManager.createAccount(authorName, password)) {
				LOG.info("Created account");
				state.postEvent(CREATED);
			} else {
				LOG.warning("Failed to create account");
				state.postEvent(FAILED);
			}
		});
	}
}
