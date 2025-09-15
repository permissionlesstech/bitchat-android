package org.briarproject.briar.android.forum;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.DiffUtil.ItemCallback;
import androidx.recyclerview.widget.ListAdapter;

@NotNullByDefault
class ForumListAdapter extends ListAdapter<ForumListItem, ForumViewHolder> {

	private final ForumListViewModel viewModel;

	ForumListAdapter(ForumListViewModel viewModel) {
		super(new ForumListCallback());
		this.viewModel = viewModel;
	}

	@Override
	public ForumViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(
				R.layout.list_item_forum, parent, false);
		return new ForumViewHolder(v, viewModel);
	}

	@Override
	public void onBindViewHolder(ForumViewHolder viewHolder, int position) {
		viewHolder.bind(getItem(position));
	}

	@NotNullByDefault
	private static class ForumListCallback extends ItemCallback<ForumListItem> {
		@Override
		public boolean areItemsTheSame(ForumListItem a, ForumListItem b) {
			return a.equals(b);
		}

		@Override
		public boolean areContentsTheSame(ForumListItem a, ForumListItem b) {
			return a.isEmpty() == b.isEmpty() &&
					a.getTimestamp() == b.getTimestamp() &&
					a.getUnreadCount() == b.getUnreadCount();
		}
	}

}
