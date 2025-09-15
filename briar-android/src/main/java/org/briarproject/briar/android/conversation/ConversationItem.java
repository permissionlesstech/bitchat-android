package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;

import static org.briarproject.bramble.util.StringUtils.toHexString;

@NotThreadSafe
@NotNullByDefault
abstract class ConversationItem {

	@LayoutRes
	private final int layoutRes;
	@Nullable
	protected String text;
	private final MessageId id;
	private final GroupId groupId;
	private final long time, autoDeleteTimer;
	private final boolean isIncoming;
	private final LiveData<String> contactName;
	private boolean read, sent, seen, showTimerNotice;

	ConversationItem(@LayoutRes int layoutRes, ConversationMessageHeader h,
			LiveData<String> contactName) {
		this.layoutRes = layoutRes;
		this.text = null;
		this.id = h.getId();
		this.groupId = h.getGroupId();
		this.time = h.getTimestamp();
		this.autoDeleteTimer = h.getAutoDeleteTimer();
		this.read = h.isRead();
		this.sent = h.isSent();
		this.seen = h.isSeen();
		this.isIncoming = !h.isLocal();
		this.contactName = contactName;
		this.showTimerNotice = false;
	}

	@LayoutRes
	int getLayout() {
		return layoutRes;
	}

	MessageId getId() {
		return id;
	}

	String getKey() {
		return toHexString(id.getBytes());
	}

	GroupId getGroupId() {
		return groupId;
	}

	void setText(String text) {
		this.text = text;
	}

	@Nullable
	String getText() {
		return text;
	}

	long getTime() {
		return time;
	}

	public long getAutoDeleteTimer() {
		return autoDeleteTimer;
	}

	/**
	 * Only useful for incoming messages.
	 */
	boolean isRead() {
		return read;
	}

	void markRead() {
		read = true;
	}

	/**
	 * Only useful for outgoing messages.
	 */
	boolean isSent() {
		return sent;
	}

	/**
	 * Only useful for outgoing messages.
	 */
	void setSent(boolean sent) {
		this.sent = sent;
	}

	/**
	 * Only useful for outgoing messages.
	 */
	boolean isSeen() {
		return seen;
	}

	/**
	 * Only useful for outgoing messages.
	 */
	void setSeen(boolean seen) {
		this.seen = seen;
	}

	boolean isIncoming() {
		return isIncoming;
	}

	public LiveData<String> getContactName() {
		return contactName;
	}

	/**
	 * Set this to true when {@link #getAutoDeleteTimer()} has changed
	 * since the last message from the same peer.
	 *
	 * @return true if the value was set, false if it was already set.
	 */
	boolean setTimerNoticeVisible(boolean visible) {
		if (this.showTimerNotice != visible) {
			this.showTimerNotice = visible;
			return true;
		}
		return false;
	}

	boolean isTimerNoticeVisible() {
		return showTimerNotice;
	}
}
