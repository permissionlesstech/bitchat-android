package org.briarproject.briar.android.conversation;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.widget.OnboardingFullDialogFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import static java.util.logging.Level.INFO;
import static org.briarproject.briar.android.conversation.ConversationActivity.CONTACT_ID;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConversationSettingsDialog extends DialogFragment {

	final static String TAG = ConversationSettingsDialog.class.getName();

	private static final Logger LOG = Logger.getLogger(TAG);

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ConversationViewModel viewModel;

	static ConversationSettingsDialog newInstance(ContactId contactId) {
		Bundle args = new Bundle();
		args.putInt(CONTACT_ID, contactId.getInt());
		ConversationSettingsDialog dialog = new ConversationSettingsDialog();
		dialog.setArguments(args);
		return dialog;
	}

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		injectFragment(((BaseFragment.BaseFragmentListener) context)
				.getActivityComponent());
	}

	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(ConversationViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setStyle(DialogFragment.STYLE_NO_FRAME,
				R.style.BriarFullScreenDialogTheme);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_conversation_settings,
				container, false);

		Bundle args = requireArguments();
		int id = args.getInt(CONTACT_ID, -1);
		if (id == -1) throw new IllegalStateException();
		ContactId contactId = new ContactId(id);

		FragmentActivity activity = requireActivity();
		viewModel.setContactId(contactId);

		Toolbar toolbar = view.findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> dismiss());

		SwitchCompat switchDisappearingMessages = view.findViewById(
				R.id.switchDisappearingMessages);
		switchDisappearingMessages.setOnCheckedChangeListener(
				(button, value) -> viewModel.setAutoDeleteTimerEnabled(value));

		Button buttonLearnMore =
				view.findViewById(R.id.buttonLearnMore);
		buttonLearnMore.setOnClickListener(e -> showLearnMoreDialog());

		viewModel.getAutoDeleteTimer()
				.observe(getViewLifecycleOwner(), timer -> {
					if (LOG.isLoggable(INFO)) {
						LOG.info("Received auto delete timer: " + timer);
					}
					boolean disappearingMessages =
							timer != NO_AUTO_DELETE_TIMER;
					switchDisappearingMessages
							.setChecked(disappearingMessages);
					switchDisappearingMessages.setEnabled(true);
				});

		return view;
	}

	private void showLearnMoreDialog() {
		OnboardingFullDialogFragment.newInstance(
				R.string.disappearing_messages_title,
				R.string.disappearing_messages_explanation_long
		).show(getChildFragmentManager(), OnboardingFullDialogFragment.TAG);
	}

}
