package org.briarproject.briar.android.util;

import android.content.Context;

import java.util.Collection;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.recyclerview.widget.RecyclerView.Adapter;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import androidx.recyclerview.widget.SortedList;

import static androidx.recyclerview.widget.SortedList.INVALID_POSITION;

public abstract class BriarAdapter<T, V extends ViewHolder>
		extends Adapter<V> implements VersionedAdapter {

	protected final Context ctx;
	protected final SortedList<T> items;

	private volatile int revision = 0;

	public BriarAdapter(Context ctx, Class<T> c) {
		this.ctx = ctx;
		this.items = new SortedList<>(c, new SortedList.Callback<T>() {
			@Override
			public int compare(T item1, T item2) {
				return BriarAdapter.this.compare(item1, item2);
			}

			@Override
			public void onInserted(int position, int count) {
				notifyItemRangeInserted(position, count);
			}

			@Override
			public void onRemoved(int position, int count) {
				notifyItemRangeRemoved(position, count);
			}

			@Override
			public void onMoved(int fromPosition, int toPosition) {
				notifyItemMoved(fromPosition, toPosition);
			}

			@Override
			public void onChanged(int position, int count) {
				notifyItemRangeChanged(position, count);
			}

			@Override
			public boolean areContentsTheSame(T item1, T item2) {
				return BriarAdapter.this.areContentsTheSame(item1, item2);
			}

			@Override
			public boolean areItemsTheSame(T item1, T item2) {
				return BriarAdapter.this.areItemsTheSame(item1, item2);
			}
		});
	}

	public abstract int compare(T item1, T item2);

	public abstract boolean areContentsTheSame(T item1, T item2);

	public abstract boolean areItemsTheSame(T item1, T item2);

	@Override
	public int getItemCount() {
		return items.size();
	}

	public void add(T item) {
		items.add(item);
	}

	public void addAll(Collection<T> items) {
		this.items.addAll(items);
	}

	public void replaceAll(Collection<T> items) {
		this.items.replaceAll(items);
	}

	public void setItems(Collection<T> items) {
		this.items.beginBatchedUpdates();
		this.items.clear();
		this.items.addAll(items);
		this.items.endBatchedUpdates();
	}

	@Nullable
	public T getItemAt(int position) {
		if (position == INVALID_POSITION || position >= items.size()) {
			return null;
		}
		return items.get(position);
	}

	public int findItemPosition(T item) {
		return items.indexOf(item);
	}

	public void updateItemAt(int position, T item) {
		items.updateItemAt(position, item);
	}

	public void remove(T item) {
		items.remove(item);
	}

	public void clear() {
		items.clear();
	}

	public boolean isEmpty() {
		return items.size() == 0;
	}

	@Override
	public int getRevision() {
		return revision;
	}

	@UiThread
	@Override
	public void incrementRevision() {
		revision++;
	}
}
