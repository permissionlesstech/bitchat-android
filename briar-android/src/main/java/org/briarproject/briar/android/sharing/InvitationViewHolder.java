package org.briarproject.briar.android.sharing;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.android.sharing.InvitationAdapter.InvitationClickListener;
import org.briarproject.briar.android.view.TextAvatarView;
import org.briarproject.briar.api.sharing.InvitationItem;

import javax.annotation.Nullable;

import androidx.annotation.CallSuper;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

public class InvitationViewHolder<I extends InvitationItem>
		extends RecyclerView.ViewHolder {

	private final TextAvatarView avatar;
	private final TextView name;
	protected final TextView sharedBy;
	private final TextView subscribed;
	private final Button accept;
	private final Button decline;

	public InvitationViewHolder(View v) {
		super(v);

		avatar = v.findViewById(R.id.avatarView);
		name = v.findViewById(R.id.forumNameView);
		sharedBy = v.findViewById(R.id.sharedByView);
		subscribed = v.findViewById(R.id.forumSubscribedView);
		accept = v.findViewById(R.id.acceptButton);
		decline = v.findViewById(R.id.declineButton);
	}

	@CallSuper
	public void onBind(@Nullable I item, InvitationClickListener<I> listener) {
		if (item == null) return;

		avatar.setText(item.getShareable().getName().substring(0, 1));
		avatar.setBackgroundBytes(item.getShareable().getId().getBytes());

		name.setText(item.getShareable().getName());

		if (item.isSubscribed()) {
			subscribed.setVisibility(VISIBLE);
		} else {
			subscribed.setVisibility(GONE);
		}

		accept.setOnClickListener(v -> listener.onItemClick(item, true));
		decline.setOnClickListener(v -> listener.onItemClick(item, false));
	}

}