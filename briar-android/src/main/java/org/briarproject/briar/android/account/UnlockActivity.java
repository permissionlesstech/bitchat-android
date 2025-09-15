package org.briarproject.briar.android.account;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.BiometricPrompt.AuthenticationCallback;
import android.hardware.biometrics.BiometricPrompt.AuthenticationResult;
import android.hardware.biometrics.BiometricPrompt.Builder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BaseActivity;
import org.briarproject.briar.api.android.LockManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import static android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
import static android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.INVISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static org.briarproject.briar.android.activity.RequestCodes.REQUEST_KEYGUARD_UNLOCK;
import static org.briarproject.briar.android.util.UiUtils.hasKeyguardLock;
import static org.briarproject.briar.android.util.UiUtils.hasUsableFingerprint;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class UnlockActivity extends BaseActivity {

	private static final Logger LOG =
			Logger.getLogger(UnlockActivity.class.getName());
	private static final String KEYGUARD_SHOWN = "keyguardShown";

	@Inject
	LockManager lockManager;

	private boolean keyguardShown = false;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		overridePendingTransition(0, 0);
		setContentView(R.layout.activity_unlock);

		if (!hasUsableFingerprint(this)) {
			getWindow().setBackgroundDrawable(null);
			findViewById(R.id.image).setVisibility(INVISIBLE);
		}
		keyguardShown = state != null && state.getBoolean(KEYGUARD_SHOWN);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		// Saving whether we've shown the keyguard already is necessary
		// for Android 6 when this activity gets destroyed.
		//
		// This will not help Android 5.
		// There the system will show the keyguard once again.
		// So if this activity was destroyed, the user needs to enter PIN twice.
		outState.putBoolean(KEYGUARD_SHOWN, keyguardShown);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode,
			@Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_KEYGUARD_UNLOCK) {
			if (resultCode == RESULT_OK) unlock();
			else {
				finish();
				overridePendingTransition(0, 0);
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Show keyguard after onActivityResult() as been called.
		// Check if app is still locked, lockable
		// and not finishing (which is possible if recreated)
		if (!keyguardShown && lockManager.isLocked() && !isFinishing()) {
			requestUnlock();
		} else if (!lockManager.isLocked()) {
			setResult(RESULT_OK);
			finish();
		}
	}

	private void requestUnlock() {
		if (SDK_INT >= 28 && hasUsableFingerprint(this)) {
			requestFingerprintUnlock();
		} else {
			requestKeyguardUnlock();
		}
	}

	@Override
	@SuppressLint("MissingSuperCall")
	public void onBackPressed() {
		moveTaskToBack(true);
	}

	@RequiresApi(api = 28)
	private void requestFingerprintUnlock() {
		BiometricPrompt biometricPrompt;
		if (SDK_INT >= 29) {
			biometricPrompt = new Builder(this)
					.setTitle(getString(R.string.lock_unlock))
					.setDescription(getString(
							R.string.lock_unlock_fingerprint_description))
					.setDeviceCredentialAllowed(true)
					.build();
		} else {
			biometricPrompt = new Builder(this)
					.setTitle(getString(R.string.lock_unlock))
					.setDescription(getString(
							R.string.lock_unlock_fingerprint_description))
					.setNegativeButton(getString(R.string.lock_unlock_password),
							getMainExecutor(),
							(dialog, which) -> requestKeyguardUnlock())
					.build();
		}
		CancellationSignal signal = new CancellationSignal();
		AuthenticationCallback callback = new AuthenticationCallback() {
			@Override
			public void onAuthenticationError(int errorCode,
					@Nullable CharSequence errString) {
				// when back button is pressed while fingerprint dialog shows
				if (errorCode == BIOMETRIC_ERROR_CANCELED ||
						errorCode == BIOMETRIC_ERROR_USER_CANCELED) {
					finish();
				}
				// e.g. 5 failed attempts
				else {
					if (hasKeyguardLock(UnlockActivity.this)) {
						requestKeyguardUnlock();
					} else {
						// normally fingerprints require a screen lock, but
						// who knows if that's true for all devices out there
						if (errString != null) {
							Toast.makeText(UnlockActivity.this, errString,
									Toast.LENGTH_LONG).show();
						}
						finish();
					}
				}
			}

			@Override
			public void onAuthenticationHelp(int helpCode,
					@Nullable CharSequence helpString) {
			}

			@Override
			public void onAuthenticationSucceeded(AuthenticationResult result) {
				unlock();
			}

			@Override
			public void onAuthenticationFailed() {
			}
		};
		biometricPrompt.authenticate(signal, getMainExecutor(), callback);
	}

	private void requestKeyguardUnlock() {
		KeyguardManager keyguardManager =
				(KeyguardManager) getSystemService(KEYGUARD_SERVICE);
		if (keyguardManager == null) throw new AssertionError();
		Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(
				SDK_INT < 23 ? getString(R.string.lock_unlock_verbose) :
						getString(R.string.lock_unlock), null);
		if (intent == null) {
			// the user must have removed the screen lock since locked
			LOG.warning("Unlocking without keyguard");
			unlock();
		} else {
			keyguardShown = true;
			try {
				startActivityForResult(intent, REQUEST_KEYGUARD_UNLOCK);
			} catch (ActivityNotFoundException e) {
				Toast.makeText(this, R.string.error_start_activity, LENGTH_LONG)
						.show();
			}
			overridePendingTransition(0, 0);
		}
	}

	private void unlock() {
		lockManager.setLocked(false);
		setResult(RESULT_OK);
		finish();
		overridePendingTransition(0, 0);
	}

}
