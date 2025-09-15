package org.briarproject.briar.android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.briarproject.briar.R;

import javax.annotation.Nullable;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;

import static org.briarproject.briar.android.util.UiUtils.MIN_DATE_RESOLUTION;

public class BriarRecyclerView extends FrameLayout {

	private final Handler handler = new Handler(Looper.getMainLooper());

	private RecyclerView recyclerView;
	private AppCompatImageView emptyImage;
	private TextView emptyText, emptyAction;
	private ProgressBar progressBar;
	private RecyclerView.AdapterDataObserver emptyObserver;
	@Nullable
	private Runnable refresher = null;
	private boolean isScrollingToEnd;

	public BriarRecyclerView(Context context) {
		this(context, null, 0);
	}

	public BriarRecyclerView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public BriarRecyclerView(Context context, @Nullable AttributeSet attrs,
			int defStyle) {
		super(context, attrs, defStyle);

		TypedArray attributes = context.obtainStyledAttributes(attrs,
				R.styleable.BriarRecyclerView);
		isScrollingToEnd = attributes
				.getBoolean(R.styleable.BriarRecyclerView_scrollToEnd, true);
		int drawableRes = attributes
				.getResourceId(R.styleable.BriarRecyclerView_emptyImage, -1);
		if (drawableRes != -1) setEmptyImage(drawableRes);
		String emtpyText =
				attributes.getString(R.styleable.BriarRecyclerView_emptyText);
		if (emtpyText != null) setEmptyText(emtpyText);
		String emtpyAction =
				attributes.getString(R.styleable.BriarRecyclerView_emptyAction);
		if (emtpyAction != null) setEmptyAction(emtpyAction);
		attributes.recycle();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		stopPeriodicUpdate();
	}

	private void initViews() {
		View v = LayoutInflater.from(getContext()).inflate(
				R.layout.briar_recycler_view, this, true);

		recyclerView = v.findViewById(R.id.recyclerView);
		emptyImage = v.findViewById(R.id.emptyImage);
		emptyText = v.findViewById(R.id.emptyText);
		emptyAction = v.findViewById(R.id.emptyAction);
		progressBar = v.findViewById(R.id.progressBar);

		showProgressBar();

		// scroll down when opening keyboard
		if (isScrollingToEnd) {
			addLayoutChangeListener();
		}

		emptyObserver = new RecyclerView.AdapterDataObserver() {

			@Override
			public void onChanged() {
				super.onChanged();
				showData();
			}

			@Override
			public void onItemRangeChanged(int positionStart, int itemCount) {
				super.onItemRangeChanged(positionStart, itemCount);
				if (itemCount > 0) showData();
			}

			@Override
			public void onItemRangeMoved(int fromPosition, int toPosition,
					int itemCount) {
				super.onItemRangeMoved(fromPosition, toPosition, itemCount);
				if (itemCount > 0) showData();
			}

			@Override
			public void onItemRangeInserted(int positionStart, int itemCount) {
				super.onItemRangeInserted(positionStart, itemCount);
				showData();
			}

			@Override
			public void onItemRangeRemoved(int positionStart, int itemCount) {
				super.onItemRangeRemoved(positionStart, itemCount);
				if (itemCount > 0) showData();
			}
		};
	}

	private void addLayoutChangeListener() {
		recyclerView.addOnLayoutChangeListener((v, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (bottom < oldBottom) {
				recyclerView.postDelayed(() -> scrollToPosition(
						recyclerView.getAdapter().getItemCount() - 1), 100);
			}
		});
	}

	public void setLayoutManager(RecyclerView.LayoutManager layout) {
		if (recyclerView == null) initViews();
		recyclerView.setLayoutManager(layout);
	}

	public void setAdapter(Adapter adapter) {
		if (recyclerView == null) initViews();

		Adapter oldAdapter = recyclerView.getAdapter();
		if (oldAdapter != null) {
			oldAdapter.unregisterAdapterDataObserver(emptyObserver);
		}

		recyclerView.setAdapter(adapter);

		if (adapter != null) {
			adapter.registerAdapterDataObserver(emptyObserver);

			if (adapter.getItemCount() > 0) {
				// only show data if adapter has data already
				// otherwise progress bar is shown
				emptyObserver.onChanged();
			}
		}
	}

	public void setEmptyImage(@DrawableRes int res) {
		if (recyclerView == null) initViews();
		emptyImage.setImageResource(res);
	}

	public void setEmptyText(String text) {
		if (recyclerView == null) initViews();
		emptyText.setText(text);
	}

	public void setEmptyText(@StringRes int res) {
		if (recyclerView == null) initViews();
		emptyText.setText(res);
	}

	public void setEmptyAction(String text) {
		if (recyclerView == null) initViews();
		emptyAction.setText(text);
	}

	public void setEmptyAction(@StringRes int res) {
		if (recyclerView == null) initViews();
		emptyAction.setText(res);
	}

	public void showProgressBar() {
		if (recyclerView == null) initViews();
		recyclerView.setVisibility(INVISIBLE);
		emptyImage.setVisibility(INVISIBLE);
		emptyText.setVisibility(INVISIBLE);
		emptyAction.setVisibility(INVISIBLE);
		progressBar.setVisibility(VISIBLE);
	}

	public void showData() {
		if (recyclerView == null) initViews();
		Adapter adapter = recyclerView.getAdapter();
		if (adapter != null) {
			if (adapter.getItemCount() == 0) {
				emptyImage.setVisibility(VISIBLE);
				emptyText.setVisibility(VISIBLE);
				emptyAction.setVisibility(VISIBLE);
				recyclerView.setVisibility(INVISIBLE);
			} else {
				emptyImage.setVisibility(INVISIBLE);
				emptyText.setVisibility(INVISIBLE);
				emptyAction.setVisibility(INVISIBLE);
				recyclerView.setVisibility(VISIBLE);
			}
			progressBar.setVisibility(GONE);
		}
	}

	public void scrollToPosition(int position) {
		if (recyclerView == null) initViews();
		recyclerView.scrollToPosition(position);
	}

	public void smoothScrollToPosition(int position) {
		if (recyclerView == null) initViews();
		recyclerView.smoothScrollToPosition(position);
	}

	public RecyclerView getRecyclerView() {
		return this.recyclerView;
	}

	public void startPeriodicUpdate() {
		startPeriodicUpdate(MIN_DATE_RESOLUTION);
	}

	public void startPeriodicUpdate(long interval) {
		if (recyclerView == null || recyclerView.getAdapter() == null) {
			throw new IllegalStateException("Need to call setAdapter() first!");
		}
		refresher = () -> {
			Adapter adapter = recyclerView.getAdapter();
			adapter.notifyItemRangeChanged(0, adapter.getItemCount());
			handler.postDelayed(refresher, interval);
		};
		handler.postDelayed(refresher, interval);
	}

	public void stopPeriodicUpdate() {
		if (refresher != null) {
			handler.removeCallbacks(refresher);
			refresher = null;
		}
	}

}
