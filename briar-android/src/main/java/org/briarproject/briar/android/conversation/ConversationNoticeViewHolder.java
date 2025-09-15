package org.briarproject.briar.android.conversation;

import android.text.util.Linkify;
import android.view.View;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.CallSuper;
import androidx.annotation.UiThread;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.bramble.util.StringUtils.trim;
import static org.briarproject.briar.android.util.UiUtils.makeLinksClickable;

@UiThread
@NotNullByDefault
class ConversationNoticeViewHolder extends ConversationItemViewHolder {

	private final TextView msgText;

	ConversationNoticeViewHolder(View v, ConversationListener listener,
			boolean isIncoming) {
		super(v, listener, isIncoming);
		msgText = v.findViewById(R.id.msgText);
	}

	@Override
	@CallSuper
	void bind(ConversationItem item, boolean selected) {
		ConversationNoticeItem notice = (ConversationNoticeItem) item;
		super.bind(notice, selected);

		String text = notice.getMsgText();
		if (isNullOrEmpty(text)) {
			msgText.setVisibility(GONE);
			layout.setBackgroundResource(isIncoming() ? R.drawable.notice_in :
					R.drawable.notice_out);
		} else {
			msgText.setVisibility(VISIBLE);
			msgText.setText(trim(text));
			Linkify.addLinks(msgText, Linkify.WEB_URLS);
			makeLinksClickable(msgText, listener::onLinkClick);
			layout.setBackgroundResource(isIncoming() ?
					R.drawable.notice_in_bottom : R.drawable.notice_out_bottom);
		}
	}

}
