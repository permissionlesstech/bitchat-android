package org.briarproject.briar.android.blog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class BlogPostAdapter extends ListAdapter<BlogPostItem, BlogPostViewHolder> {

	private final boolean authorClickable;
	private final OnBlogPostClickListener listener;

	BlogPostAdapter(boolean authorClickable, OnBlogPostClickListener listener) {
		super(new DiffUtil.ItemCallback<BlogPostItem>() {
			@Override
			public boolean areItemsTheSame(BlogPostItem a, BlogPostItem b) {
				return a.getId().equals(b.getId());
			}

			@Override
			public boolean areContentsTheSame(BlogPostItem a, BlogPostItem b) {
				return a.isRead() == b.isRead();
			}
		});
		this.authorClickable = authorClickable;
		this.listener = listener;
	}

	@Override
	public BlogPostViewHolder onCreateViewHolder(ViewGroup parent,
			int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(
				R.layout.list_item_blog_post, parent, false);
		return new BlogPostViewHolder(v, false, listener, authorClickable);
	}

	@Override
	public void onBindViewHolder(BlogPostViewHolder ui, int position) {
		ui.bindItem(getItem(position));
	}

}
