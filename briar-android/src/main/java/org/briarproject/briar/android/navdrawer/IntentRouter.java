package org.briarproject.briar.android.navdrawer;

import android.content.Context;
import android.content.Intent;

import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.contact.add.remote.AddContactActivity;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.EXTRA_TEXT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static org.briarproject.bramble.api.contact.HandshakeLinkConstants.LINK_REGEX;

class IntentRouter {

	static void handleExternalIntent(Context ctx, Intent i) {
		String action = i.getAction();
		// add remote contact with clicked briar:// link
		if (ACTION_VIEW.equals(action) && "briar".equals(i.getScheme())) {
			redirect(ctx, i, AddContactActivity.class);
		}
		// add remote contact with shared briar:// link
		else if (ACTION_SEND.equals(action) &&
				"text/plain".equals(i.getType()) &&
				i.getStringExtra(EXTRA_TEXT) != null &&
				LINK_REGEX.matcher(i.getStringExtra(EXTRA_TEXT)).find()) {
			redirect(ctx, i, AddContactActivity.class);
		}
	}

	private static void redirect(Context ctx, Intent i,
			Class<? extends BriarActivity> activityClass) {
		i.setClass(ctx, activityClass);
		i.addFlags(FLAG_ACTIVITY_CLEAR_TOP);
		ctx.startActivity(i);
	}

}
