package org.briarproject.briar.android.privategroup.invitation;

import android.content.Context;
import android.view.ViewGroup;

import org.briarproject.briar.android.sharing.InvitationAdapter;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationItem;

class GroupInvitationAdapter extends
		InvitationAdapter<GroupInvitationItem, GroupInvitationViewHolder> {

	GroupInvitationAdapter(Context ctx,
			InvitationClickListener<GroupInvitationItem> listener) {
		super(ctx, GroupInvitationItem.class, listener);
	}

	@Override
	public GroupInvitationViewHolder onCreateViewHolder(ViewGroup parent,
			int viewType) {
		return new GroupInvitationViewHolder(getView(parent));
	}

	@Override
	public boolean areContentsTheSame(GroupInvitationItem item1,
			GroupInvitationItem item2) {
		return item1.isSubscribed() == item2.isSubscribed();
	}
}
