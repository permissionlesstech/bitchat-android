package org.briarproject.briar.android.conversation;

import android.content.Context;
import android.text.util.Linkify;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.bramble.util.StringUtils.trim;
import static org.briarproject.briar.android.util.UiUtils.formatDate;
import static org.briarproject.briar.android.util.UiUtils.formatDuration;
import static org.briarproject.briar.android.util.UiUtils.makeLinksClickable;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@UiThread
@NotNullByDefault
abstract class ConversationItemViewHolder extends ViewHolder {

	protected final ConversationListener listener;
	private final View root;
	protected final ConstraintLayout layout;
	@Nullable
	private final OutItemViewHolder outViewHolder;
	private final TextView topNotice, text;
	protected final TextView time;
	protected final ImageView bomb;
	@Nullable
	private String itemKey = null;

	ConversationItemViewHolder(View v, ConversationListener listener,
			boolean isIncoming) {
		super(v);
		this.listener = listener;
		outViewHolder = isIncoming ? null : new OutItemViewHolder(v);
		root = v;
		topNotice = v.findViewById(R.id.topNotice);
		layout = v.findViewById(R.id.layout);
		text = v.findViewById(R.id.text);
		time = v.findViewById(R.id.time);
		bomb = v.findViewById(R.id.bomb);
	}

	@CallSuper
	void bind(ConversationItem item, boolean selected) {
		itemKey = item.getKey();
		root.setActivated(selected);

		setTopNotice(item);

		if (item.getText() != null) {
			text.setText(trim(item.getText()));
			Linkify.addLinks(text, Linkify.WEB_URLS);
			makeLinksClickable(text, listener::onLinkClick);
		}

		long timestamp = item.getTime();
		time.setText(formatDate(time.getContext(), timestamp));

		boolean showBomb = item.getAutoDeleteTimer() != NO_AUTO_DELETE_TIMER;
		bomb.setVisibility(showBomb ? VISIBLE : GONE);

		if (outViewHolder != null) outViewHolder.bind(item);
	}

	boolean isIncoming() {
		return outViewHolder == null;
	}

	@Nullable
	String getItemKey() {
		return itemKey;
	}

	private void setTopNotice(ConversationItem item) {
		if (item.isTimerNoticeVisible()) {
			Context ctx = itemView.getContext();
			topNotice.setVisibility(VISIBLE);
			boolean enabled = item.getAutoDeleteTimer() != NO_AUTO_DELETE_TIMER;
			String duration = enabled ?
					formatDuration(ctx, item.getAutoDeleteTimer()) : "";
			String tapToLearnMore = ctx.getString(R.string.tap_to_learn_more);
			String text;
			if (item.isIncoming()) {
				String name = item.getContactName().getValue();
				text = enabled ?
						ctx.getString(R.string.auto_delete_msg_contact_enabled,
								name, duration, tapToLearnMore) :
						ctx.getString(R.string.auto_delete_msg_contact_disabled,
								name, tapToLearnMore);
			} else {
				text = enabled ?
						ctx.getString(R.string.auto_delete_msg_you_enabled,
								duration, tapToLearnMore) :
						ctx.getString(R.string.auto_delete_msg_you_disabled,
								tapToLearnMore);
			}
			topNotice.setText(text);
			topNotice.setOnClickListener(
					v -> listener.onAutoDeleteTimerNoticeClicked());
		} else {
			topNotice.setVisibility(GONE);
		}
	}

}
