package org.briarproject.briar.android.conversation;

import android.view.MotionEvent;
import android.view.View;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.recyclerview.selection.ItemDetailsLookup;
import androidx.recyclerview.widget.RecyclerView;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;

@NotNullByDefault
class ConversationItemDetailsLookup extends ItemDetailsLookup<String > {

	private final RecyclerView recyclerView;

	ConversationItemDetailsLookup(RecyclerView recyclerView) {
		this.recyclerView = recyclerView;
	}

	@Nullable
	@Override
	public ItemDetails<String> getItemDetails(MotionEvent e) {
		// find view that corresponds to MotionEvent
		View view = recyclerView.findChildViewUnder(e.getX(), e.getY());
		if (view == null) return null;

		// get position
		int pos = recyclerView.getChildAdapterPosition(view);
		if (pos == NO_POSITION) return null;

		// get key ID
		ConversationItemViewHolder holder =
				(ConversationItemViewHolder) recyclerView.getChildViewHolder(view);
		String id = holder.getItemKey();
		if (id == null) return null;

		return new ItemDetails<String>() {
			@Override
			public int getPosition() {
				return pos;
			}

			@Override
			public String getSelectionKey() {
				return id;
			}
		};
	}

}
