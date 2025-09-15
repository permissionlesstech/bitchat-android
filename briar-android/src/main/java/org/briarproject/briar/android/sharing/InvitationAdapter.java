package org.briarproject.briar.android.sharing;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.briar.R;
import org.briarproject.briar.android.util.BriarAdapter;
import org.briarproject.briar.api.sharing.InvitationItem;

import androidx.annotation.NonNull;

public abstract class InvitationAdapter<I extends InvitationItem, VH extends InvitationViewHolder<I>>
		extends BriarAdapter<I, VH> {

	private final InvitationClickListener<I> listener;

	public InvitationAdapter(Context ctx, Class<I> c,
			InvitationClickListener<I> listener) {
		super(ctx, c);
		this.listener = listener;
	}

	protected View getView(ViewGroup parent) {
		return LayoutInflater.from(ctx)
				.inflate(R.layout.list_item_invitations, parent, false);
	}

	@Override
	public void onBindViewHolder(@NonNull VH ui, int position) {
		I item = getItemAt(position);
		if (item == null) return;
		ui.onBind(item, listener);
	}

	@Override
	public boolean areItemsTheSame(I oldItem, I newItem) {
		return oldItem.getShareable().equals(newItem.getShareable());
	}

	@Override
	public int compare(I o1, I o2) {
		return String.CASE_INSENSITIVE_ORDER
				.compare((o1.getShareable()).getName(),
						(o2.getShareable()).getName());
	}

	public interface InvitationClickListener<I> {
		void onItemClick(I item, boolean accept);
	}

}
