package org.briarproject.briar.android;

import android.app.Activity;
import android.content.SharedPreferences;

import org.briarproject.bramble.BrambleApplication;
import org.briarproject.briar.android.navdrawer.NavDrawerActivity;

/**
 * This exists so that the Application object will not necessarily be cast
 * directly to the Briar application object.
 */
public interface BriarApplication extends BrambleApplication {

	Class<? extends Activity> ENTRY_ACTIVITY = NavDrawerActivity.class;

	AndroidComponent getApplicationComponent();

	SharedPreferences getDefaultSharedPreferences();

	boolean isRunningInBackground();

	boolean isInstrumentationTest();
}
