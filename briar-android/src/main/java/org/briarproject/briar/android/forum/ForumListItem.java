package org.briarproject.briar.android.forum;

import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.forum.Forum;
import org.briarproject.briar.api.forum.ForumPostHeader;

import javax.annotation.concurrent.Immutable;

import androidx.annotation.Nullable;

@Immutable
class ForumListItem implements Comparable<ForumListItem> {

	private final Forum forum;
	private final int postCount, unread;
	private final long timestamp;

	ForumListItem(Forum forum, GroupCount count) {
		this.forum = forum;
		this.postCount = count.getMsgCount();
		this.unread = count.getUnreadCount();
		this.timestamp = count.getLatestMsgTime();
	}

	ForumListItem(ForumListItem item, ForumPostHeader h) {
		this.forum = item.forum;
		this.postCount = item.postCount + 1;
		this.unread = item.unread + (h.isRead() ? 0 : 1);
		this.timestamp = Math.max(item.timestamp, h.getTimestamp());
	}

	Forum getForum() {
		return forum;
	}

	boolean isEmpty() {
		return postCount == 0;
	}

	int getPostCount() {
		return postCount;
	}

	long getTimestamp() {
		return timestamp;
	}

	int getUnreadCount() {
		return unread;
	}

	@Override
	public int hashCode() {
		return forum.getId().hashCode();
	}

	@Override
	public boolean equals(@Nullable Object o) {
		return o instanceof ForumListItem && getForum().equals(
				((ForumListItem) o).getForum());
	}

	@Override
	public int compareTo(ForumListItem o) {
		if (this == o) return 0;
		// The forum with the newest message comes first
		long aTime = getTimestamp(), bTime = o.getTimestamp();
		if (aTime > bTime) return -1;
		if (aTime < bTime) return 1;
		// Break ties by forum name
		String aName = getForum().getName();
		String bName = o.getForum().getName();
		return String.CASE_INSENSITIVE_ORDER.compare(aName, bName);
	}

}
