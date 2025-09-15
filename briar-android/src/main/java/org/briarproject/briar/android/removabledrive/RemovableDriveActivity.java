package org.briarproject.briar.android.removabledrive;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.plugin.file.RemovableDriveTask;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.fragment.FinalFragment;
import org.briarproject.briar.android.removabledrive.RemovableDriveViewModel.Action;
import org.briarproject.briar.android.removabledrive.TransferDataState.TaskAvailable;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import static java.util.Objects.requireNonNull;
import static org.briarproject.briar.android.conversation.ConversationActivity.CONTACT_ID;
import static org.briarproject.briar.android.util.UiUtils.showFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class RemovableDriveActivity extends BriarActivity {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private RemovableDriveViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);

		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(RemovableDriveViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Intent intent = requireNonNull(getIntent());
		int contactId = intent.getIntExtra(CONTACT_ID, -1);
		if (contactId == -1) throw new IllegalArgumentException("ContactId");
		viewModel.setContactId(new ContactId(contactId));

		setContentView(R.layout.activity_fragment_container);

		viewModel.getActionEvent().observeEvent(this, this::onActionReceived);
		viewModel.getState().observe(this, this::onStateChanged);

		if (savedInstanceState == null) {
			Fragment f = new ChooserFragment();
			String tag = ChooserFragment.TAG;
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.fragmentContainer, f, tag)
					.commit();
		}
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void onActionReceived(Action action) {
		Fragment f;
		String tag;
		if (action == Action.SEND) {
			f = new SendFragment();
			tag = SendFragment.TAG;
		} else if (action == Action.RECEIVE) {
			f = new ReceiveFragment();
			tag = ReceiveFragment.TAG;
		} else throw new AssertionError();
		showFragment(getSupportFragmentManager(), f, tag);
	}

	private void onStateChanged(TransferDataState state) {
		if (!(state instanceof TaskAvailable)) return;
		RemovableDriveTask.State s = ((TaskAvailable) state).state;
		if (s.isFinished()) {
			FragmentManager fm = getSupportFragmentManager();
			Action action;
			// We can't simply rely on viewModel.getActionEvent()
			// as that might have been destroyed in the meantime.
			if (fm.findFragmentByTag(SendFragment.TAG) != null) {
				action = Action.SEND;
			} else if (fm.findFragmentByTag(ReceiveFragment.TAG) != null) {
				action = Action.RECEIVE;
			} else {
				action = requireNonNull(
						viewModel.getActionEvent().getLastValue());
			}
			Fragment f;
			if (s.isSuccess()) f = getSuccessFragment(action);
			else f = getErrorFragment(action);
			showFragment(getSupportFragmentManager(), f, FinalFragment.TAG);
		}
	}

	private Fragment getSuccessFragment(Action action) {
		@StringRes int title, text;
		if (action == Action.SEND) {
			title = R.string.removable_drive_success_send_title;
			text = R.string.removable_drive_success_send_text;
		} else if (action == Action.RECEIVE) {
			title = R.string.removable_drive_success_receive_title;
			text = R.string.removable_drive_success_receive_text;
		} else throw new AssertionError();
		return FinalFragment.newInstance(title,
				R.drawable.ic_check_circle_outline, R.color.briar_brand_green,
				text);
	}

	private Fragment getErrorFragment(Action action) {
		@StringRes int title, text;
		if (action == Action.SEND) {
			title = R.string.removable_drive_error_send_title;
			text = R.string.removable_drive_error_send_text;
		} else if (action == Action.RECEIVE) {
			title = R.string.removable_drive_error_receive_title;
			text = R.string.removable_drive_error_receive_text;
		} else throw new AssertionError();
		return ErrorFragment.newInstance(title, text);
	}

}
