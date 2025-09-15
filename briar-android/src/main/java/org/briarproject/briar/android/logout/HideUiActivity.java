package org.briarproject.briar.android.logout;

import android.app.Activity;
import android.os.Bundle;

public class HideUiActivity extends Activity {

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);
		finish();
	}
}
