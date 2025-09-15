package org.briarproject.briar.android.privategroup.invitation;

import android.view.View;

import org.briarproject.briar.R;
import org.briarproject.briar.android.sharing.InvitationAdapter.InvitationClickListener;
import org.briarproject.briar.android.sharing.InvitationViewHolder;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationItem;

import javax.annotation.Nullable;

import static org.briarproject.briar.android.util.UiUtils.getContactDisplayName;

class GroupInvitationViewHolder
		extends InvitationViewHolder<GroupInvitationItem> {

	GroupInvitationViewHolder(View v) {
		super(v);
	}

	@Override
	public void onBind(@Nullable GroupInvitationItem item,
			InvitationClickListener<GroupInvitationItem> listener) {
		super.onBind(item, listener);
		if (item == null) return;

		sharedBy.setText(
				sharedBy.getContext().getString(R.string.groups_created_by,
						getContactDisplayName(item.getCreator())));
	}

}