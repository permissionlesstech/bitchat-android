package org.briarproject.briar.android.login;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputLayout;

import org.briarproject.bramble.api.crypto.DecryptionResult;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;

import javax.inject.Inject;

import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static org.briarproject.bramble.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.briarproject.bramble.api.crypto.DecryptionResult.SUCCESS;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.QUITE_WEAK;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.STRONG;
import static org.briarproject.briar.android.login.LoginUtils.createKeyStrengthenerErrorDialog;
import static org.briarproject.briar.android.util.UiUtils.hideSoftKeyboard;
import static org.briarproject.briar.android.util.UiUtils.setError;
import static org.briarproject.briar.android.util.UiUtils.showSoftKeyboard;

public class ChangePasswordActivity extends BriarActivity
		implements OnClickListener, OnEditorActionListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private TextInputLayout currentPasswordEntryWrapper;
	private TextInputLayout newPasswordEntryWrapper;
	private TextInputLayout newPasswordConfirmationWrapper;
	private EditText currentPassword;
	private EditText newPassword;
	private EditText newPasswordConfirmation;
	private StrengthMeter strengthMeter;
	private Button changePasswordButton;
	private ProgressBar progress;

	@VisibleForTesting
	ChangePasswordViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ChangePasswordViewModel.class);
	}

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_change_password);

		currentPasswordEntryWrapper =
				findViewById(R.id.current_password_entry_wrapper);
		newPasswordEntryWrapper = findViewById(R.id.new_password_entry_wrapper);
		newPasswordConfirmationWrapper =
				findViewById(R.id.new_password_confirm_wrapper);
		currentPassword = findViewById(R.id.current_password_entry);
		newPassword = findViewById(R.id.new_password_entry);
		newPasswordConfirmation = findViewById(R.id.new_password_confirm);
		strengthMeter = findViewById(R.id.strength_meter);
		changePasswordButton = findViewById(R.id.change_password);
		progress = findViewById(R.id.progress_wheel);

		TextWatcher tw = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
				enableOrDisableContinueButton();
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		};

		currentPassword.addTextChangedListener(tw);
		newPassword.addTextChangedListener(tw);
		newPasswordConfirmation.addTextChangedListener(tw);
		newPasswordConfirmation.setOnEditorActionListener(this);
		changePasswordButton.setOnClickListener(this);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void enableOrDisableContinueButton() {
		if (progress == null) return; // Not created yet
		if (newPassword.getText().length() > 0 && newPassword.hasFocus())
			strengthMeter.setVisibility(VISIBLE);
		else strengthMeter.setVisibility(INVISIBLE);
		String firstPassword = newPassword.getText().toString();
		String secondPassword = newPasswordConfirmation.getText().toString();
		boolean passwordsMatch = firstPassword.equals(secondPassword);
		float strength = viewModel.estimatePasswordStrength(firstPassword);
		strengthMeter.setStrength(strength);

		if (!firstPassword.isEmpty()) {
			if (strength >= STRONG) {
				newPasswordEntryWrapper.setHelperText(
						getString(R.string.password_strong));
			} else if (strength >= QUITE_WEAK) {
				newPasswordEntryWrapper.setHelperText(
						getString(R.string.password_quite_strong));
			} else {
				newPasswordEntryWrapper.setHelperTextEnabled(false);
			}
		}

		setError(newPasswordEntryWrapper,
				getString(R.string.password_too_weak),
				!firstPassword.isEmpty() && strength < QUITE_WEAK);
		setError(newPasswordConfirmationWrapper,
				getString(R.string.passwords_do_not_match),
				!secondPassword.isEmpty() && !passwordsMatch);
		changePasswordButton.setEnabled(
				!currentPassword.getText().toString().isEmpty() &&
						passwordsMatch && strength >= QUITE_WEAK);
	}

	@Override
	public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		hideSoftKeyboard(v);
		return true;
	}

	@Override
	public void onClick(View view) {
		// Replace the button with a progress bar
		changePasswordButton.setVisibility(INVISIBLE);
		progress.setVisibility(VISIBLE);

		String curPwd = currentPassword.getText().toString();
		String newPwd = newPassword.getText().toString();
		viewModel.changePassword(curPwd, newPwd).observeEvent(this, result -> {
					if (result == SUCCESS) {
						Toast.makeText(ChangePasswordActivity.this,
								R.string.password_changed,
								LENGTH_LONG).show();
						setResult(RESULT_OK);
						supportFinishAfterTransition();
					} else {
						tryAgain(result);
					}
				}
		);
	}

	private void tryAgain(DecryptionResult result) {
		changePasswordButton.setVisibility(VISIBLE);
		progress.setVisibility(INVISIBLE);
		if (result == KEY_STRENGTHENER_ERROR) {
			createKeyStrengthenerErrorDialog(this).show();
		} else {
			setError(currentPasswordEntryWrapper,
					getString(R.string.try_again), true);
			currentPassword.setText("");
			// show the keyboard again
			showSoftKeyboard(currentPassword);
		}
	}
}
