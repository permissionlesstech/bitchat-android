package org.briarproject.briar.android.settings;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import static android.app.Activity.RESULT_OK;
import static android.media.RingtoneManager.ACTION_RINGTONE_PICKER;
import static android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI;
import static android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI;
import static android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI;
import static android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT;
import static android.media.RingtoneManager.EXTRA_RINGTONE_TITLE;
import static android.media.RingtoneManager.EXTRA_RINGTONE_TYPE;
import static android.media.RingtoneManager.TYPE_NOTIFICATION;
import static android.os.Build.VERSION.SDK_INT;
import static android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS;
import static android.provider.Settings.EXTRA_APP_PACKAGE;
import static android.provider.Settings.EXTRA_CHANNEL_ID;
import static android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;
import static org.briarproject.briar.android.activity.RequestCodes.REQUEST_RINGTONE;
import static org.briarproject.briar.android.settings.SettingsActivity.enableAndPersist;
import static org.briarproject.briar.api.android.AndroidNotificationManager.BLOG_CHANNEL_ID;
import static org.briarproject.briar.api.android.AndroidNotificationManager.CONTACT_CHANNEL_ID;
import static org.briarproject.briar.api.android.AndroidNotificationManager.FORUM_CHANNEL_ID;
import static org.briarproject.briar.api.android.AndroidNotificationManager.GROUP_CHANNEL_ID;
import static org.briarproject.briar.api.android.AndroidNotificationManager.PREF_NOTIFY_BLOG;
import static org.briarproject.briar.api.android.AndroidNotificationManager.PREF_NOTIFY_FORUM;
import static org.briarproject.briar.api.android.AndroidNotificationManager.PREF_NOTIFY_GROUP;
import static org.briarproject.briar.api.android.AndroidNotificationManager.PREF_NOTIFY_PRIVATE;
import static org.briarproject.briar.api.android.AndroidNotificationManager.PREF_NOTIFY_SOUND;
import static org.briarproject.briar.api.android.AndroidNotificationManager.PREF_NOTIFY_VIBRATION;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class NotificationsFragment extends PreferenceFragmentCompat {

	public static final String PREF_NOTIFY_SIGN_IN = "pref_key_notify_sign_in";
	private static final int NOTIFICATION_CHANNEL_API = 26;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private SettingsViewModel viewModel;
	private NotificationsManager nm;

	private SwitchPreferenceCompat notifyPrivateMessages;
	private SwitchPreferenceCompat notifyGroupMessages;
	private SwitchPreferenceCompat notifyForumPosts;
	private SwitchPreferenceCompat notifyBlogPosts;
	private SwitchPreferenceCompat notifyVibration;

	private Preference notifySound;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
		nm = viewModel.notificationsManager;
	}

	@Override
	public void onCreatePreferences(Bundle bundle, String s) {
		addPreferencesFromResource(R.xml.settings_notifications);

		notifyPrivateMessages = findPreference(PREF_NOTIFY_PRIVATE);
		notifyGroupMessages = findPreference(PREF_NOTIFY_GROUP);
		notifyForumPosts = findPreference(PREF_NOTIFY_FORUM);
		notifyBlogPosts = findPreference(PREF_NOTIFY_BLOG);
		notifyVibration = findPreference(PREF_NOTIFY_VIBRATION);
		notifySound = findPreference(PREF_NOTIFY_SOUND);

		if (SDK_INT < NOTIFICATION_CHANNEL_API) {
			// NOTIFY_SIGN_IN gets stored in Android's SharedPreferences
			notifyPrivateMessages
					.setPreferenceDataStore(viewModel.settingsStore);
			notifyGroupMessages.setPreferenceDataStore(viewModel.settingsStore);
			notifyForumPosts.setPreferenceDataStore(viewModel.settingsStore);
			notifyBlogPosts.setPreferenceDataStore(viewModel.settingsStore);
			notifyVibration.setPreferenceDataStore(viewModel.settingsStore);

			notifySound.setOnPreferenceClickListener(pref ->
					onNotificationSoundClicked()
			);
		} else {
			setupNotificationPreference(notifyPrivateMessages,
					CONTACT_CHANNEL_ID,
					R.string.notify_private_messages_setting_summary_26);
			setupNotificationPreference(notifyGroupMessages,
					GROUP_CHANNEL_ID,
					R.string.notify_group_messages_setting_summary_26);
			setupNotificationPreference(notifyForumPosts, FORUM_CHANNEL_ID,
					R.string.notify_forum_posts_setting_summary_26);
			setupNotificationPreference(notifyBlogPosts, BLOG_CHANNEL_ID,
					R.string.notify_blog_posts_setting_summary_26);

			notifyVibration.setVisible(false);
			notifySound.setVisible(false);
		}
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		if (SDK_INT < NOTIFICATION_CHANNEL_API) {
			LifecycleOwner lifecycleOwner = getViewLifecycleOwner();
			nm.getNotifyPrivateMessages().observe(lifecycleOwner, enabled -> {
				notifyPrivateMessages.setChecked(enabled);
				enableAndPersist(notifyPrivateMessages);
			});
			nm.getNotifyGroupMessages().observe(lifecycleOwner, enabled -> {
				notifyGroupMessages.setChecked(enabled);
				enableAndPersist(notifyGroupMessages);
			});
			nm.getNotifyForumPosts().observe(lifecycleOwner, enabled -> {
				notifyForumPosts.setChecked(enabled);
				enableAndPersist(notifyForumPosts);
			});
			nm.getNotifyBlogPosts().observe(lifecycleOwner, enabled -> {
				notifyBlogPosts.setChecked(enabled);
				enableAndPersist(notifyBlogPosts);
			});
			nm.getNotifyVibration().observe(lifecycleOwner, enabled -> {
				notifyVibration.setChecked(enabled);
				enableAndPersist(notifyVibration);
			});
			nm.getNotifySound().observe(lifecycleOwner, enabled -> {
				String text;
				if (enabled) {
					String ringtoneName = nm.getRingtoneName();
					if (isNullOrEmpty(ringtoneName)) {
						text = getString(R.string.notify_sound_setting_default);
					} else {
						text = ringtoneName;
					}
				} else {
					text = getString(R.string.notify_sound_setting_disabled);
				}
				notifySound.setSummary(text);
				notifySound.setEnabled(true);
			});
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.notification_settings_title);
	}

	@Override
	public void onActivityResult(int request, int result,
			@Nullable Intent data) {
		super.onActivityResult(request, result, data);
		if (request == REQUEST_RINGTONE && result == RESULT_OK &&
				data != null) {
			Uri uri = data.getParcelableExtra(EXTRA_RINGTONE_PICKED_URI);
			nm.onRingtoneSet(uri);
		}
	}

	@TargetApi(NOTIFICATION_CHANNEL_API)
	private void setupNotificationPreference(SwitchPreferenceCompat pref,
			String channelId, @StringRes int summary) {
		pref.setWidgetLayoutResource(0);
		pref.setSummary(summary);
		pref.setEnabled(true);
		pref.setOnPreferenceClickListener(clickedPref -> {
			String packageName = requireContext().getPackageName();
			Intent intent = new Intent(ACTION_CHANNEL_NOTIFICATION_SETTINGS)
					.putExtra(EXTRA_APP_PACKAGE, packageName)
					.putExtra(EXTRA_CHANNEL_ID, channelId);
			Context ctx = requireContext();
			if (intent.resolveActivity(ctx.getPackageManager()) != null) {
				startActivity(intent);
			} else {
				Toast.makeText(ctx, R.string.error_start_activity, LENGTH_SHORT)
						.show();
			}
			return true;
		});
	}

	private boolean onNotificationSoundClicked() {
		String title = getString(R.string.choose_ringtone_title);
		Intent i = new Intent(ACTION_RINGTONE_PICKER);
		i.putExtra(EXTRA_RINGTONE_TYPE, TYPE_NOTIFICATION);
		i.putExtra(EXTRA_RINGTONE_TITLE, title);
		i.putExtra(EXTRA_RINGTONE_DEFAULT_URI,
				DEFAULT_NOTIFICATION_URI);
		i.putExtra(EXTRA_RINGTONE_SHOW_SILENT, true);
		if (requireNonNull(nm.getNotifySound().getValue())) {
			Uri uri;
			String ringtoneUri = nm.getRingtoneUri();
			if (isNullOrEmpty(ringtoneUri))
				uri = DEFAULT_NOTIFICATION_URI;
			else uri = Uri.parse(ringtoneUri);
			i.putExtra(EXTRA_RINGTONE_EXISTING_URI, uri);
		}
		if (i.resolveActivity(requireActivity().getPackageManager()) != null) {
			startActivityForResult(i, REQUEST_RINGTONE);
		} else {
			Toast.makeText(getContext(), R.string.cannot_load_ringtone,
					LENGTH_SHORT).show();
		}
		return true;
	}

}
