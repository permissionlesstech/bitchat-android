package org.briarproject.briar.android.reporting;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.briar.android.util.UiUtils.onSingleLinkClick;
import static org.briarproject.briar.android.util.UiUtils.tryToStartActivity;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ReportFormFragment extends BaseFragment {

	public final static String TAG = ReportFormFragment.class.getName();
	private static final Logger LOG = getLogger(TAG);

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ReportViewModel viewModel;

	private EditText userCommentView;
	private EditText userEmailView;
	private TextView privacyPolicy;
	private CheckBox includeDebugReport;
	private Button chevron;
	private RecyclerView list;
	private View progress;
	@Nullable
	private MenuItem sendReport;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(ReportViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_report_form, container,
				false);

		userCommentView = v.findViewById(R.id.user_comment);
		userEmailView = v.findViewById(R.id.user_email);
		privacyPolicy = v.findViewById(R.id.PrivacyPolicy);
		includeDebugReport = v.findViewById(R.id.include_debug_report);
		chevron = v.findViewById(R.id.chevron);
		list = v.findViewById(R.id.list);
		progress = v.findViewById(R.id.progress_wheel);

		if (viewModel.getInitialComment() != null)
			userCommentView.setText(viewModel.getInitialComment());

		if (viewModel.isFeedback()) {
			includeDebugReport
					.setText(getString(R.string.include_debug_report_feedback));
			userCommentView.setHint(R.string.enter_feedback);
		} else {
			includeDebugReport.setChecked(true);
			userCommentView.setHint(R.string.describe_crash);
		}

		onSingleLinkClick(privacyPolicy, this::triggerPrivacyPolicy);

		chevron.setOnClickListener(view -> {
			boolean show = chevron.getText().equals(getString(R.string.show));
			viewModel.showReportData(show);
		});

		viewModel.getShowReportData().observe(getViewLifecycleOwner(), show -> {
			if (show) {
				chevron.setText(R.string.hide);
				list.setVisibility(VISIBLE);
				if (list.getAdapter() == null) {
					progress.setVisibility(VISIBLE);
				} else {
					progress.setVisibility(INVISIBLE);
				}
			} else {
				chevron.setText(R.string.show);
				list.setVisibility(GONE);
				progress.setVisibility(INVISIBLE);
			}
		});
		return v;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.dev_report_actions, menu);
		sendReport = menu.findItem(R.id.action_send_report);
		sendReport.setEnabled(false);
		viewModel.getReportData().observe(getViewLifecycleOwner(), data -> {
			list.setAdapter(new ReportDataAdapter(data.getItems()));
			sendReport.setEnabled(true);
			progress.setVisibility(INVISIBLE);
		});
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_send_report) {
			sendReport();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	private void sendReport() {
		userCommentView.setEnabled(false);
		userEmailView.setEnabled(false);
		requireNonNull(sendReport).setEnabled(false);
		list.setVisibility(GONE); // ensures that progress fits on screen
		progress.setVisibility(VISIBLE);

		// Retrieve user's comment and email address, if any
		String comment = userCommentView.getText().toString();
		String email = userEmailView.getText().toString();

		boolean includeReport = includeDebugReport.isChecked();

		// Send report (now or after next sign-in)
		if (viewModel.sendReport(comment, email, includeReport)) {
			// trying to send now
			Toast.makeText(requireContext(), R.string.dev_report_sending,
					LENGTH_SHORT).show();
		}
	}

	private void triggerPrivacyPolicy() {
		Intent i = new Intent(Intent.ACTION_VIEW);
		i.setData(Uri.parse("https://briarproject.org/privacy-policy/\\"));
		tryToStartActivity(requireActivity(), i);
	}

}
