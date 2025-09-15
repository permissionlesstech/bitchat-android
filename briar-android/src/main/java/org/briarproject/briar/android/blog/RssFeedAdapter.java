package org.briarproject.briar.android.blog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.api.feed.Feed;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.util.UiUtils.formatDate;

@NotNullByDefault
class RssFeedAdapter extends ListAdapter<Feed, RssFeedAdapter.FeedViewHolder> {

	private final RssFeedListener listener;

	RssFeedAdapter(RssFeedListener listener) {
		super(new DiffUtil.ItemCallback<Feed>() {
			@Override
			public boolean areItemsTheSame(Feed a, Feed b) {
				return a.getBlogId().equals(b.getBlogId()) &&
						a.getAdded() == b.getAdded();
			}

			@Override
			public boolean areContentsTheSame(Feed a, Feed b) {
				return a.getUpdated() == b.getUpdated();
			}
		});
		this.listener = listener;
	}

	@Override
	public FeedViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(
				R.layout.list_item_rss_feed, parent, false);
		return new FeedViewHolder(v);
	}

	@Override
	public void onBindViewHolder(FeedViewHolder ui, int position) {
		ui.bindItem(getItem(position));
	}

	class FeedViewHolder extends RecyclerView.ViewHolder {
		private final Context ctx;
		private final View layout;
		private final TextView title;
		private final ImageButton delete;
		private final TextView imported;
		private final TextView updated;
		private final TextView author;
		private final TextView authorLabel;
		private final TextView description;

		private FeedViewHolder(View v) {
			super(v);

			ctx = v.getContext();
			layout = v;
			title = v.findViewById(R.id.titleView);
			delete = v.findViewById(R.id.deleteButton);
			imported = v.findViewById(R.id.importedView);
			updated = v.findViewById(R.id.updatedView);
			author = v.findViewById(R.id.authorView);
			authorLabel = v.findViewById(R.id.author);
			description = v.findViewById(R.id.descriptionView);
		}

		private void bindItem(Feed item) {
			// Feed Title
			title.setText(item.getTitle());

			// Delete Button
			delete.setOnClickListener(v -> listener.onDeleteClick(item));

			// Author
			if (item.getProperties().getAuthor() != null) {
				author.setText(item.getProperties().getAuthor());
				author.setVisibility(VISIBLE);
				authorLabel.setVisibility(VISIBLE);
			} else {
				author.setVisibility(GONE);
				authorLabel.setVisibility(GONE);
			}

			// Imported and Last Updated
			imported.setText(formatDate(ctx, item.getAdded()));
			updated.setText(formatDate(ctx, item.getUpdated()));

			// Description
			if (item.getProperties().getDescription() != null) {
				description.setText(item.getProperties().getDescription());
				description.setVisibility(VISIBLE);
			} else {
				description.setVisibility(GONE);
			}

			// Open feed's blog when clicked
			layout.setOnClickListener(v -> listener.onFeedClick(item));
		}
	}

	interface RssFeedListener {
		void onFeedClick(Feed feed);

		void onDeleteClick(Feed feed);
	}

}
