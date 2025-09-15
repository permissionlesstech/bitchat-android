package org.briarproject.briar.api.android;

import android.app.Activity;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

public interface LockManager {

	String ACTION_LOCK = "lock";
	String EXTRA_PID = "PID";

	/**
	 * Stops the inactivity timer when the user interacts with the app.
	 * Should typically be called by {@link Activity#onStart()}
	 */
	@UiThread
	void onActivityStart();

	/**
	 * Starts the inactivity timer which will lock the app.
	 * Should typically be called by {@link Activity#onStop()}
	 */
	@UiThread
	void onActivityStop();

	/**
	 * Returns an observable LiveData to indicate whether the app can be locked.
	 */
	LiveData<Boolean> isLockable();

	/**
	 * Updates the LiveData returned by {@link #isLockable()}.
	 * It checks whether a device screen lock is available and
	 * whether the app setting is checked.
	 */
	@UiThread
	void checkIfLockable();

	/**
	 * Returns true if app is currently locked, false otherwise.
	 * If the device's screen lock was removed while the app was locked,
	 * calling this will unlock the app automatically.
	 */
	boolean isLocked();

	/**
	 * Locks the app if true is passed, otherwise unlocks the app.
	 */
	void setLocked(boolean locked);

}
