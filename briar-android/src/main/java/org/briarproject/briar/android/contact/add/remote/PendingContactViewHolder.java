package org.briarproject.briar.android.contact.add.remote;

import android.view.View;
import android.widget.TextView;

import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.briar.R;
import org.briarproject.briar.android.view.TextAvatarView;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import static org.briarproject.briar.android.util.UiUtils.formatDate;

@NotNullByDefault
class PendingContactViewHolder extends ViewHolder {

	private final PendingContactListener listener;
	private final TextAvatarView avatar;
	private final TextView name;
	private final TextView time;
	private final TextView status;
	private final AppCompatImageButton removeButton;

	PendingContactViewHolder(View v, PendingContactListener listener) {
		super(v);
		avatar = v.findViewById(R.id.avatar);
		name = v.findViewById(R.id.name);
		time = v.findViewById(R.id.time);
		status = v.findViewById(R.id.status);
		removeButton = v.findViewById(R.id.removeButton);
		this.listener = listener;
	}

	public void bind(PendingContactItem item) {
		PendingContact p = item.getPendingContact();
		avatar.setText(p.getAlias());
		avatar.setBackgroundBytes(p.getId().getBytes());
		name.setText(p.getAlias());
		time.setText(formatDate(time.getContext(), p.getTimestamp()));
		removeButton.setOnClickListener(v -> {
			listener.onPendingContactItemRemoved(item);
			removeButton.setEnabled(false);
		});

		int color = ContextCompat
				.getColor(status.getContext(), R.color.briar_lime_600);
		switch (item.getState()) {
			case WAITING_FOR_CONNECTION:
				color = ContextCompat.getColor(status.getContext(),
						R.color.briar_orange_500);
				status.setText(R.string.waiting_for_contact_to_come_online);
				break;
			case OFFLINE:
				status.setText("");
				break;
			case CONNECTING:
				status.setText(R.string.connecting);
				break;
			case ADDING_CONTACT:
				status.setText(R.string.adding_contact);
				break;
			case FAILED:
				color = ContextCompat
						.getColor(status.getContext(), R.color.briar_red_500);
				status.setText(R.string.adding_contact_failed);
				break;
			default:
				throw new IllegalStateException();
		}
		status.setTextColor(color);
		removeButton.setEnabled(true);
	}

}
