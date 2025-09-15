package org.briarproject.briar.android.mailbox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.bramble.api.mailbox.MailboxStatus;
import org.briarproject.briar.R;
import org.briarproject.briar.android.view.BriarButton;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static androidx.core.content.ContextCompat.getColor;
import static androidx.core.widget.ImageViewCompat.setImageTintList;
import static androidx.transition.TransitionManager.beginDelayedTransition;
import static org.briarproject.bramble.api.mailbox.MailboxConstants.API_CLIENT_TOO_OLD;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;
import static org.briarproject.briar.android.util.UiUtils.MIN_DATE_RESOLUTION;
import static org.briarproject.briar.android.util.UiUtils.formatDate;
import static org.briarproject.briar.android.util.UiUtils.observeOnce;
import static org.briarproject.briar.android.util.UiUtils.showFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class MailboxStatusFragment extends Fragment {

	static final String TAG = MailboxStatusFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private MailboxViewModel viewModel;
	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable // UiThread
	private Runnable refresher = null;
	private boolean showUnlinkWarning = true;

	private ImageView imageView;
	private TextView statusTitleView, statusMessageView, statusInfoView;
	private Button wizardButton;
	private Button unlinkButton;
	private ProgressBar unlinkProgress;
	@Nullable
	private AlertDialog dialog = null;

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		FragmentActivity activity = requireActivity();
		getAndroidComponent(activity).inject(this);
		viewModel = new ViewModelProvider(activity, viewModelFactory)
				.get(MailboxViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_mailbox_status,
				container, false);
	}

	@Override
	public void onViewCreated(View v, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(v, savedInstanceState);

		BriarButton checkButton = v.findViewById(R.id.checkButton);
		checkButton.setOnClickListener(view ->
				observeOnce(viewModel.checkConnection(), this, result ->
						checkButton.reset()
				));

		imageView = v.findViewById(R.id.imageView);
		statusTitleView = v.findViewById(R.id.statusTitleView);
		statusMessageView = v.findViewById(R.id.statusMessageView);
		statusInfoView = v.findViewById(R.id.statusInfoView);
		viewModel.getStatus()
				.observe(getViewLifecycleOwner(), this::onMailboxStateChanged);

		wizardButton = v.findViewById(R.id.wizardButton);
		wizardButton.setOnClickListener(view -> {
			Fragment f = new ErrorWizardFragment();
			String tag = ErrorWizardFragment.TAG;
			showFragment(getParentFragmentManager(), f, tag, false);
		});

		unlinkButton = v.findViewById(R.id.unlinkButton);
		unlinkProgress = v.findViewById(R.id.unlinkProgress);
		unlinkButton.setOnClickListener(view ->
				onUnlinkButtonClicked(showUnlinkWarning)
		);
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.mailbox_status_title);
		viewModel.clearProblemNotification();
		refresher = this::refreshLastConnection;
		handler.postDelayed(refresher, MIN_DATE_RESOLUTION);
	}

	@Override
	public void onStop() {
		super.onStop();
		handler.removeCallbacks(refresher);
		refresher = null;
	}

	@Override
	public void onDetach() {
		super.onDetach();
		if (dialog != null) {
			dialog.dismiss();
			dialog = null;
		}
	}

	private void onMailboxStateChanged(MailboxStatus status) {
		@ColorRes int tintRes;
		@DrawableRes int iconRes;
		String title;
		String message = null;
		if (status.getMailboxCompatibility() < 0) {
			tintRes = R.color.briar_red_500;
			if (status.getMailboxCompatibility() == API_CLIENT_TOO_OLD) {
				title = getString(R.string.mailbox_status_app_too_old_title);
				message =
						getString(R.string.mailbox_status_app_too_old_message);
			} else {
				title = getString(
						R.string.mailbox_status_mailbox_too_old_title);
				message = getString(
						R.string.mailbox_status_mailbox_too_old_message);
			}
			iconRes = R.drawable.alerts_and_states_error;
			showUnlinkWarning = true;
			wizardButton.setVisibility(GONE);
		} else if (status.hasProblem(System.currentTimeMillis())) {
			tintRes = R.color.briar_red_500;
			title = getString(R.string.mailbox_status_failure_title);
			iconRes = R.drawable.alerts_and_states_error;
			showUnlinkWarning = false;
			wizardButton.setVisibility(VISIBLE);
		} else if (status.getAttemptsSinceSuccess() > 0) {
			iconRes = R.drawable.ic_help_outline_white;
			title = getString(R.string.mailbox_status_problem_title);
			tintRes = R.color.briar_orange_500;
			showUnlinkWarning = false;
			wizardButton.setVisibility(VISIBLE);
		} else {
			iconRes = R.drawable.ic_check_circle_outline;
			title = getString(R.string.mailbox_status_connected_title);
			tintRes = R.color.briar_brand_green;
			showUnlinkWarning = true;
			wizardButton.setVisibility(GONE);
		}
		imageView.setImageResource(iconRes);
		int color = getColor(requireContext(), tintRes);
		setImageTintList(imageView, ColorStateList.valueOf(color));
		statusTitleView.setText(title);
		if (message == null) {
			statusMessageView.setVisibility(GONE);
		} else {
			statusMessageView.setVisibility(VISIBLE);
			statusMessageView.setText(message);
		}

		long lastSuccess = status.getTimeOfLastSuccess();
		String lastConnectionText;
		if (lastSuccess < 0) {
			lastConnectionText =
					getString(R.string.mailbox_status_connected_never);
		} else {
			lastConnectionText = formatDate(requireContext(), lastSuccess);
		}
		String statusInfoText =
				getString(R.string.mailbox_status_connected_info,
						lastConnectionText);
		statusInfoView.setText(statusInfoText);
	}

	@UiThread
	private void refreshLastConnection() {
		MailboxStatus status = viewModel.getStatus().getValue();
		if (status != null) onMailboxStateChanged(status);
		if (refresher != null) {
			handler.postDelayed(refresher, MIN_DATE_RESOLUTION);
		}
	}

	private void onUnlinkButtonClicked(boolean showWarning) {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.BriarDialogTheme);
		builder.setTitle(R.string.mailbox_status_unlink_dialog_title);
		String msg = getString(R.string.mailbox_status_unlink_dialog_question);
		if (showWarning) {
			msg = getString(R.string.mailbox_status_unlink_dialog_warning) +
					"\n\n" + msg;
		}
		builder.setMessage(msg);
		builder.setPositiveButton(R.string.cancel,
				(dialog, which) -> dialog.cancel());
		builder.setNegativeButton(R.string.mailbox_status_unlink_button,
				(dialog, which) -> {
					ViewGroup v = (ViewGroup) getView();
					if (v != null) beginDelayedTransition(v);
					unlinkButton.setVisibility(INVISIBLE);
					unlinkProgress.setVisibility(VISIBLE);
					viewModel.unlink();
				});
		builder.setOnDismissListener(dialog ->
				MailboxStatusFragment.this.dialog = null);
		dialog = builder.show();
	}

}
