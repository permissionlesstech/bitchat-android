package org.briarproject.briar.android.privategroup.reveal;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.briar.android.contactselection.BaseSelectableContactItem;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.privategroup.Visibility;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.NotThreadSafe;

import static org.briarproject.briar.api.privategroup.Visibility.INVISIBLE;

@NotThreadSafe
@NotNullByDefault
class RevealableContactItem extends BaseSelectableContactItem {

	private final Visibility visibility;

	RevealableContactItem(Contact contact, AuthorInfo authorInfo,
			boolean selected, Visibility visibility) {
		super(contact, authorInfo, selected);
		this.visibility = visibility;
	}

	Visibility getVisibility() {
		return visibility;
	}

	@Override
	public boolean isDisabled() {
		return visibility != INVISIBLE;
	}
}
