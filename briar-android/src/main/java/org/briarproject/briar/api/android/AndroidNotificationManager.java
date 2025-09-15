package org.briarproject.briar.api.android;

import android.app.Notification;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.sync.GroupId;

/**
 * Manages notifications for private messages, forum posts, blog posts and
 * introductions.
 */
public interface AndroidNotificationManager {

	// Keys for notification preferences
	String PREF_NOTIFY_PRIVATE = "notifyPrivateMessages";
	String PREF_NOTIFY_GROUP = "notifyGroupMessages";
	String PREF_NOTIFY_FORUM = "notifyForumPosts";
	String PREF_NOTIFY_BLOG = "notifyBlogPosts";

	String PREF_NOTIFY_SOUND = "notifySound";
	String PREF_NOTIFY_RINGTONE_NAME = "notifyRingtoneName";
	String PREF_NOTIFY_RINGTONE_URI = "notifyRingtoneUri";
	String PREF_NOTIFY_VIBRATION = "notifyVibration";

	// Notification IDs
	int ONGOING_NOTIFICATION_ID = 1;
	int REMINDER_NOTIFICATION_ID = 3;
	int PRIVATE_MESSAGE_NOTIFICATION_ID = 4;
	int GROUP_MESSAGE_NOTIFICATION_ID = 5;
	int FORUM_POST_NOTIFICATION_ID = 6;
	int BLOG_POST_NOTIFICATION_ID = 7;
	int CONTACT_ADDED_NOTIFICATION_ID = 8;
	int HOTSPOT_NOTIFICATION_ID = 9;
	int MAILBOX_PROBLEM_NOTIFICATION_ID = 10;

	// Channel IDs
	String CONTACT_CHANNEL_ID = "contacts";
	String GROUP_CHANNEL_ID = "groups";
	String FORUM_CHANNEL_ID = "forums";
	String BLOG_CHANNEL_ID = "blogs";
	// Channels are sorted by channel ID in the Settings app, so use IDs
	// that will sort below the main channels such as contacts
	String ONGOING_CHANNEL_OLD_ID = "zForegroundService";
	String ONGOING_CHANNEL_ID = "zForegroundService2";
	String REMINDER_CHANNEL_ID = "zSignInReminder";
	String HOTSPOT_CHANNEL_ID = "zHotspot";
	String MAILBOX_PROBLEM_CHANNEL_ID = "zMailboxProblem";

	// Actions for pending intents
	String ACTION_DISMISS_REMINDER = "dismissReminder";
	String ACTION_STOP_HOTSPOT = "stopHotspot";

	Notification getForegroundNotification();

	void updateForegroundNotification(boolean locked);

	void clearContactNotification(ContactId c);

	void clearAllContactNotifications();

	void clearGroupMessageNotification(GroupId g);

	void clearAllGroupMessageNotifications();

	void clearForumPostNotification(GroupId g);

	void clearAllForumPostNotifications();

	void clearBlogPostNotification(GroupId g);

	void clearAllBlogPostNotifications();

	void clearAllContactAddedNotifications();

	void showSignInNotification();

	void clearSignInNotification();

	void blockSignInNotification();

	void blockContactNotification(ContactId c);

	void unblockContactNotification(ContactId c);

	void blockNotification(GroupId g);

	void unblockNotification(GroupId g);

	void blockAllForumPostNotifications();

	void unblockAllForumPostNotifications();

	void blockAllGroupMessageNotifications();

	void unblockAllGroupMessageNotifications();

	void blockAllBlogPostNotifications();

	void unblockAllBlogPostNotifications();

	void showHotspotNotification();

	void clearHotspotNotification();

	void showMailboxProblemNotification();

	void clearMailboxProblemNotification();
}
