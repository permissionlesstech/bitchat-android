package org.briarproject.briar.android.contact;

import android.content.Context;

import org.briarproject.briar.android.util.BriarAdapter;

import javax.annotation.Nullable;

import androidx.annotation.NonNull;

import static org.briarproject.briar.android.util.UiUtils.getContactDisplayName;

public abstract class BaseContactListAdapter<I extends ContactItem, VH extends ContactItemViewHolder<I>>
		extends BriarAdapter<I, VH> {

	@Nullable
	protected final OnContactClickListener<I> listener;

	public BaseContactListAdapter(Context ctx, Class<I> c,
			@Nullable OnContactClickListener<I> listener) {
		super(ctx, c);
		this.listener = listener;
	}

	@Override
	public void onBindViewHolder(@NonNull VH ui, int position) {
		I item = items.get(position);
		ui.bind(item, listener);
	}

	@Override
	public int compare(I c1, I c2) {
		return getContactDisplayName(c1.getContact())
				.compareTo(getContactDisplayName(c2.getContact()));
	}

	@Override
	public boolean areItemsTheSame(I c1, I c2) {
		return c1.getContact().equals(c2.getContact());
	}

	@Override
	public boolean areContentsTheSame(ContactItem c1, ContactItem c2) {
		return true;
	}

}
