package org.briarproject.briar.android.privategroup.list;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.google.android.material.snackbar.Snackbar;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.privategroup.creation.CreateGroupActivity;
import org.briarproject.briar.android.privategroup.invitation.GroupInvitationActivity;
import org.briarproject.briar.android.privategroup.list.GroupViewHolder.OnGroupRemoveClickListener;
import org.briarproject.briar.android.util.BriarSnackbarBuilder;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE;
import static java.util.Objects.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupListFragment extends BaseFragment implements
		OnGroupRemoveClickListener, OnClickListener {

	public final static String TAG = GroupListFragment.class.getName();

	public static GroupListFragment newInstance() {
		return new GroupListFragment();
	}

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private GroupListViewModel viewModel;
	private BriarRecyclerView list;
	private GroupListAdapter adapter;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(GroupListViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {

		requireActivity().setTitle(R.string.groups_button);

		View v = inflater.inflate(R.layout.list, container, false);

		adapter = new GroupListAdapter(this);
		list = v.findViewById(R.id.list);
		list.setEmptyImage(R.drawable.il_empty_state_group_list);
		list.setEmptyText(R.string.groups_list_empty);
		list.setEmptyAction(R.string.groups_list_empty_action);
		list.setLayoutManager(new LinearLayoutManager(getContext()));
		list.setAdapter(adapter);
		viewModel.getGroupItems().observe(getViewLifecycleOwner(), result ->
				result.onError(this::handleException).onSuccess(items -> {
					adapter.submitList(items);
					if (requireNonNull(items).size() == 0) list.showData();
				})
		);

		Snackbar snackbar = new BriarSnackbarBuilder()
				.setAction(R.string.show, this)
				.make(list, "", LENGTH_INDEFINITE);
		viewModel.getNumInvitations().observe(getViewLifecycleOwner(), num -> {
			if (num == 0) {
				snackbar.dismiss();
			} else {
				snackbar.setText(getResources().getQuantityString(
						R.plurals.groups_invitations_open, num, num));
				if (!snackbar.isShownOrQueued()) snackbar.show();
			}
		});

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.blockAllGroupMessageNotifications();
		viewModel.clearAllGroupMessageNotifications();
		// The attributes and sorting of the groups may have changed while we
		// were stopped and we have no way finding out about them, so re-load
		// e.g. less unread messages in a group after viewing it.
		viewModel.loadGroups();
		// The number of invitations might have changed while we were stopped
		// e.g. because of accepting an invitation which does not trigger event
		viewModel.loadNumInvitations();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		list.stopPeriodicUpdate();
		viewModel.unblockAllGroupMessageNotifications();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.groups_list_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_add_group) {
			Intent i = new Intent(getContext(), CreateGroupActivity.class);
			startActivity(i);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@UiThread
	@Override
	public void onGroupRemoveClick(GroupItem item) {
		viewModel.removeGroup(item.getId());
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	/**
	 * This method is handling the available groups snackbar action
	 */
	@Override
	public void onClick(View v) {
		Intent i = new Intent(getContext(), GroupInvitationActivity.class);
		startActivity(i);
	}

}
