package org.briarproject.briar.android.view;

import org.briarproject.briar.android.util.ItemReturningAdapter;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.CallSuper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;
import static java.util.Objects.requireNonNull;

@NotNullByDefault
public abstract class BriarRecyclerViewScrollListener<A extends ItemReturningAdapter<I>, I>
		extends OnScrollListener {

	protected final A adapter;

	private int prevFirstVisible = NO_POSITION;
	private int prevLastVisible = NO_POSITION;
	private int prevItemCount = 0;

	protected BriarRecyclerViewScrollListener(A adapter) {
		this.adapter = adapter;
	}

	@Override
	public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
		LinearLayoutManager layoutManager = (LinearLayoutManager)
				requireNonNull(recyclerView.getLayoutManager());
		int firstVisible = layoutManager.findFirstVisibleItemPosition();
		int lastVisible = layoutManager.findLastVisibleItemPosition();
		int itemCount = adapter.getItemCount();

		if (firstVisible != prevFirstVisible ||
				lastVisible != prevLastVisible ||
				itemCount != prevItemCount) {
			onItemsVisible(firstVisible, lastVisible, itemCount);
			prevFirstVisible = firstVisible;
			prevLastVisible = lastVisible;
			prevItemCount = itemCount;
		}
	}

	@CallSuper
	protected void onItemsVisible(int firstVisible, int lastVisible,
			int itemCount) {
		if (firstVisible != NO_POSITION && lastVisible != NO_POSITION) {
			for (int i = firstVisible; i <= lastVisible; i++) {
				onItemVisible(i);
			}
		}
	}

	private void onItemVisible(int position) {
		I item = requireNonNull(adapter.getItemAt(position));
		onItemVisible(item);
	}

	protected abstract void onItemVisible(I item);

}
