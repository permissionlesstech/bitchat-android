package org.briarproject.briar.android.account;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.account.PowerView.OnCheckedChangedListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.Nullable;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static org.briarproject.android.dontkillmelib.DozeUtils.getDozeWhitelistingIntent;
import static org.briarproject.briar.android.activity.RequestCodes.REQUEST_DOZE_WHITELISTING;
import static org.briarproject.briar.android.util.UiUtils.hideViewOnSmallScreen;
import static org.briarproject.briar.android.util.UiUtils.showOnboardingDialog;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class DozeFragment extends SetupFragment
		implements OnCheckedChangedListener {

	private final static String TAG = DozeFragment.class.getName();

	private DozeView dozeView;
	private HuaweiProtectedAppsView huaweiProtectedAppsView;
	private HuaweiAppLaunchView huaweiAppLaunchView;
	private XiaomiRecentAppsView xiaomiRecentAppsView;
	private XiaomiLockAppsView xiaomiLockAppsView;
	private Button next;
	private boolean secondAttempt = false;

	public static DozeFragment newInstance() {
		return new DozeFragment();
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(getString(R.string.dnkm_doze_title));
		setHasOptionsMenu(false);
		View v = inflater.inflate(R.layout.fragment_setup_doze, container,
				false);
		dozeView = v.findViewById(R.id.dozeView);
		dozeView.setOnCheckedChangedListener(this);
		huaweiProtectedAppsView = v.findViewById(R.id.huaweiProtectedAppsView);
		huaweiProtectedAppsView.setOnCheckedChangedListener(this);
		huaweiAppLaunchView = v.findViewById(R.id.huaweiAppLaunchView);
		huaweiAppLaunchView.setOnCheckedChangedListener(this);
		xiaomiRecentAppsView = v.findViewById(R.id.xiaomiRecentAppsView);
		xiaomiRecentAppsView.setOnCheckedChangedListener(this);
		xiaomiLockAppsView = v.findViewById(R.id.xiaomiLockAppsView);
		xiaomiLockAppsView.setOnCheckedChangedListener(this);
		next = v.findViewById(R.id.next);
		ProgressBar progressBar = v.findViewById(R.id.progress);

		dozeView.setOnButtonClickListener(this::askForDozeWhitelisting);
		next.setOnClickListener(this);

		viewModel.getIsCreatingAccount()
				.observe(getViewLifecycleOwner(), isCreatingAccount -> {
					if (isCreatingAccount) {
						next.setVisibility(INVISIBLE);
						progressBar.setVisibility(VISIBLE);
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
		return getString(R.string.dnkm_doze_explanation);
	}

	@Override
	public void onActivityResult(int request, int result,
			@Nullable Intent data) {
		super.onActivityResult(request, result, data);
		if (request == REQUEST_DOZE_WHITELISTING) {
			if (!dozeView.needsToBeShown() || secondAttempt) {
				dozeView.setChecked(true);
			} else if (getContext() != null) {
				secondAttempt = true;
				showOnboardingDialog(getContext(), getHelpText());
			}
		}
	}

	@Override
	public void onCheckedChanged() {
		next.setEnabled(dozeView.isChecked() &&
				huaweiProtectedAppsView.isChecked() &&
				huaweiAppLaunchView.isChecked() &&
				xiaomiRecentAppsView.isChecked() &&
				xiaomiLockAppsView.isChecked());
	}

	@SuppressLint("BatteryLife")
	private void askForDozeWhitelisting() {
		if (getContext() == null) return;
		Intent i = getDozeWhitelistingIntent(getContext());
		try {
			startActivityForResult(i, REQUEST_DOZE_WHITELISTING);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(requireContext(),
					R.string.error_start_activity, LENGTH_LONG).show();
		}
	}

	@Override
	public void onClick(View view) {
		viewModel.dozeExceptionConfirmed();
	}
}
