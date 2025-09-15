package org.briarproject.briar.android.threaded;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.util.ItemReturningAdapter;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;

@UiThread
@NotNullByDefault
public class ThreadItemAdapter<I extends ThreadItem>
		extends ListAdapter<I, BaseThreadItemViewHolder<I>>
		implements ItemReturningAdapter<I> {

	static final int UNDEFINED = -1;

	private final ThreadItemListener<I> listener;

	public ThreadItemAdapter(ThreadItemListener<I> listener) {
		super(new DiffUtil.ItemCallback<I>() {
			@Override
			public boolean areItemsTheSame(I a, I b) {
				return a.equals(b);
			}

			@Override
			public boolean areContentsTheSame(I a, I b) {
				return a.isHighlighted() == b.isHighlighted() &&
						a.isRead() == b.isRead();
			}
		});
		this.listener = listener;
	}

	@NonNull
	@Override
	public BaseThreadItemViewHolder<I> onCreateViewHolder(@NonNull
			ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_thread, parent, false);
		return new ThreadPostViewHolder<>(v);
	}

	@Override
	public void onBindViewHolder(@NonNull BaseThreadItemViewHolder<I> ui,
			int position) {
		I item = getItem(position);
		ui.bind(item, listener);
	}

	int findItemPosition(MessageId id) {
		for (int i = 0; i < getItemCount(); i++) {
			if (id.equals(getItem(i).getId())) return i;
		}
		return NO_POSITION; // Not found
	}

	/**
	 * Highlights the item with the given {@link MessageId}
	 * and disables the highlight for a previously highlighted item, if any.
	 * <p>
	 * Only one item can be highlighted at a time.
	 */
	void setHighlightedItem(@Nullable MessageId id) {
		for (int i = 0; i < getItemCount(); i++) {
			I item = getItem(i);
			if (item.getId().equals(id)) {
				item.setHighlighted(true);
				notifyItemChanged(i, item);
			} else if (item.isHighlighted()) {
				item.setHighlighted(false);
				notifyItemChanged(i, item);
			}
		}
	}

	@Nullable
	I getHighlightedItem() {
		for (I item : getCurrentList()) {
			if (item.isHighlighted()) return item;
		}
		return null;
	}

	@Nullable
	MessageId getFirstVisibleMessageId(LinearLayoutManager layoutManager) {
		int position = layoutManager.findFirstVisibleItemPosition();
		if (position == NO_POSITION) return null;
		return getItemAt(position).getId();
	}

	/**
	 * Returns the position of the first unread item below the current viewport
	 */
	int getVisibleUnreadPosBottom(LinearLayoutManager layoutManager) {
		int positionBottom = layoutManager.findLastVisibleItemPosition();
		if (positionBottom == NO_POSITION) return NO_POSITION;
		for (int i = positionBottom + 1; i < getItemCount(); i++) {
			if (!getItem(i).isRead()) return i;
		}
		return NO_POSITION;
	}

	/**
	 * Returns the position of the first unread item above the current viewport
	 */
	int getVisibleUnreadPosTop(LinearLayoutManager layoutManager) {
		int positionTop = layoutManager.findFirstVisibleItemPosition();
		int position = NO_POSITION;
		for (int i = 0; i < getItemCount(); i++) {
			if (i < positionTop && !getItem(i).isRead()) {
				position = i;
			} else if (i >= positionTop) {
				return position;
			}
		}
		return NO_POSITION;
	}

	@Override
	public I getItemAt(int position) {
		return getItem(position);
	}

	public interface ThreadItemListener<I> {
		void onReplyClick(I item);
		void onLinkClick(String url);
	}

}
