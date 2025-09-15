package org.briarproject.briar.android.contactselection;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.briar.android.contact.ContactItem;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
@NotNullByDefault
public abstract class BaseSelectableContactItem extends ContactItem {

	private boolean selected;

	public BaseSelectableContactItem(Contact contact, AuthorInfo authorInfo,
			boolean selected) {
		super(contact, authorInfo);
		this.selected = selected;
	}

	boolean isSelected() {
		return selected;
	}

	void toggleSelected() {
		selected = !selected;
	}

	public abstract boolean isDisabled();

}
