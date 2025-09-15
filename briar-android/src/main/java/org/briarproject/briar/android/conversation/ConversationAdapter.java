package org.briarproject.briar.android.conversation;

import android.content.Context;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.util.BriarAdapter;
import org.briarproject.briar.android.util.ItemReturningAdapter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@NotNullByDefault
class ConversationAdapter
		extends BriarAdapter<ConversationItem, ConversationItemViewHolder>
		implements ItemReturningAdapter<ConversationItem> {

	private final ConversationListener listener;
	private final RecycledViewPool imageViewPool;
	private final ImageItemDecoration imageItemDecoration;
	@Nullable
	private SelectionTracker<String> tracker = null;

	ConversationAdapter(Context ctx,
			ConversationListener conversationListener) {
		super(ctx, ConversationItem.class);
		listener = conversationListener;
		// This shares the same pool for view recycling between all image lists
		imageViewPool = new RecycledViewPool();
		// Share the item decoration as well
		imageItemDecoration = new ImageItemDecoration(ctx);
	}

	@LayoutRes
	@Override
	public int getItemViewType(int position) {
		ConversationItem item = items.get(position);
		return item.getLayout();
	}

	String getItemKey(int position) {
		return items.get(position).getKey();
	}

	int getPositionOfKey(String key) {
		for (int i = 0; i < items.size(); i++) {
			if (key.equals(items.get(i).getKey())) return i;
		}
		return NO_POSITION;
	}

	@Override
	public ConversationItemViewHolder onCreateViewHolder(ViewGroup viewGroup,
			@LayoutRes int type) {
		View v = LayoutInflater.from(viewGroup.getContext()).inflate(
				type, viewGroup, false);
		if (type == R.layout.list_item_conversation_msg_in) {
			return new ConversationMessageViewHolder(v, listener, true,
					imageViewPool, imageItemDecoration);
		} else if (type == R.layout.list_item_conversation_msg_out) {
			return new ConversationMessageViewHolder(v, listener, false,
					imageViewPool, imageItemDecoration);
		} else if (type == R.layout.list_item_conversation_notice_in) {
			return new ConversationNoticeViewHolder(v, listener, true);
		} else if (type == R.layout.list_item_conversation_notice_out) {
			return new ConversationNoticeViewHolder(v, listener, false);
		} else if (type == R.layout.list_item_conversation_request) {
			return new ConversationRequestViewHolder(v, listener, true);
		}
		throw new IllegalArgumentException("Unknown ConversationItem");
	}

	@Override
	public void onBindViewHolder(ConversationItemViewHolder ui, int position) {
		ConversationItem item = items.get(position);
		boolean selected = tracker != null && tracker.isSelected(item.getKey());
		ui.bind(item, selected);
	}

	@Override
	public int compare(ConversationItem c1, ConversationItem c2) {
		return Long.compare(c1.getTime(), c2.getTime());
	}

	@Override
	public boolean areItemsTheSame(ConversationItem c1,
			ConversationItem c2) {
		return c1.getId().equals(c2.getId());
	}

	@Override
	public boolean areContentsTheSame(ConversationItem c1,
			ConversationItem c2) {
		return c1.equals(c2);
	}

	@Override
	public void add(ConversationItem item) {
		items.beginBatchedUpdates();
		items.add(item);
		updateTimersInBatch();
		items.endBatchedUpdates();
	}

	@Override
	public void replaceAll(Collection<ConversationItem> itemsToReplace) {
		items.beginBatchedUpdates();
		// there can be items already in the adapter
		// SortedList takes care of duplicates and detecting changed items
		items.replaceAll(itemsToReplace);
		updateTimersInBatch();
		items.endBatchedUpdates();
	}

	@UiThread
	void removeItems(Collection<MessageId> messageIds) {
		// Collect all items to be deleted first
		// and then delete them in one batched update.
		// Deleting them right away would cause issues
		// due to changing list positions.
		List<ConversationItem> toRemove = new ArrayList<>(messageIds.size());
		for (int i = 0; i < items.size(); i++) {
			ConversationItem item = items.get(i);
			if (messageIds.contains(item.getId())) toRemove.add(item);
		}
		items.beginBatchedUpdates();
		for (ConversationItem item : toRemove) items.remove(item);
		updateTimersInBatch();
		items.endBatchedUpdates();
	}

	private void updateTimersInBatch() {
		long lastTimerIncoming = NO_AUTO_DELETE_TIMER;
		long lastTimerOutgoing = NO_AUTO_DELETE_TIMER;
		for (int i = 0; i < items.size(); i++) {
			ConversationItem c = items.get(i);
			boolean itemChanged;
			boolean timerChanged;
			if (c.isIncoming()) {
				timerChanged = lastTimerIncoming != c.getAutoDeleteTimer();
				lastTimerIncoming = c.getAutoDeleteTimer();
			} else {
				timerChanged = lastTimerOutgoing != c.getAutoDeleteTimer();
				lastTimerOutgoing = c.getAutoDeleteTimer();
			}
			itemChanged = c.setTimerNoticeVisible(timerChanged);
			if (itemChanged) items.updateItemAt(i, c);
		}
	}

	void setSelectionTracker(SelectionTracker<String> tracker) {
		this.tracker = tracker;
	}

	SparseArray<ConversationItem> getOutgoingMessages() {
		SparseArray<ConversationItem> messages = new SparseArray<>();
		for (int i = 0; i < items.size(); i++) {
			ConversationItem item = items.get(i);
			if (!item.isIncoming()) {
				messages.put(i, item);
			}
		}
		return messages;
	}

	@Nullable
	Pair<Integer, ConversationMessageItem> getMessageItem(MessageId messageId) {
		for (int i = 0; i < items.size(); i++) {
			ConversationItem item = items.get(i);
			if (item instanceof ConversationMessageItem &&
					item.getId().equals(messageId)) {
				return new Pair<>(i, (ConversationMessageItem) item);
			}
		}
		return null;
	}

	boolean isScrolledToBottom(LinearLayoutManager layoutManager) {
		return layoutManager.findLastVisibleItemPosition() == items.size() - 1;
	}

}
