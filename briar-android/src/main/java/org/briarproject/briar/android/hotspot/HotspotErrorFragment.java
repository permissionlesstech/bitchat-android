package org.briarproject.briar.android.hotspot;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import static org.briarproject.briar.android.util.UiUtils.triggerFeedback;


@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class HotspotErrorFragment extends BaseFragment {

	public static final String TAG = HotspotErrorFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private static final String ERROR_MSG = "errorMessage";

	public static HotspotErrorFragment newInstance(String message) {
		HotspotErrorFragment f = new HotspotErrorFragment();
		Bundle args = new Bundle();
		args.putString(ERROR_MSG, message);
		f.setArguments(args);
		return f;
	}

	private String errorMessage;

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Bundle args = requireArguments();
		errorMessage = args.getString(ERROR_MSG);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.error);
		return inflater
				.inflate(R.layout.fragment_hotspot_error, container, false);
	}

	@Override
	public void onViewCreated(View v, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(v, savedInstanceState);
		TextView msg = v.findViewById(R.id.errorMessageDetail);
		msg.setText(errorMessage);

		Button feedbackButton = v.findViewById(R.id.feedbackButton);
		feedbackButton.setOnClickListener(
				button -> triggerFeedback(requireContext(), errorMessage));
	}

}
