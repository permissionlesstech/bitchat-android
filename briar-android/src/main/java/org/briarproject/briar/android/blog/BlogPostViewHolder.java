package org.briarproject.briar.android.blog;

import android.content.Context;
import android.content.Intent;
import android.text.Spanned;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.view.AuthorView;
import org.briarproject.briar.api.blog.BlogCommentHeader;
import org.briarproject.briar.api.blog.BlogPostHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.blog.BlogPostFragment.POST_ID;
import static org.briarproject.briar.android.util.UiUtils.TEASER_LENGTH;
import static org.briarproject.briar.android.util.UiUtils.getSpanned;
import static org.briarproject.briar.android.util.UiUtils.getTeaser;
import static org.briarproject.briar.android.util.UiUtils.makeLinksClickable;
import static org.briarproject.briar.android.view.AuthorView.COMMENTER;
import static org.briarproject.briar.android.view.AuthorView.REBLOGGER;
import static org.briarproject.briar.android.view.AuthorView.RSS_FEED_REBLOGGED;

@UiThread
@NotNullByDefault
class BlogPostViewHolder extends RecyclerView.ViewHolder {

	private final Context ctx;
	private final ViewGroup layout;
	private final AuthorView reblogger;
	private final AuthorView author;
	private final ImageButton reblogButton;
	private final TextView text;
	private final ViewGroup commentContainer;
	private final boolean fullText, authorClickable;
	private final int padding;

	private final OnBlogPostClickListener listener;

	BlogPostViewHolder(View v, boolean fullText,
			OnBlogPostClickListener listener, boolean authorClickable) {
		super(v);
		this.fullText = fullText;
		this.listener = listener;
		this.authorClickable = authorClickable;

		ctx = v.getContext();
		layout = v.findViewById(R.id.postLayout);
		reblogger = v.findViewById(R.id.rebloggerView);
		author = v.findViewById(R.id.authorView);
		reblogButton = v.findViewById(R.id.commentView);
		text = v.findViewById(R.id.textView);
		commentContainer = v.findViewById(R.id.commentContainer);
		padding = ctx.getResources()
				.getDimensionPixelSize(R.dimen.listitem_vertical_margin);
	}

	void hideReblogButton() {
		reblogButton.setVisibility(GONE);
	}

	void updateDate(long time) {
		author.setDate(time);
	}

	void setTransitionName(MessageId id) {
		ViewCompat.setTransitionName(layout, getTransitionName(id));
	}

	private String getTransitionName(MessageId id) {
		return "blogPost" + id.hashCode();
	}

	void bindItem(BlogPostItem item) {
		setTransitionName(item.getId());
		if (!fullText) {
			layout.setClickable(true);
			layout.setOnClickListener(v -> listener.onBlogPostClick(item));
		}

		boolean isReblog = item instanceof BlogCommentItem;

		// author and date
		BlogPostHeader post = item.getPostHeader();
		author.setAuthor(post.getAuthor(), post.getAuthorInfo());
		author.setDate(post.getTimestamp());
		author.setPersona(
				item.isRssFeed() ? AuthorView.RSS_FEED : AuthorView.NORMAL);
		// TODO make author clickable more often #624
		if (authorClickable && !isReblog) {
			author.setAuthorClickable(v -> listener.onAuthorClick(item));
		} else {
			author.setAuthorNotClickable();
		}

		// post text
		Spanned postText = getSpanned(item.getText());
		if (fullText) {
			text.setText(postText);
			text.setTextIsSelectable(true);
			makeLinksClickable(text, listener::onLinkClick);
		} else {
			text.setTextIsSelectable(false);
			if (postText.length() > TEASER_LENGTH)
				postText = getTeaser(ctx, postText);
			text.setText(postText);
		}

		// reblog button
		reblogButton.setOnClickListener(v -> {
			Intent i = new Intent(ctx, ReblogActivity.class);
			i.putExtra(GROUP_ID, item.getGroupId().getBytes());
			i.putExtra(POST_ID, item.getId().getBytes());
			ctx.startActivity(i);
		});

		// comments
		commentContainer.removeAllViews();
		if (isReblog) {
			onBindComment((BlogCommentItem) item, authorClickable);
		} else {
			reblogger.setVisibility(GONE);
		}
	}

	private void onBindComment(BlogCommentItem item, boolean authorClickable) {
		// reblogger
		reblogger.setAuthor(item.getAuthor(), item.getAuthorInfo());
		reblogger.setDate(item.getTimestamp());
		if (authorClickable) {
			reblogger.setAuthorClickable(v -> listener.onAuthorClick(item));
		} else {
			reblogger.setAuthorNotClickable();
		}
		reblogger.setVisibility(VISIBLE);
		reblogger.setPersona(REBLOGGER);

		author.setPersona(item.getHeader().getRootPost().isRssFeed() ?
				RSS_FEED_REBLOGGED : COMMENTER);

		// comments
		// TODO use nested RecyclerView instead like we do for Image Attachments
		for (BlogCommentHeader c : item.getComments()) {
			View v = LayoutInflater.from(ctx).inflate(
					R.layout.list_item_blog_comment, commentContainer, false);

			AuthorView author = v.findViewById(R.id.authorView);
			TextView text = v.findViewById(R.id.textView);

			author.setAuthor(c.getAuthor(), c.getAuthorInfo());
			author.setDate(c.getTimestamp());
			// TODO make author clickable #624

			text.setText(c.getComment());
			Linkify.addLinks(text, Linkify.WEB_URLS);
			text.setMovementMethod(null);
			if (fullText) {
				text.setTextIsSelectable(true);
				makeLinksClickable(text, listener::onLinkClick);
			}

			commentContainer.addView(v);
		}
	}
}
