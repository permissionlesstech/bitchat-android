package org.briarproject.briar.android.privategroup.list;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.privategroup.conversation.GroupActivity;
import org.briarproject.briar.android.util.UiUtils;
import org.briarproject.briar.android.view.TextAvatarView;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_NAME;
import static org.briarproject.briar.android.util.UiUtils.getContactDisplayName;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class GroupViewHolder extends RecyclerView.ViewHolder {

	private final static float ALPHA = 0.42f;

	private final Context ctx;
	private final ViewGroup layout;
	private final TextAvatarView avatar;
	private final TextView name;
	private final TextView creator;
	private final TextView postCount;
	private final TextView date;
	private final TextView status;
	private final Button remove;

	GroupViewHolder(View v) {
		super(v);
		ctx = v.getContext();
		layout = (ViewGroup) v;
		avatar = v.findViewById(R.id.avatarView);
		name = v.findViewById(R.id.nameView);
		creator = v.findViewById(R.id.creatorView);
		postCount = v.findViewById(R.id.messageCountView);
		date = v.findViewById(R.id.dateView);
		status = v.findViewById(R.id.statusView);
		remove = v.findViewById(R.id.removeButton);
	}

	void bindView(GroupItem group, OnGroupRemoveClickListener listener) {
		// Avatar
		avatar.setText(group.getName().substring(0, 1));
		avatar.setBackgroundBytes(group.getId().getBytes());
		avatar.setUnreadCount(group.getUnreadCount());

		// Group Name
		name.setText(group.getName());

		// Creator
		String creatorName = getContactDisplayName(group.getCreator(),
				group.getCreatorInfo().getAlias());
		creator.setText(ctx.getString(R.string.groups_created_by, creatorName));

		if (!group.isDissolved()) {
			// full visibility
			avatar.setAlpha(1);
			name.setAlpha(1);
			creator.setAlpha(1);

			// Date and Status
			if (group.isEmpty()) {
				postCount.setVisibility(GONE);
				date.setVisibility(GONE);
				status.setText(ctx.getString(R.string.groups_group_is_empty));
				status.setVisibility(VISIBLE);
			} else {
				// Message Count
				int messageCount = group.getMessageCount();
				postCount.setVisibility(VISIBLE);
				postCount.setText(ctx.getResources()
						.getQuantityString(R.plurals.messages, messageCount,
								messageCount));

				long lastUpdate = group.getTimestamp();
				date.setText(UiUtils.formatDate(ctx, lastUpdate));
				date.setVisibility(VISIBLE);
				status.setVisibility(GONE);
			}
			remove.setVisibility(GONE);
		} else {
			// grey out
			avatar.setAlpha(ALPHA);
			name.setAlpha(ALPHA);
			creator.setAlpha(ALPHA);

			postCount.setVisibility(GONE);
			date.setVisibility(GONE);
			status
					.setText(ctx.getString(R.string.groups_group_is_dissolved));
			status.setVisibility(VISIBLE);
			remove.setOnClickListener(v -> listener.onGroupRemoveClick(group));
			remove.setVisibility(VISIBLE);
		}

		// Open Group on Click
		layout.setOnClickListener(v -> {
			Intent i = new Intent(ctx, GroupActivity.class);
			GroupId id = group.getId();
			i.putExtra(GROUP_ID, id.getBytes());
			i.putExtra(GROUP_NAME, group.getName());
			ctx.startActivity(i);
		});
	}

	interface OnGroupRemoveClickListener {
		void onGroupRemoveClick(GroupItem item);
	}

}
