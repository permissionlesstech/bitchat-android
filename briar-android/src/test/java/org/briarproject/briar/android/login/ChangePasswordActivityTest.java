package org.briarproject.briar.android.login;

import android.widget.Button;
import android.widget.EditText;

import com.google.android.material.textfield.TextInputLayout;

import org.briarproject.bramble.api.crypto.DecryptionResult;
import org.briarproject.briar.R;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static android.os.Looper.getMainLooper;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.briarproject.bramble.api.crypto.DecryptionResult.SUCCESS;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.NONE;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.QUITE_STRONG;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.QUITE_WEAK;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.STRONG;
import static org.briarproject.bramble.api.crypto.PasswordStrengthEstimator.WEAK;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
public class ChangePasswordActivityTest {

	private ChangePasswordActivity changePasswordActivity;
	private TextInputLayout passwordConfirmationWrapper;
	private EditText currentPassword;
	private EditText newPassword;
	private EditText newPasswordConfirmation;
	private StrengthMeter strengthMeter;
	private Button changePasswordButton;

	@Mock
	private ChangePasswordViewModel viewModel;

	@Before
	public void setUp() {
		MockitoAnnotations.initMocks(this);
		changePasswordActivity =
				Robolectric.setupActivity(ChangePasswordActivity.class);
		changePasswordActivity.viewModel = viewModel;
		passwordConfirmationWrapper = changePasswordActivity
				.findViewById(R.id.new_password_confirm_wrapper);
		currentPassword = changePasswordActivity
				.findViewById(R.id.current_password_entry);
		newPassword = changePasswordActivity
				.findViewById(R.id.new_password_entry);
		newPasswordConfirmation = changePasswordActivity
				.findViewById(R.id.new_password_confirm);
		strengthMeter = changePasswordActivity
				.findViewById(R.id.strength_meter);
		changePasswordButton = changePasswordActivity
				.findViewById(R.id.change_password);
	}

	private void testStrengthMeter(String pass, float strength, int color) {
		newPassword.setText(pass);
		assertEquals(strengthMeter.getProgress(),
				(int) (strengthMeter.getMax() * strength));
		assertEquals(color, strengthMeter.getColor());
	}

	@Test
	public void testPasswordMatchUI() {
		// Password mismatch
		newPassword.setText("really.safe.password");
		newPasswordConfirmation.setText("really.safe.pass");
		assertFalse(changePasswordButton.isEnabled());
		assertEquals(passwordConfirmationWrapper.getError(),
				changePasswordActivity
						.getString(R.string.passwords_do_not_match));
		// Button enabled
		newPassword.setText("really.safe.pass");
		newPasswordConfirmation.setText("really.safe.pass");
		// Confirm that the password mismatch error message is not visible
		assertNotEquals(passwordConfirmationWrapper.getError(),
				changePasswordActivity
						.getString(R.string.passwords_do_not_match));
		// Nick has not been set, expect the button to be disabled
		assertFalse(changePasswordButton.isEnabled());
	}

	@Test
	public void testChangePasswordUI() {
		// Mock strong password strength answer
		when(viewModel.estimatePasswordStrength(anyString()))
				.thenReturn(STRONG);
		// Mock changing the password
		MutableLiveEvent<DecryptionResult> result = new MutableLiveEvent<>();
		when(viewModel.changePassword(anyString(), anyString()))
				.thenReturn(result);
		String curPass = "old.password";
		String safePass = "really.safe.password";
		currentPassword.setText(curPass);
		newPassword.setText(safePass);
		newPasswordConfirmation.setText(safePass);
		// Confirm that the create account button is clickable
		assertTrue(changePasswordButton.isEnabled());
		changePasswordButton.performClick();
		// Verify that the view model was called with the correct params
		verify(viewModel, times(1)).changePassword(eq(curPass), eq(safePass));
		// Return the result
		result.postEvent(SUCCESS);
		shadowOf(getMainLooper()).idle();
		assertTrue(changePasswordActivity.isFinishing());
	}

	@Test
	public void testStrengthMeterUI() {
		Assert.assertNotNull(changePasswordActivity);
		// Mock answers for UI testing only
		when(viewModel.estimatePasswordStrength("strong")).thenReturn(STRONG);
		when(viewModel.estimatePasswordStrength("qstrong"))
				.thenReturn(QUITE_STRONG);
		when(viewModel.estimatePasswordStrength("qweak"))
				.thenReturn(QUITE_WEAK);
		when(viewModel.estimatePasswordStrength("weak")).thenReturn(WEAK);
		when(viewModel.estimatePasswordStrength("empty")).thenReturn(NONE);
		// Test the meters progress and color for several values
		testStrengthMeter("strong", STRONG, StrengthMeter.GREEN);
		verify(viewModel, times(1)).estimatePasswordStrength(eq("strong"));
		testStrengthMeter("qstrong", QUITE_STRONG, StrengthMeter.LIME);
		verify(viewModel, times(1)).estimatePasswordStrength(eq("qstrong"));
		testStrengthMeter("qweak", QUITE_WEAK, StrengthMeter.YELLOW);
		verify(viewModel, times(1)).estimatePasswordStrength(eq("qweak"));
		testStrengthMeter("weak", WEAK, StrengthMeter.ORANGE);
		verify(viewModel, times(1)).estimatePasswordStrength(eq("weak"));
		// Not sure this should be the correct behaviour on an empty input ?
		testStrengthMeter("empty", NONE, StrengthMeter.RED);
		verify(viewModel, times(1)).estimatePasswordStrength(eq("empty"));
	}
}
