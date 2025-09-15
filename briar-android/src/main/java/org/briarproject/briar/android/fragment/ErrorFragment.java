package org.briarproject.briar.android.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.annotation.Nullable;


@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ErrorFragment extends BaseFragment {

	public static final String TAG = ErrorFragment.class.getName();

	private static final String ERROR_MSG = "errorMessage";

	public static ErrorFragment newInstance(String message) {
		ErrorFragment f = new ErrorFragment();
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
		View v = inflater
				.inflate(R.layout.fragment_error, container, false);
		TextView msg = v.findViewById(R.id.errorMessage);
		msg.setText(errorMessage);
		return v;
	}

}
