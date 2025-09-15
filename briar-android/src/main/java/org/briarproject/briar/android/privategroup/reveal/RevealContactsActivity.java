package org.briarproject.briar.android.privategroup.reveal;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.contactselection.ContactSelectorActivity;
import org.briarproject.briar.android.controller.handler.UiExceptionHandler;
import org.briarproject.briar.android.controller.handler.UiResultExceptionHandler;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.LayoutRes;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class RevealContactsActivity extends ContactSelectorActivity
		implements OnClickListener {

	private Button button;

	@Inject
	RevealContactsController controller;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	@SuppressWarnings("ConstantConditions")
	public void onCreate(@Nullable Bundle bundle) {
		super.onCreate(bundle);

		Intent i = getIntent();
		byte[] b = i.getByteArrayExtra(GROUP_ID);
		if (b == null) throw new IllegalStateException("No GroupId");
		groupId = new GroupId(b);

		button = findViewById(R.id.revealButton);
		button.setOnClickListener(this);
		button.setEnabled(false);

		if (bundle == null) {
			showInitialFragment(RevealContactsFragment.newInstance(groupId));
		}
	}

	@Override
	@LayoutRes
	protected int getLayout() {
		return R.layout.activity_reveal_contacts;
	}

	@Override
	public void onStart() {
		super.onStart();
		controller.isOnboardingNeeded(
				new UiResultExceptionHandler<Boolean, DbException>(this) {
					@Override
					public void onResultUi(Boolean show) {
						if (show) showOnboardingDialog();
					}

					@Override
					public void onExceptionUi(DbException exception) {
						handleException(exception);
					}
				});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.group_reveal_actions, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_group_reveal_onboarding) {
			showOnboardingDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showOnboardingDialog() {
		new MaterialAlertDialogBuilder(this,
				R.style.OnboardingDialogTheme)
				.setMessage(getString(R.string.groups_reveal_dialog_message))
				.setNeutralButton(R.string.got_it,
						(dialog, which) -> dialog.cancel())
				.setOnCancelListener(dialog -> onboardingShown())
				.show();
	}

	private void onboardingShown() {
		controller.onboardingShown(
				new UiExceptionHandler<DbException>(this) {
					@Override
					public void onExceptionUi(DbException exception) {
						handleException(exception);
					}
				});
	}

	@Override
	public void contactsSelected(Collection<ContactId> contacts) {
		super.contactsSelected(contacts);
		button.setEnabled(!contacts.isEmpty());
	}

	@Override
	public void onClick(View v) {
		controller.reveal(groupId, contacts,
				new UiExceptionHandler<DbException>(this) {
					@Override
					public void onExceptionUi(DbException exception) {
						handleException(exception);
					}
				});
		supportFinishAfterTransition();
	}

}
