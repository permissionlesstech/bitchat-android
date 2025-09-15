package org.briarproject.briar.android.account;

import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ProgressBar;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.briarproject.briar.R;
import org.briarproject.briar.android.login.StrengthMeter;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.Context.INPUT_METHOD_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static androidx.core.content.ContextCompat.checkSelfPermission;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.QUITE_WEAK;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.STRONG;
import static org.briarproject.briar.android.util.UiUtils.hideViewOnSmallScreen;
import static org.briarproject.briar.android.util.UiUtils.setError;
import static org.briarproject.briar.android.util.UiUtils.showOnboardingDialog;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SetPasswordFragment extends SetupFragment {
	private final static String TAG = SetPasswordFragment.class.getName();

	private TextInputLayout passwordEntryWrapper;
	private TextInputLayout passwordConfirmationWrapper;
	private TextInputEditText passwordEntry;
	private TextInputEditText passwordConfirmation;
	private StrengthMeter strengthMeter;
	private Button nextButton;

	private final ActivityResultLauncher<String> requestPermissionLauncher =
			registerForActivityResult(new RequestPermission(), isGranted ->
					setPassword());

	public static SetPasswordFragment newInstance() {
		return new SetPasswordFragment();
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_setup_password, container,
				false);

		strengthMeter = v.findViewById(R.id.strength_meter);
		passwordEntryWrapper = v.findViewById(R.id.password_entry_wrapper);
		passwordEntry = v.findViewById(R.id.password_entry);
		passwordConfirmationWrapper =
				v.findViewById(R.id.password_confirm_wrapper);
		passwordConfirmation = v.findViewById(R.id.password_confirm);
		Button infoButton = v.findViewById(R.id.info_button);
		nextButton = v.findViewById(R.id.next);
		ProgressBar progressBar = v.findViewById(R.id.progress);

		passwordEntry.addTextChangedListener(this);
		passwordConfirmation.addTextChangedListener(this);
		infoButton.setOnClickListener(view ->
				showOnboardingDialog(requireContext(), getHelpText()));
		nextButton.setOnClickListener(this);

		if (!viewModel.needToShowDozeFragment()) {
			nextButton.setText(R.string.create_account_button);
		}

		viewModel.getIsCreatingAccount()
				.observe(getViewLifecycleOwner(), isCreatingAccount -> {
					if (isCreatingAccount) {
						nextButton.setVisibility(INVISIBLE);
						progressBar.setVisibility(VISIBLE);
						// this also avoids the keyboard popping up
						passwordEntry.setFocusable(false);
						passwordConfirmation.setFocusable(false);
					}
				});

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		hideViewOnSmallScreen(requireView().findViewById(R.id.logo));
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	protected String getHelpText() {
		return getString(R.string.setup_password_explanation);
	}

	@Override
	public void onTextChanged(CharSequence authorName, int i, int i1, int i2) {
		String password1 = passwordEntry.getText().toString();
		String password2 = passwordConfirmation.getText().toString();
		boolean passwordsMatch = password1.equals(password2);

		strengthMeter.setVisibility(!password1.isEmpty() ? VISIBLE : INVISIBLE);
		float strength = viewModel.estimatePasswordStrength(password1);
		strengthMeter.setStrength(strength);
		boolean strongEnough = strength >= QUITE_WEAK;

		if (!password1.isEmpty()) {
			if (strength >= STRONG) {
				passwordEntryWrapper.setHelperText(
						getString(R.string.password_strong));
			} else if (strength >= QUITE_WEAK) {
				passwordEntryWrapper.setHelperText(
						getString(R.string.password_quite_strong));
			} else {
				passwordEntryWrapper.setHelperTextEnabled(false);
			}
		}
		setError(passwordEntryWrapper, getString(R.string.password_too_weak),
				!password1.isEmpty() && !strongEnough);
		setError(passwordConfirmationWrapper,
				getString(R.string.passwords_do_not_match),
				!password2.isEmpty() && !passwordsMatch);

		boolean enabled = passwordsMatch && strongEnough;
		nextButton.setEnabled(enabled);
		passwordConfirmation.setOnEditorActionListener(enabled ? this : null);
	}

	@Override
	public void onClick(View view) {
		IBinder token = passwordEntry.getWindowToken();
		Object o = requireContext().getSystemService(INPUT_METHOD_SERVICE);
		((InputMethodManager) o).hideSoftInputFromWindow(token, 0);
		if (SDK_INT >= 33 &&
				checkSelfPermission(requireContext(), POST_NOTIFICATIONS) !=
						PERMISSION_GRANTED) {
			// this calls setPassword() when it returns
			requestPermissionLauncher.launch(POST_NOTIFICATIONS);
		} else {
			setPassword();
		}
	}

	private void setPassword() {
		viewModel.setPassword(passwordEntry.getText().toString());
	}

}
