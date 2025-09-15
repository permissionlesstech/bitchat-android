package org.briarproject.briar.android.mailbox;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import com.google.android.material.animation.ArgbEvaluatorCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.briar.R;
import org.briarproject.briar.android.view.BriarButton;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.transition.AutoTransition;
import androidx.transition.Transition;

import static android.view.View.FOCUS_DOWN;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.core.content.ContextCompat.getColor;
import static androidx.transition.TransitionManager.beginDelayedTransition;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ErrorWizardFragment extends Fragment {

	static final String TAG = ErrorWizardFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private MailboxViewModel viewModel;
	private ScrollView scrollView;
	private ValueAnimator colorAnim;
	private final Transition transition = new AutoTransition();

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
		View v = inflater.inflate(R.layout.fragment_mailbox_error_wizard,
				container, false);
		scrollView = (ScrollView) v;

		int startColor =
				getColor(v.getContext(), R.color.briar_accent);
		int endColor = getColor(v.getContext(), R.color.window_background);
		colorAnim = ValueAnimator
				.ofObject(new ArgbEvaluatorCompat(), startColor, endColor);
		colorAnim.setDuration(2500);

		return v;
	}

	@Override
	public void onViewCreated(View v, @Nullable Bundle savedInstanceState) {
		RadioGroup radioGroup1 = v.findViewById(R.id.radioGroup1);
		List<RadioButton> radioButtons1 = new ArrayList<>(3);
		radioButtons1.add(v.findViewById(R.id.radioButton1));
		radioButtons1.add(v.findViewById(R.id.radioButton2));
		radioButtons1.add(v.findViewById(R.id.radioButton3));
		List<View> views1 = new ArrayList<>(3);
		View info1 = v.findViewById(R.id.info1);
		views1.add(info1);
		views1.add(v.findViewById(R.id.info2));
		View info3 = v.findViewById(R.id.info3);
		views1.add(info3);
		setUpRadioGroup(radioGroup1, radioButtons1, views1);

		RadioGroup radioGroup1_1 = info1.findViewById(R.id.radioGroup1_1);
		List<RadioButton> radioButtons1_1 = new ArrayList<>(3);
		radioButtons1_1.add(info1.findViewById(R.id.radioButton1_1));
		radioButtons1_1.add(info1.findViewById(R.id.radioButton1_2));
		radioButtons1_1.add(info1.findViewById(R.id.radioButton1_3));
		radioButtons1_1.add(info1.findViewById(R.id.radioButton1_4));
		List<View> views1_1 = new ArrayList<>(3);
		views1_1.add(info1.findViewById(R.id.info1_1_1));
		views1_1.add(info1.findViewById(R.id.info1_1_2));
		views1_1.add(info1.findViewById(R.id.info1_1_3));
		views1_1.add(info1.findViewById(R.id.info1_1_4));
		setUpRadioGroup(radioGroup1_1, radioButtons1_1, views1_1);

		// set up unlink buttons
		BriarButton button3 = info3.findViewById(R.id.button3);
		BriarButton button1_1_1 = info1.findViewById(R.id.button1_1_1);
		BriarButton button1_1_2 = info1.findViewById(R.id.button1_1_2);
		button3.setOnClickListener(this::onUnlinkButtonClicked);
		button1_1_1.setOnClickListener(this::onUnlinkButtonClicked);
		button1_1_2.setOnClickListener(this::onUnlinkButtonClicked);

		// set up check connection button
		BriarButton button1_1_3 = info1.findViewById(R.id.button1_1_3);
		button1_1_3.setOnClickListener(this::onCheckConnectionButtonClicked);
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.mailbox_error_wizard_title);
	}

	private void setUpRadioGroup(RadioGroup radioGroup,
			List<RadioButton> radioButtons, List<View> views) {
		if (radioButtons.size() != views.size()) {
			throw new IllegalArgumentException();
		}
		radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
			onCheckedChanged();
			for (int i = 0; i < radioButtons.size(); i++) {
				RadioButton radioButton = radioButtons.get(i);
				View view = views.get(i);
				if (checkedId == radioButton.getId()) {
					animateColor(view);
					view.setVisibility(VISIBLE);
				} else {
					view.setVisibility(GONE);
				}
			}
		});
	}

	private void onUnlinkButtonClicked(View v) {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.BriarDialogTheme);
		builder.setTitle(R.string.mailbox_status_unlink_dialog_title);
		builder.setMessage(R.string.mailbox_status_unlink_dialog_question);
		builder.setPositiveButton(R.string.cancel,
				(dialog, which) -> dialog.cancel());
		builder.setNegativeButton(R.string.mailbox_status_unlink_button,
				(dialog, which) -> viewModel.unlink());
		builder.setOnCancelListener(dialog -> ((BriarButton) v).reset());
		builder.show();
	}

	private void onCheckConnectionButtonClicked(View v) {
		viewModel.checkConnectionFromWizard();
	}

	private void animateColor(View v) {
		if (colorAnim.isRunning()) colorAnim.end();
		colorAnim.removeAllUpdateListeners();
		colorAnim.addUpdateListener(animation ->
				v.setBackgroundColor((int) animation.getAnimatedValue())
		);
		colorAnim.start();
	}

	private void onCheckedChanged() {
		transition.addListener(new Transition.TransitionListener() {
			@Override
			public void onTransitionStart(@NonNull Transition transition) {
			}

			@Override
			public void onTransitionEnd(@NonNull Transition transition) {
				scrollView.fullScroll(FOCUS_DOWN);
			}

			@Override
			public void onTransitionCancel(@NonNull Transition transition) {
			}

			@Override
			public void onTransitionPause(@NonNull Transition transition) {
			}

			@Override
			public void onTransitionResume(@NonNull Transition transition) {
			}
		});
		beginDelayedTransition(scrollView, transition);
	}

}
