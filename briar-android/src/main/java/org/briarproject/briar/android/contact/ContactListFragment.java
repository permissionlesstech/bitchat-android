package org.briarproject.briar.android.contact;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.contact.add.nearby.AddNearbyContactActivity;
import org.briarproject.briar.android.contact.add.remote.AddContactActivity;
import org.briarproject.briar.android.contact.add.remote.PendingContactListActivity;
import org.briarproject.briar.android.conversation.ConversationActivity;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.util.BriarSnackbarBuilder;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import io.github.kobakei.materialfabspeeddial.FabSpeedDial;
import io.github.kobakei.materialfabspeeddial.FabSpeedDial.OnMenuItemClickListener;

import static com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_INDEFINITE;
import static org.briarproject.briar.android.conversation.ConversationActivity.CONTACT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ContactListFragment extends BaseFragment
		implements OnMenuItemClickListener,
		OnContactClickListener<ContactListItem> {

	public static final String TAG = ContactListFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ContactListViewModel viewModel;
	private final ContactListAdapter adapter = new ContactListAdapter(this);
	private BriarRecyclerView list;
	private FabSpeedDial speedDial;

	/**
	 * The Snackbar is non-null when shown and null otherwise.
	 * Use {@link #showSnackBar()} and {@link #dismissSnackBar()} to interact.
	 */
	@Nullable
	private Snackbar snackbar = null;

	public static ContactListFragment newInstance() {
		Bundle args = new Bundle();
		ContactListFragment fragment = new ContactListFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ContactListViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.contact_list_button);

		View contentView = inflater.inflate(R.layout.fragment_contact_list,
				container, false);

		speedDial = contentView.findViewById(R.id.speedDial);
		speedDial.addOnMenuItemClickListener(this);

		list = contentView.findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		list.setAdapter(adapter);
		list.setEmptyImage(R.drawable.il_empty_state_contact_list);
		list.setEmptyText(getString(R.string.no_contacts));
		list.setEmptyAction(getString(R.string.no_contacts_action));

		viewModel.getContactListItems()
				.observe(getViewLifecycleOwner(), result -> {
					result.onError(this::handleException).onSuccess(items -> {
						adapter.submitList(items);
					});
				});
		viewModel.getHasPendingContacts()
				.observe(getViewLifecycleOwner(), hasPending -> {
					if (hasPending) showSnackBar();
					else dismissSnackBar();
				});

		return contentView;
	}

	@Override
	public void onItemClick(View view, ContactListItem item) {
		Intent i = new Intent(getActivity(), ConversationActivity.class);
		ContactId contactId = item.getContact().getId();
		i.putExtra(CONTACT_ID, contactId.getInt());
		startActivity(i);
	}

	@Override
	public void onMenuItemClick(FloatingActionButton fab, @Nullable TextView v,
			int itemId) {
		if (itemId == R.id.action_add_contact_nearby) {
			Intent intent =
					new Intent(getContext(), AddNearbyContactActivity.class);
			startActivity(intent);
		} else if (itemId == R.id.action_add_contact_remotely) {
			startActivity(new Intent(getContext(), AddContactActivity.class));
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.clearAllContactNotifications();
		viewModel.clearAllContactAddedNotifications();
		viewModel.loadContacts();
		viewModel.checkForPendingContacts();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		list.stopPeriodicUpdate();
		dismissSnackBar();
		// Close the speed dial to prevent a crash (#1672)
		speedDial.closeMenu();
	}

	@UiThread
	private void showSnackBar() {
		if (snackbar != null) return;
		View v = requireView();
		int stringRes = R.string.pending_contact_requests_snackbar;
		snackbar = new BriarSnackbarBuilder()
				.setAction(R.string.show, view -> showPendingContactList())
				.make(v, stringRes, LENGTH_INDEFINITE);
		snackbar.show();
	}

	@UiThread
	private void dismissSnackBar() {
		if (snackbar == null) return;
		snackbar.dismiss();
		snackbar = null;
	}

	private void showPendingContactList() {
		Intent i = new Intent(getContext(), PendingContactListActivity.class);
		startActivity(i);
	}
}
