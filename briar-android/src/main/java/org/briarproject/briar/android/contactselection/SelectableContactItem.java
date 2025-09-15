package org.briarproject.briar.android.contactselection;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.sharing.SharingManager.SharingStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.NotThreadSafe;

import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;

@NotThreadSafe
@NotNullByDefault
public class SelectableContactItem extends BaseSelectableContactItem {

	private final SharingStatus sharingStatus;

	public SelectableContactItem(Contact contact, AuthorInfo authorInfo,
			boolean selected, SharingStatus sharingStatus) {
		super(contact, authorInfo, selected);
		this.sharingStatus = sharingStatus;
	}

	public SharingStatus getSharingStatus() {
		return sharingStatus;
	}

	@Override
	public boolean isDisabled() {
		return sharingStatus != SHAREABLE;
	}

}
