package org.briarproject.briar.android.contact.add.remote;

import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.briarproject.bramble.api.UnsupportedVersionException;
import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.db.ContactExistsException;
import org.briarproject.bramble.api.db.PendingContactExistsException;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.view.BriarButton;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import static android.widget.Toast.LENGTH_LONG;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.StringUtils.utf8IsTooLong;
import static org.briarproject.briar.android.util.UiUtils.getDialogIcon;
import static org.briarproject.briar.android.util.UiUtils.hideViewOnSmallScreen;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class NicknameFragment extends BaseFragment {

	private static final String TAG = NicknameFragment.class.getName();
	private static final String SAVED_LINK = "savedLink";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private AddContactViewModel viewModel;

	private TextInputLayout contactNameLayout;
	private TextInputEditText contactNameInput;
	private BriarButton addButton;

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(AddContactViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			// When the activity (and the ViewModel) get destroyed,
			// the link will not be available anymore and needs to be restored.
			// TODO migrate to SavedStateViewModelFactory (once we can use it)
			String savedLink = savedInstanceState.getString(SAVED_LINK);
			if (savedLink != null) viewModel.setRemoteHandshakeLink(savedLink);
		}
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_nickname,
				container, false);

		contactNameLayout = v.findViewById(R.id.contactNameLayout);
		contactNameInput = v.findViewById(R.id.contactNameInput);

		addButton = v.findViewById(R.id.addButton);
		addButton.setOnClickListener(view -> onAddButtonClicked());

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		hideViewOnSmallScreen(requireView().findViewById(R.id.imageView));
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString(SAVED_LINK, viewModel.getRemoteHandshakeLink());
	}

	@Nullable
	private String getNicknameOrNull() {
		Editable text = contactNameInput.getText();
		if (text == null || text.toString().trim().isEmpty()) {
			contactNameLayout.setError(getString(R.string.nickname_missing));
			contactNameInput.requestFocus();
			return null;
		}
		String name = text.toString().trim();
		if (utf8IsTooLong(name, MAX_AUTHOR_NAME_LENGTH)) {
			contactNameLayout.setError(getString(R.string.name_too_long));
			contactNameInput.requestFocus();
			return null;
		}
		contactNameLayout.setError(null);
		return name;
	}

	private void onAddButtonClicked() {
		String name = getNicknameOrNull();
		if (name == null) { // invalid nickname
			addButton.reset();
			return;
		}

		LifecycleOwner owner = getViewLifecycleOwner();
		viewModel.getAddContactResult().observe(owner, result -> {
			if (result == null) return;
			if (result.hasError())
				handleException(name, requireNonNull(result.getException()));
			else
				showPendingContactListActivity();
		});
		viewModel.addContact(name);
	}

	private void showPendingContactListActivity() {
		Intent intent = new Intent(getActivity(),
				PendingContactListActivity.class);
		startActivity(intent);
		finish();
	}

	private void handleException(String name, Exception e) {
		if (e instanceof ContactExistsException) {
			ContactExistsException ce = (ContactExistsException) e;
			handleExistingContact(name, ce.getRemoteAuthor());
		} else if (e instanceof PendingContactExistsException) {
			PendingContactExistsException pe =
					(PendingContactExistsException) e;
			handleExistingPendingContact(name, pe.getPendingContact());
		} else if (e instanceof UnsupportedVersionException) {
			int stringRes = R.string.unsupported_link;
			Toast.makeText(getContext(), stringRes, LENGTH_LONG).show();
			finish();
		} else {
			int stringRes = R.string.adding_contact_error;
			Toast.makeText(getContext(), stringRes, LENGTH_LONG).show();
			finish();
		}
	}

	private void handleExistingContact(String name, Author existing) {
		OnClickListener listener = (d, w) -> {
			d.dismiss();
			String str = getString(R.string.contact_already_exists_general);
			Toast.makeText(getContext(), str, LENGTH_LONG).show();
			finish();
		};
		showSameLinkDialog(existing.getName(), name,
				R.string.duplicate_link_dialog_text_1_contact, listener);
	}

	private void handleExistingPendingContact(String name, PendingContact p) {
		OnClickListener listener = (d, w) -> {
			viewModel.updatePendingContact(name, p);
			Toast.makeText(getContext(), R.string.pending_contact_updated_toast,
					LENGTH_LONG).show();
			d.dismiss();
			showPendingContactListActivity();
		};
		showSameLinkDialog(p.getAlias(), name,
				R.string.duplicate_link_dialog_text_1, listener);
	}

	private void showSameLinkDialog(String name1, String name2,
			@StringRes int existsRes, OnClickListener samePersonListener) {
		Context ctx = requireContext();
		Builder b = new Builder(ctx, R.style.BriarDialogTheme_Neutral);
		b.setTitle(getString(R.string.duplicate_link_dialog_title));
		String msg = getString(existsRes, name1) + "\n\n" +
				getString(R.string.duplicate_link_dialog_text_2, name2, name1);
		b.setMessage(msg);
		b.setPositiveButton(R.string.same_person_button, samePersonListener);
		b.setNegativeButton(R.string.different_person_button, (d, w) -> {
			d.dismiss();
			showWarningDialog(name1, name2);
		});
		b.setCancelable(false);
		b.show();
	}

	private void showWarningDialog(String name1, String name2) {
		Context ctx = requireContext();
		Builder b = new Builder(ctx, R.style.BriarDialogTheme);
		b.setIcon(getDialogIcon(ctx, R.drawable.alerts_and_states_error));
		b.setTitle(getString(R.string.duplicate_link_dialog_title));
		b.setMessage(
				getString(R.string.duplicate_link_dialog_text_3, name1, name2));
		b.setPositiveButton(R.string.ok, (d, w) -> {
			d.dismiss();
			finish();
		});
		b.setCancelable(false);
		b.show();
	}

}
