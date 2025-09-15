package org.briarproject.briar.android.logout;

import android.app.Activity;
import android.os.Bundle;

import java.util.logging.Logger;

public class ExitActivity extends Activity {

	private static final Logger LOG =
			Logger.getLogger(ExitActivity.class.getName());

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		finishAndRemoveTask();
		LOG.info("Exiting");
		System.exit(0);
	}
}