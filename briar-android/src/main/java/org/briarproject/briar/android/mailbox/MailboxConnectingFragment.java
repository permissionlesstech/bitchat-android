package org.briarproject.briar.android.mailbox;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import static org.briarproject.briar.android.util.UiUtils.formatDuration;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class MailboxConnectingFragment extends Fragment {

	static final String TAG = MailboxConnectingFragment.class.getName();

	private static final String ARG_STARTED = "started";
	private static final long TIMEOUT_MS = TorConstants.EXTRA_CONNECT_TIMEOUT;
	private static final long REFRESH_INTERVAL_MS = 1_000;

	private final Handler handler = new Handler(Looper.getMainLooper());
	// Capture a method reference so we use the same reference for posting
	// and removing
	private final Runnable refresher = this::updateProgressBar;

	private ProgressBar progressBar;
	private long timeStarted;

	public static MailboxConnectingFragment newInstance(long timeStarted) {
		MailboxConnectingFragment f = new MailboxConnectingFragment();
		Bundle args = new Bundle();
		args.putLong(ARG_STARTED, timeStarted);
		f.setArguments(args);
		return f;
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_mailbox_connecting,
				container, false);

		progressBar = v.findViewById(R.id.progressBar);
		TextView info = v.findViewById(R.id.info);
		String duration = formatDuration(requireContext(), TIMEOUT_MS);
		info.setText(getString(R.string.mailbox_setup_connecting_info,
				duration));

		timeStarted = requireArguments().getLong(ARG_STARTED);

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.mailbox_setup_title);
		updateProgressBar();
	}

	@Override
	public void onStop() {
		super.onStop();
		handler.removeCallbacks(refresher);
	}

	private void updateProgressBar() {
		long elapsedMs = System.currentTimeMillis() - timeStarted;
		int percent = (int) (elapsedMs * 100 / TIMEOUT_MS);
		percent = Math.min(Math.max(percent, 0), 100);
		progressBar.setProgress(percent);
		handler.postDelayed(refresher, REFRESH_INTERVAL_MS);
	}
}
