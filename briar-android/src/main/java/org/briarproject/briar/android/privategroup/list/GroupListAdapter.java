package org.briarproject.briar.android.privategroup.list;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.briar.R;
import org.briarproject.briar.android.privategroup.list.GroupViewHolder.OnGroupRemoveClickListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.recyclerview.widget.DiffUtil.ItemCallback;
import androidx.recyclerview.widget.ListAdapter;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class GroupListAdapter extends ListAdapter<GroupItem, GroupViewHolder> {

	private final OnGroupRemoveClickListener listener;

	GroupListAdapter(OnGroupRemoveClickListener listener) {
		super(new GroupItemCallback());
		this.listener = listener;
	}

	@Override
	public GroupViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_group, parent, false);
		return new GroupViewHolder(v);
	}

	@Override
	public void onBindViewHolder(GroupViewHolder ui, int position) {
		ui.bindView(getItem(position), listener);
	}

	private static class GroupItemCallback extends ItemCallback<GroupItem> {
		@Override
		public boolean areItemsTheSame(GroupItem a, GroupItem b) {
			return a.equals(b);
		}

		@Override
		public boolean areContentsTheSame(GroupItem a, GroupItem b) {
			return a.getMessageCount() == b.getMessageCount() &&
					a.getTimestamp() == b.getTimestamp() &&
					a.getUnreadCount() == b.getUnreadCount() &&
					a.isDissolved() == b.isDissolved();
		}
	}
}
