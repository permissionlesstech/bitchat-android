package org.briarproject.briar.android.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import static java.util.Objects.requireNonNull;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;
import static org.briarproject.briar.android.settings.SettingsActivity.enableAndPersist;
import static org.briarproject.briar.android.util.UiUtils.hasScreenLock;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SecurityFragment extends PreferenceFragmentCompat {

	public static final String PREF_SCREEN_LOCK = "pref_key_lock";
	public static final String PREF_SCREEN_LOCK_TIMEOUT =
			"pref_key_lock_timeout";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private SettingsViewModel viewModel;
	private SwitchPreferenceCompat screenLock;
	private ListPreference screenLockTimeout;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
	}

	@Override
	public void onCreatePreferences(Bundle bundle, String s) {
		addPreferencesFromResource(R.xml.settings_security);
		getPreferenceManager().setPreferenceDataStore(viewModel.settingsStore);

		screenLock = findPreference(PREF_SCREEN_LOCK);
		screenLockTimeout =
				requireNonNull(findPreference(PREF_SCREEN_LOCK_TIMEOUT));

		screenLockTimeout.setSummaryProvider(preference -> {
			CharSequence timeout = screenLockTimeout.getValue();
			String never = getString(R.string.pref_lock_timeout_value_never);
			if (timeout.equals(never)) {
				return getString(R.string.pref_lock_timeout_never_summary);
			} else {
				return getString(R.string.pref_lock_timeout_summary,
						screenLockTimeout.getEntry());
			}
		});
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		// timeout depends on screenLock and gets disabled automatically
		LifecycleOwner lifecycleOwner = getViewLifecycleOwner();
		viewModel.getScreenLockTimeout().observe(lifecycleOwner, value -> {
			screenLockTimeout.setValue(value);
			enableAndPersist(screenLockTimeout);
		});
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.security_settings_title);
		checkScreenLock();
	}

	private void checkScreenLock() {
		LifecycleOwner lifecycleOwner = getViewLifecycleOwner();
		viewModel.getScreenLockEnabled().removeObservers(lifecycleOwner);
		if (hasScreenLock(requireActivity())) {
			viewModel.getScreenLockEnabled().observe(lifecycleOwner, on -> {
				screenLock.setChecked(on);
				enableAndPersist(screenLock);
			});
			screenLock.setSummary(R.string.pref_lock_summary);
		} else {
			screenLock.setEnabled(false);
			screenLock.setPersistent(false);
			screenLock.setChecked(false);
			screenLock.setSummary(R.string.pref_lock_disabled_summary);
		}
	}

}
