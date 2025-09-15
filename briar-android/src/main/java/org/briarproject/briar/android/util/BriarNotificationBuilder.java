package org.briarproject.briar.android.util;

import android.content.Context;

import org.briarproject.briar.R;

import androidx.annotation.ColorRes;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import static androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE;

public class BriarNotificationBuilder extends NotificationCompat.Builder {

	private final Context context;

	public BriarNotificationBuilder(Context context, String channelId) {
		super(context, channelId);
		this.context = context;
		// Auto-cancel does not fire the delete intent, see
		// https://issuetracker.google.com/issues/36961721
		setAutoCancel(true);

		setLights(ContextCompat.getColor(context, R.color.briar_lime_400),
				750, 500);
		setVisibility(VISIBILITY_PRIVATE);
	}

	public BriarNotificationBuilder setColorRes(@ColorRes int res) {
		setColor(ContextCompat.getColor(context, res));
		return this;
	}

	public BriarNotificationBuilder setNotificationCategory(String category) {
		setCategory(category);
		return this;
	}

}
