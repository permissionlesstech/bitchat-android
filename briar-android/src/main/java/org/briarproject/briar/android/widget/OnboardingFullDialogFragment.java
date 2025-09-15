package org.briarproject.briar.android.widget;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.DialogFragment;

@NotNullByDefault
public class OnboardingFullDialogFragment extends DialogFragment {

	public final static String TAG =
			OnboardingFullDialogFragment.class.getName();

	private final static String RES_TITLE = "resTitle";
	private final static String RES_CONTENT = "resContent";

	public static OnboardingFullDialogFragment newInstance(@StringRes int title,
			@StringRes int content) {
		Bundle args = new Bundle();
		args.putInt(RES_TITLE, title);
		args.putInt(RES_CONTENT, content);
		OnboardingFullDialogFragment f = new OnboardingFullDialogFragment();
		f.setArguments(args);
		return f;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setStyle(DialogFragment.STYLE_NORMAL,
				R.style.BriarFullScreenDialogTheme);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_onboarding_full,
				container, false);

		Bundle args = requireArguments();

		Toolbar toolbar = view.findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> dismiss());
		toolbar.setTitle(args.getInt(RES_TITLE));

		TextView contentView = view.findViewById(R.id.contentView);
		contentView.setText(args.getInt(RES_CONTENT));

		view.findViewById(R.id.button).setOnClickListener(v -> dismiss());

		return view;
	}

}
