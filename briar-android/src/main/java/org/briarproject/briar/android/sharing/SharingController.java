package org.briarproject.briar.android.sharing;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotNullByDefault
public interface SharingController {

	/**
	 * Call this when the owning ViewModel gets cleared,
	 * so the {@link EventBus} can get unregistered.
	 */
	void onCleared();

	/**
	 * Adds one contact to be tracked.
	 */
	@UiThread
	void add(ContactId c);

	/**
	 * Adds a collection of contacts to be tracked.
	 */
	@UiThread
	void addAll(Collection<ContactId> contacts);

	/**
	 * Call this when the contact identified by c is no longer sharing
	 * the given group identified by GroupId g.
	 */
	@UiThread
	void remove(ContactId c);

	/**
	 * Returns the total number of contacts that have been added.
	 */
	LiveData<SharingInfo> getSharingInfo();

	class SharingInfo {
		public final int total, online;

		SharingInfo(int total, int online) {
			this.total = total;
			this.online = online;
		}
	}

}
