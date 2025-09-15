package org.briarproject.briar.android.removabledrive;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.bramble.api.plugin.file.RemovableDriveTask;
import org.briarproject.briar.R;
import org.briarproject.briar.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;
import static org.briarproject.briar.android.util.UiUtils.hideViewOnSmallScreen;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SendFragment extends Fragment {

	final static String TAG = SendFragment.class.getName();
	private static final Logger LOG = getLogger(TAG);

	private final ActivityResultLauncher<String> launcher =
			registerForActivityResult(new CreateDocumentAdvanced(),
					this::onDocumentCreated);

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private RemovableDriveViewModel viewModel;
	private TextView introTextView;
	private Button button;
	private ProgressBar progressBar;

	private boolean checkForStateLoss = false;

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		FragmentActivity activity = requireActivity();
		getAndroidComponent(activity).inject(this);
		viewModel = new ViewModelProvider(activity, viewModelFactory)
				.get(RemovableDriveViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_transfer_data_send,
				container, false);

		introTextView = v.findViewById(R.id.introTextView);
		progressBar = v.findViewById(R.id.progressBar);
		button = v.findViewById(R.id.fileButton);
		button.setOnClickListener(view -> {
			try {
				launcher.launch(viewModel.getFileName());
			} catch (ActivityNotFoundException e) {
				logException(LOG, WARNING, e);
				Toast.makeText(requireContext(),
						R.string.error_start_activity, LENGTH_LONG).show();
			}
		});

		viewModel.getOldTaskResumedEvent()
				.observeEvent(getViewLifecycleOwner(), this::onOldTaskResumed);
		viewModel.getState()
				.observe(getViewLifecycleOwner(), this::onStateChanged);

		// need to check for lost ViewModel state when creating with prior state
		if (savedInstanceState != null) checkForStateLoss = true;

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.removable_drive_title_send);
		hideViewOnSmallScreen(requireView().findViewById(R.id.imageView));
	}

	@Override
	public void onResume() {
		super.onResume();
		// This code gets called *after* launcher had a chance
		// to return the activity result.
		if (checkForStateLoss && viewModel.hasNoState()) {
			// We were recreated, but have lost the ViewModel state,
			// because our activity was destroyed.
			//
			// Remove the current fragment from the stack
			// to prevent duplicates on the back stack.
			getParentFragmentManager().popBackStack();
			// Start again (picks up existing task or allows to start a new one)
			// unless the activity was recreated after the app was killed
			if (viewModel.isAccountSignedIn()) viewModel.startSendData();
		}
	}

	private void onOldTaskResumed(boolean resumed) {
		if (resumed) {
			Toast.makeText(requireContext(),
					R.string.removable_drive_ongoing, LENGTH_LONG).show();
		}
	}

	private void onStateChanged(TransferDataState state) {
		if (state instanceof TransferDataState.NoDataToSend) {
			introTextView.setText(R.string.removable_drive_send_no_data);
			button.setEnabled(false);
		} else if (state instanceof TransferDataState.NotSupported) {
			introTextView.setText(R.string.removable_drive_send_not_supported);
			button.setEnabled(false);
		} else if (state instanceof TransferDataState.Ready) {
			button.setEnabled(true);
		} else if (state instanceof TransferDataState.TaskAvailable) {
			button.setEnabled(false);
			RemovableDriveTask.State s =
					((TransferDataState.TaskAvailable) state).state;
			if (s.getTotal() > 0L && progressBar.getVisibility() != VISIBLE) {
				progressBar.setVisibility(VISIBLE);
				progressBar.setMax(100);
			}
			int progress = s.getTotal() == 0 ? 0 : // no div by null
					(int) ((double) s.getDone() / s.getTotal() * 100);
			if (SDK_INT >= 24) {
				progressBar.setProgress(progress, true);
			} else {
				progressBar.setProgress(progress);
			}
		}
	}

	private void onDocumentCreated(@Nullable Uri uri) {
		if (uri == null) return;
		// we just got our document, so don't treat this as a state loss
		checkForStateLoss = false;
		viewModel.exportData(uri);
	}

}
