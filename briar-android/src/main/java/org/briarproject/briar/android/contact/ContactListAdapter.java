package org.briarproject.briar.android.contact;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.nullsafety.NullSafety;

import androidx.recyclerview.widget.DiffUtil.ItemCallback;
import androidx.recyclerview.widget.ListAdapter;

@NotNullByDefault
public class ContactListAdapter extends
		ListAdapter<ContactListItem, ContactListItemViewHolder> {

	protected final OnContactClickListener<ContactListItem> listener;

	public ContactListAdapter(
			OnContactClickListener<ContactListItem> listener) {
		super(new ContactListCallback());
		this.listener = listener;
	}

	@NotNullByDefault
	private static class ContactListCallback
			extends ItemCallback<ContactListItem> {
		@Override
		public boolean areItemsTheSame(ContactListItem c1, ContactListItem c2) {
			return c1.getContact().equals(c2.getContact());
		}

		@Override
		public boolean areContentsTheSame(ContactListItem c1,
				ContactListItem c2) {
			// check for all properties that influence visual
			// representation of contact
			if (c1.isEmpty() != c2.isEmpty()) {
				return false;
			}
			if (c1.getUnreadCount() != c2.getUnreadCount()) {
				return false;
			}
			if (c1.getTimestamp() != c2.getTimestamp()) {
				return false;
			}
			if (c1.isConnected() != c2.isConnected()) {
				return false;
			}
			if (!NullSafety.equals(c1.getContact().getAlias(),
					c2.getContact().getAlias())) {
				return false;
			}
			return NullSafety.equals(c1.getAuthorInfo().getAvatarHeader(),
					c2.getAuthorInfo().getAvatarHeader());
		}
	}

	@Override
	public ContactListItemViewHolder onCreateViewHolder(ViewGroup viewGroup,
			int viewType) {
		View v = LayoutInflater.from(viewGroup.getContext()).inflate(
				R.layout.list_item_contact, viewGroup, false);
		return new ContactListItemViewHolder(v);
	}

	@Override
	public void onBindViewHolder(ContactListItemViewHolder viewHolder,
			int position) {
		viewHolder.bind(getItem(position), listener);
	}
}
