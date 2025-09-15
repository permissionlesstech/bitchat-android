package org.briarproject.briar.android.fragment;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import static android.view.View.GONE;
import static androidx.core.widget.ImageViewCompat.setImageTintList;
import static org.briarproject.briar.android.util.UiUtils.hideViewOnSmallScreen;

/**
 * A fragment to be used at the end of a user flow
 * where the user should not have the option to go back.
 * Here, we only show final information
 * before finishing the related activity.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class FinalFragment extends Fragment {

	public static final String TAG = FinalFragment.class.getName();

	public static final String ARG_TITLE = "title";
	public static final String ARG_ICON = "icon";
	public static final String ARG_ICON_TINT = "iconTint";
	public static final String ARG_TEXT = "text";

	public static FinalFragment newInstance(
			@StringRes int title,
			@DrawableRes int icon,
			@ColorRes int iconTint,
			@StringRes int text) {
		FinalFragment f = new FinalFragment();
		Bundle args = new Bundle();
		args.putInt(ARG_TITLE, title);
		args.putInt(ARG_ICON, icon);
		args.putInt(ARG_ICON_TINT, iconTint);
		args.putInt(ARG_TEXT, text);
		f.setArguments(args);
		return f;
	}

	protected Button buttonView;
	protected final OnBackPressedCallback onBackPressedCallback =
			new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					onBackButtonPressed();
				}
			};

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater
				.inflate(R.layout.fragment_final, container, false);

		ImageView iconView = v.findViewById(R.id.iconView);
		TextView titleView = v.findViewById(R.id.titleView);
		TextView textView = v.findViewById(R.id.textView);
		buttonView = v.findViewById(R.id.button);

		Bundle args = requireArguments();
		titleView.setText(args.getInt(ARG_TITLE));
		iconView.setImageResource(args.getInt(ARG_ICON));
		int tintRes = args.getInt(ARG_ICON_TINT);
		if (tintRes != 0) {
			int color = getResources().getColor(tintRes);
			ColorStateList tint = ColorStateList.valueOf(color);
			setImageTintList(iconView, tint);
		}
		int textRes = args.getInt(ARG_TEXT);
		if (textRes == 0) {
			textView.setVisibility(GONE);
		} else {
			textView.setText(textRes);
		}

		buttonView.setOnClickListener(view -> onBackButtonPressed());

		AppCompatActivity a = (AppCompatActivity) requireActivity();
		a.setTitle(args.getInt(ARG_TITLE));
		a.getOnBackPressedDispatcher()
				.addCallback(getViewLifecycleOwner(), onBackPressedCallback);
		return v;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		// onAttach(Activity) is deprecated, we are told to cast the context
		AppCompatActivity a = (AppCompatActivity) context;
		ActionBar actionBar = a.getSupportActionBar();
		if (shouldHideActionBarBackButton() && actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(false);
			actionBar.setHomeButtonEnabled(false);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		hideViewOnSmallScreen(requireView().findViewById(R.id.iconView));
	}

	@Override
	public void onDetach() {
		AppCompatActivity a = (AppCompatActivity) requireActivity();
		ActionBar actionBar = a.getSupportActionBar();
		if (shouldHideActionBarBackButton() && actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
			actionBar.setHomeButtonEnabled(true);
		}
		super.onDetach();
	}

	/**
	 * This is the action that the system back button
	 * and the button at the bottom will perform.
	 */
	protected void onBackButtonPressed() {
		requireActivity().supportFinishAfterTransition();
	}

	/**
	 * If you are overriding {@link #onBackButtonPressed()}
	 * and are no longer finishing the fragment, return false here.
	 * Otherwise the ActionBar back button will be missing in your activity.
	 */
	protected boolean shouldHideActionBarBackButton() {
		return true;
	}

}
