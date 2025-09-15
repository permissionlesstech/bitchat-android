package org.briarproject.briar.android.threaded;

import org.briarproject.briar.android.view.BriarRecyclerViewScrollListener;
import org.briarproject.briar.android.view.UnreadMessageButton;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.logging.Logger;

import androidx.recyclerview.widget.LinearLayoutManager;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;

@NotNullByDefault
class ThreadScrollListener<I extends ThreadItem>
		extends BriarRecyclerViewScrollListener<ThreadItemAdapter<I>, I> {

	private static final Logger LOG =
			getLogger(ThreadScrollListener.class.getName());

	private final ThreadListViewModel<I> viewModel;
	private final UnreadMessageButton upButton, downButton;

	ThreadScrollListener(ThreadItemAdapter<I> adapter,
			ThreadListViewModel<I> viewModel,
			UnreadMessageButton upButton,
			UnreadMessageButton downButton) {
		super(adapter);
		this.viewModel = viewModel;
		this.upButton = upButton;
		this.downButton = downButton;
	}

	@Override
	protected void onItemsVisible(int firstVisible, int lastVisible,
			int itemCount) {
		super.onItemsVisible(firstVisible, lastVisible, itemCount);
		updateUnreadButtons(firstVisible, lastVisible, itemCount);
	}

	@Override
	protected void onItemVisible(I item) {
		if (!item.isRead()) {
			item.setRead(true);
			viewModel.markItemRead(item);
		}
	}

	void updateUnreadButtons(LinearLayoutManager layoutManager) {
		int firstVisible = layoutManager.findFirstVisibleItemPosition();
		int lastVisible = layoutManager.findLastVisibleItemPosition();
		int itemCount = adapter.getItemCount();
		updateUnreadButtons(firstVisible, lastVisible, itemCount);
	}

	private void updateUnreadButtons(int firstVisible, int lastVisible,
			int count) {
		if (firstVisible == NO_POSITION || lastVisible == NO_POSITION) {
			setUnreadButtons(0, 0);
			return;
		}
		int unreadCounterFirst = 0;
		for (int i = 0; i < firstVisible; i++) {
			I item = requireNonNull(adapter.getItemAt(i));
			if (!item.isRead()) unreadCounterFirst++;
		}
		int unreadCounterLast = 0;
		for (int i = lastVisible + 1; i < count; i++) {
			I item = requireNonNull(adapter.getItemAt(i));
			if (!item.isRead()) unreadCounterLast++;
		}
		setUnreadButtons(unreadCounterFirst, unreadCounterLast);
	}

	private void setUnreadButtons(int upCount, int downCount) {
		if (LOG.isLoggable(INFO)) {
			LOG.info("Updating unread count: top=" + upCount +
					" bottom=" + downCount);
		}
		upButton.setUnreadCount(upCount);
		downButton.setUnreadCount(downCount);
	}

}
