package org.briarproject.briar.android.privategroup.memberlist;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.android.view.AuthorView;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.util.UiUtils.getContactDisplayName;
import static org.briarproject.briar.api.identity.AuthorInfo.Status.OURSELVES;

@UiThread
@NotNullByDefault
class MemberListItemHolder extends RecyclerView.ViewHolder {

	private final AuthorView author;
	private final ImageView bulb;
	private final TextView creator;

	MemberListItemHolder(View v) {
		super(v);
		author = v.findViewById(R.id.authorView);
		bulb = v.findViewById(R.id.bulbView);
		creator = v.findViewById(R.id.creatorView);
	}

	protected void bind(MemberListItem item) {
		// member name, avatar and author info
		author.setAuthor(item.getMember(), item.getAuthorInfo());

		// online status of visible contacts
		if (item.getContactId() != null) {
			bulb.setVisibility(VISIBLE);
			if (item.isOnline()) {
				bulb.setImageResource(R.drawable.contact_connected);
			} else {
				bulb.setImageResource(R.drawable.contact_disconnected);
			}
		} else {
			bulb.setVisibility(GONE);
		}

		// text shown for creator
		if (item.isCreator()) {
			creator.setVisibility(VISIBLE);
			if (item.getStatus() == OURSELVES) {
				creator.setText(R.string.groups_member_created_you);
			} else {
				String name = getContactDisplayName(item.getMember(),
						item.getAuthorInfo().getAlias());
				creator.setText(creator.getContext()
						.getString(R.string.groups_member_created, name));
			}
		} else {
			creator.setVisibility(GONE);
		}
	}

}
