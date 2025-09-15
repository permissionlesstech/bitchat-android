package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.conversation.ConversationRequest;
import org.briarproject.briar.api.sharing.InvitationRequest;
import org.briarproject.briar.api.sharing.Shareable;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationRequestItem extends ConversationNoticeItem {

	enum RequestType {INTRODUCTION, FORUM, BLOG, GROUP}

	@Nullable
	private final GroupId requestedGroupId;
	private final RequestType requestType;
	private final SessionId sessionId;
	private final boolean canBeOpened;
	private boolean answered;

	ConversationRequestItem(@LayoutRes int layoutRes, String text,
			LiveData<String> contactName, RequestType type,
			ConversationRequest<?> r) {
		super(layoutRes, text, contactName, r);
		this.requestType = type;
		this.sessionId = r.getSessionId();
		this.answered = r.wasAnswered();
		if (r instanceof InvitationRequest) {
			this.requestedGroupId = ((Shareable) r.getNameable()).getId();
			this.canBeOpened = ((InvitationRequest<?>) r).canBeOpened();
		} else {
			this.requestedGroupId = null;
			this.canBeOpened = false;
		}
	}

	RequestType getRequestType() {
		return requestType;
	}

	SessionId getSessionId() {
		return sessionId;
	}

	@Nullable
	GroupId getRequestedGroupId() {
		return requestedGroupId;
	}

	boolean wasAnswered() {
		return answered;
	}

	void setAnswered() {
		this.answered = true;
	}

	boolean canBeOpened() {
		return canBeOpened;
	}

}
