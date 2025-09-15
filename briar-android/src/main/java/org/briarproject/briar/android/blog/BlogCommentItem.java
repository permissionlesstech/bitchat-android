package org.briarproject.briar.android.blog;

import org.briarproject.briar.api.blog.BlogCommentHeader;
import org.briarproject.briar.api.blog.BlogPostHeader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
class BlogCommentItem extends BlogPostItem {

	private static final BlogCommentComparator COMPARATOR =
			new BlogCommentComparator();

	private final BlogPostHeader postHeader;
	private final List<BlogCommentHeader> comments = new ArrayList<>();

	BlogCommentItem(BlogCommentHeader header) {
		super(header, null);
		postHeader = collectComments(header);
		Collections.sort(comments, COMPARATOR);
	}

	private BlogPostHeader collectComments(BlogPostHeader header) {
		if (header instanceof BlogCommentHeader) {
			BlogCommentHeader comment = (BlogCommentHeader) header;
			if (comment.getComment() != null)
				comments.add(comment);
			return collectComments(comment.getParent());
		} else {
			return header;
		}
	}

	public void setText(String text) {
		this.text = text;
	}

	@Override
	public BlogCommentHeader getHeader() {
		return (BlogCommentHeader) super.getHeader();
	}

	@Override
	BlogPostHeader getPostHeader() {
		return postHeader;
	}

	List<BlogCommentHeader> getComments() {
		return comments;
	}

	private static class BlogCommentComparator
			implements Comparator<BlogCommentHeader> {
		@Override
		public int compare(BlogCommentHeader h1, BlogCommentHeader h2) {
			// re-use same comparator used for blog posts, but reverse it
			return BlogPostItem.compare(h2, h1);
		}
	}
}
