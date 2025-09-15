package org.briarproject.briar.android.panic;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.logging.Logger;

import javax.annotation.Nullable;

import androidx.preference.PreferenceManager;
import info.guardianproject.GuardianProjectRSA4096;
import info.guardianproject.panic.Panic;
import info.guardianproject.panic.PanicResponder;
import info.guardianproject.trustedintents.TrustedIntents;

import static java.util.logging.Logger.getLogger;
import static org.briarproject.briar.android.panic.PanicPreferencesFragment.KEY_LOCK;
import static org.briarproject.briar.android.panic.PanicPreferencesFragment.KEY_PURGE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class PanicResponderActivity extends BriarActivity {

	private static final Logger LOG =
			getLogger(PanicResponderActivity.class.getName());

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		TrustedIntents trustedIntents = TrustedIntents.get(this);
		// Guardian Project Ripple
		trustedIntents.addTrustedSigner(GuardianProjectRSA4096.class);
		// F-Droid
		trustedIntents.addTrustedSigner(FDroidSignaturePin.class);

		Intent intent = trustedIntents.getIntentFromTrustedSender(this);
		if (intent != null) {
			// received intent from trusted app
			if (Panic.isTriggerIntent(intent)) {
				SharedPreferences sharedPref = PreferenceManager
						.getDefaultSharedPreferences(this);

				LOG.info("Received Panic Trigger...");

				if (PanicResponder.receivedTriggerFromConnectedApp(this)) {
					LOG.info("Panic Trigger came from connected app");

					// Performing panic responses
					if (sharedPref.getBoolean(KEY_PURGE, false)) {
						LOG.info("Purging all data...");
						signOut(true, true);
					} else if (sharedPref.getBoolean(KEY_LOCK, true)) {
						LOG.info("Signing out...");
						signOut(true, false);
					} else {
						LOG.info("Configured not to purge or lock");
					}
				} else if (sharedPref.getBoolean(KEY_LOCK, true)) {
					// non-destructive actions are allowed by non-connected
					// trusted apps
					LOG.info("Signing out...");
					signOut(true, false);
				} else {
					LOG.info("Configured not to lock");
				}
			}
		}
		finishAndRemoveTask();
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}
}
