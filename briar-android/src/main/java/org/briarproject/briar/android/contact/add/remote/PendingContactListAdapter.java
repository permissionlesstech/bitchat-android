package org.briarproject.briar.android.contact.add.remote;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.briar.R;
import org.briarproject.briar.android.util.BriarAdapter;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
class PendingContactListAdapter extends
		BriarAdapter<PendingContactItem, PendingContactViewHolder> {

	private final PendingContactListener listener;

	PendingContactListAdapter(Context ctx, PendingContactListener listener,
			Class<PendingContactItem> c) {
		super(ctx, c);
		this.listener = listener;
	}

	@Override
	public PendingContactViewHolder onCreateViewHolder(ViewGroup viewGroup,
			int i) {
		View v = LayoutInflater.from(viewGroup.getContext()).inflate(
				R.layout.list_item_pending_contact, viewGroup, false);
		return new PendingContactViewHolder(v, listener);
	}

	@Override
	public void onBindViewHolder(
			PendingContactViewHolder pendingContactViewHolder, int i) {
		pendingContactViewHolder.bind(items.get(i));
	}

	@Override
	public int compare(PendingContactItem item1, PendingContactItem item2) {
		long timestamp1 = item1.getPendingContact().getTimestamp();
		long timestamp2 = item2.getPendingContact().getTimestamp();
		return Long.compare(timestamp1, timestamp2);
	}

	@Override
	public boolean areContentsTheSame(PendingContactItem item1,
			PendingContactItem item2) {
		PendingContact p1 = item1.getPendingContact();
		PendingContact p2 = item2.getPendingContact();
		return p1.getId().equals(p2.getId()) &&
				p1.getAlias().equals(p2.getAlias()) &&
				p1.getTimestamp() == p2.getTimestamp() &&
				item1.getState() == item2.getState();
	}

	@Override
	public boolean areItemsTheSame(PendingContactItem item1,
			PendingContactItem item2) {
		PendingContact p1 = item1.getPendingContact();
		PendingContact p2 = item2.getPendingContact();
		return p1.getId().equals(p2.getId());
	}

}
