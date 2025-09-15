package org.briarproject.briar.android.contactselection;

import android.view.View;

import org.briarproject.briar.R;
import org.briarproject.briar.android.contact.OnContactClickListener;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.ERROR;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.INVITE_RECEIVED;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.INVITE_SENT;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.NOT_SUPPORTED;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHARING;

@UiThread
@NotNullByDefault
class SelectableContactHolder
		extends BaseSelectableContactHolder<SelectableContactItem> {

	SelectableContactHolder(View v) {
		super(v);
	}

	@Override
	protected void bind(SelectableContactItem item, @Nullable
			OnContactClickListener<SelectableContactItem> listener) {
		super.bind(item, listener);

		if (item.isDisabled()) {
			@StringRes int strRes;
			if (item.getSharingStatus() == SHARING) {
				strRes = R.string.forum_invitation_already_sharing;
			} else if (item.getSharingStatus() == INVITE_SENT) {
				strRes = R.string.forum_invitation_already_invited;
			} else if (item.getSharingStatus() == INVITE_RECEIVED) {
				strRes = R.string.forum_invitation_invite_received;
			} else if (item.getSharingStatus() == NOT_SUPPORTED) {
				strRes = R.string.forum_invitation_not_supported;
			} else if (item.getSharingStatus() == ERROR) {
				strRes = R.string.forum_invitation_error;
			} else throw new AssertionError("Unhandled SharingStatus");
			info.setText(strRes);
			info.setVisibility(VISIBLE);
		} else {
			info.setVisibility(GONE);
		}
	}

}
