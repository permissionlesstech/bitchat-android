package org.briarproject.briar.android.fragment;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.BaseActivity;
import org.briarproject.briar.api.android.ScreenFilterMonitor;
import org.briarproject.briar.api.android.ScreenFilterMonitor.AppDetails;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.fragment.app.DialogFragment;

import static android.os.Build.VERSION.SDK_INT;
import static android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION;
import static android.view.View.GONE;
import static org.briarproject.briar.android.util.UiUtils.tryToStartActivity;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ScreenFilterDialogFragment extends DialogFragment {

	public static final String TAG = ScreenFilterDialogFragment.class.getName();

	@Inject
	ScreenFilterMonitor screenFilterMonitor;

	private DismissListener dismissListener = null;

	public static ScreenFilterDialogFragment newInstance(
			Collection<AppDetails> apps) {
		ScreenFilterDialogFragment frag = new ScreenFilterDialogFragment();
		Bundle args = new Bundle();
		ArrayList<String> appNames = new ArrayList<>();
		for (AppDetails a : apps) appNames.add(a.name);
		args.putStringArrayList("appNames", appNames);
		ArrayList<String> packageNames = new ArrayList<>();
		for (AppDetails a : apps) packageNames.add(a.packageName);
		args.putStringArrayList("packageNames", packageNames);
		frag.setArguments(args);
		return frag;
	}

	public void setDismissListener(DismissListener dismissListener) {
		this.dismissListener = dismissListener;
	}

	@Override
	public void onAttach(Context ctx) {
		super.onAttach(ctx);
		((BaseActivity) requireActivity()).getActivityComponent().inject(this);
	}

	@NotNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Activity activity = getActivity();
		if (activity == null) throw new IllegalStateException();
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				activity, R.style.BriarDialogThemeNoFilter);
		builder.setTitle(R.string.screen_filter_title);
		Bundle args = getArguments();
		if (args == null) throw new IllegalStateException();
		ArrayList<String> appNames = args.getStringArrayList("appNames");
		ArrayList<String> packageNames =
				args.getStringArrayList("packageNames");
		if (appNames == null || packageNames == null)
			throw new IllegalStateException();
		LayoutInflater inflater = activity.getLayoutInflater();
		// See https://stackoverflow.com/a/24720976/6314875
		@SuppressLint("InflateParams")
		View dialogView = inflater.inflate(R.layout.dialog_screen_filter, null);
		builder.setView(dialogView);
		TextView message = dialogView.findViewById(R.id.screen_filter_message);
		CheckBox allow = dialogView.findViewById(R.id.screen_filter_checkbox);
		if (SDK_INT <= 29) {
			message.setText(getString(R.string.screen_filter_body,
					TextUtils.join("\n", appNames)));
		} else {
			message.setText(R.string.screen_filter_body_api_30);
			allow.setVisibility(GONE);
			builder.setNeutralButton(R.string.screen_filter_review_apps,
					(dialog, which) -> {
						Intent i = new Intent(ACTION_MANAGE_OVERLAY_PERMISSION);
						tryToStartActivity(requireActivity(), i);
					});
		}
		builder.setPositiveButton(R.string.continue_button, (dialog, which) -> {
			if (allow.isChecked()) screenFilterMonitor.allowApps(packageNames);
			dialog.dismiss();
		});
		builder.setCancelable(false);
		return builder.create();
	}

	@Override
	public void onDismiss(DialogInterface dialog) {
		super.onDismiss(dialog);
		if (dismissListener != null) dismissListener.onDialogDismissed();
	}

	public interface DismissListener {
		void onDialogDismissed();
	}
}
