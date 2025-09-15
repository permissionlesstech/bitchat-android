package org.briarproject.briar.android.util;

import androidx.annotation.UiThread;

public interface VersionedAdapter {

	/**
	 * Returns the adapter's revision counter. This method should be called on
	 * any thread before starting an asynchronous load that could overwrite
	 * other changes to the adapter, and called again on the UI thread before
	 * applying the changes from the asynchronous load. If the revision has
	 * changed between the two calls, the asynchronous load should be restarted
	 * without applying its changes. Otherwise {@link #incrementRevision()}
	 * should be called before applying the changes.
	 */
	int getRevision();

	/**
	 * Increments the adapter's revision counter. This method should be called
	 * on the UI thread before applying any changes to the adapter that could
	 * be overwritten by an asynchronous load.
	 */
	@UiThread
	void incrementRevision();

}
