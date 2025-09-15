package org.briarproject.briar.android.forum;

import android.app.Application;
import android.widget.Toast;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.R;
import org.briarproject.briar.android.sharing.SharingController;
import org.briarproject.briar.android.threaded.ThreadListViewModel;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.forum.Forum;
import org.briarproject.briar.api.forum.ForumInvitationResponse;
import org.briarproject.briar.api.forum.ForumManager;
import org.briarproject.briar.api.forum.ForumPost;
import org.briarproject.briar.api.forum.ForumPostHeader;
import org.briarproject.briar.api.forum.ForumSharingManager;
import org.briarproject.briar.api.forum.event.ForumInvitationResponseReceivedEvent;
import org.briarproject.briar.api.forum.event.ForumPostReceivedEvent;
import org.briarproject.briar.api.sharing.event.ContactLeftShareableEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static android.widget.Toast.LENGTH_SHORT;
import static java.lang.Math.max;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.now;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class ForumViewModel extends ThreadListViewModel<ForumPostItem> {

	private static final Logger LOG = getLogger(ForumViewModel.class.getName());

	private final ForumManager forumManager;
	private final ForumSharingManager forumSharingManager;

	@Inject
	ForumViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			IdentityManager identityManager,
			AndroidNotificationManager notificationManager,
			SharingController sharingController,
			@CryptoExecutor Executor cryptoExecutor,
			Clock clock,
			MessageTracker messageTracker,
			EventBus eventBus,
			ForumManager forumManager,
			ForumSharingManager forumSharingManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor,
				identityManager, notificationManager, sharingController,
				cryptoExecutor, clock, messageTracker, eventBus);
		this.forumManager = forumManager;
		this.forumSharingManager = forumSharingManager;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ForumPostReceivedEvent) {
			ForumPostReceivedEvent f = (ForumPostReceivedEvent) e;
			if (f.getGroupId().equals(groupId)) {
				LOG.info("Forum post received, adding...");
				ForumPostItem item =
						new ForumPostItem(f.getHeader(), f.getText());
				addItem(item, false);
			}
		} else if (e instanceof ForumInvitationResponseReceivedEvent) {
			ForumInvitationResponseReceivedEvent f =
					(ForumInvitationResponseReceivedEvent) e;
			ForumInvitationResponse r = f.getMessageHeader();
			if (r.getShareableId().equals(groupId) && r.wasAccepted()) {
				LOG.info("Forum invitation was accepted");
				sharingController.add(f.getContactId());
			}
		} else if (e instanceof ContactLeftShareableEvent) {
			ContactLeftShareableEvent c = (ContactLeftShareableEvent) e;
			if (c.getGroupId().equals(groupId)) {
				LOG.info("Forum left by contact");
				sharingController.remove(c.getContactId());
			}
		} else {
			super.eventOccurred(e);
		}
	}

	@Override
	protected void clearNotifications() {
		notificationManager.clearForumPostNotification(groupId);
	}

	LiveData<Forum> loadForum() {
		MutableLiveData<Forum> forum = new MutableLiveData<>();
		runOnDbThread(() -> {
			try {
				Forum f = forumManager.getForum(groupId);
				forum.postValue(f);
			} catch (DbException e) {
				handleException(e);
			}
		});
		return forum;
	}

	@Override
	public void loadItems() {
		loadFromDb(txn -> {
			long start = now();
			List<ForumPostHeader> headers =
					forumManager.getPostHeaders(txn, groupId);
			logDuration(LOG, "Loading headers", start);
			start = now();
			List<ForumPostItem> items = new ArrayList<>();
			for (ForumPostHeader header : headers) {
				items.add(loadItem(txn, header));
			}
			logDuration(LOG, "Loading bodies and creating items", start);
			return items;
		}, this::setItems);
	}

	private ForumPostItem loadItem(Transaction txn, ForumPostHeader header)
			throws DbException {
		String text = forumManager.getPostText(txn, header.getId());
		return new ForumPostItem(header, text);
	}

	@Override
	public void createAndStoreMessage(String text,
			@Nullable MessageId parentId) {
		runOnDbThread(() -> {
			try {
				LocalAuthor author = identityManager.getLocalAuthor();
				GroupCount count = forumManager.getGroupCount(groupId);
				long timestamp = max(count.getLatestMsgTime() + 1,
						clock.currentTimeMillis());
				createMessage(text, timestamp, parentId, author);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	private void createMessage(String text, long timestamp,
			@Nullable MessageId parentId, LocalAuthor author) {
		cryptoExecutor.execute(() -> {
			LOG.info("Creating forum post...");
			ForumPost msg = forumManager.createLocalPost(groupId, text,
					timestamp, parentId, author);
			storePost(msg, text);
		});
	}

	private void storePost(ForumPost msg, String text) {
		runOnDbThread(false, txn -> {
			long start = now();
			ForumPostHeader header = forumManager.addLocalPost(txn, msg);
			logDuration(LOG, "Storing forum post", start);
			txn.attach(() -> {
				ForumPostItem item = new ForumPostItem(header, text);
				addItem(item, true);
			});
		}, this::handleException);
	}

	@Override
	protected void markItemRead(ForumPostItem item) {
		runOnDbThread(() -> {
			try {
				forumManager.setReadFlag(groupId, item.getId(), true);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@Override
	public void loadSharingContacts() {
		runOnDbThread(true, txn -> {
			Collection<Contact> contacts =
					forumSharingManager.getSharedWith(txn, groupId);
			Collection<ContactId> contactIds = new ArrayList<>(contacts.size());
			for (Contact c : contacts) contactIds.add(c.getId());
			txn.attach(() -> sharingController.addAll(contactIds));
		}, this::handleException);
	}

	void deleteForum() {
		runOnDbThread(() -> {
			try {
				Forum f = forumManager.getForum(groupId);
				forumManager.removeForum(f);
				androidExecutor.runOnUiThread(() -> Toast
						.makeText(getApplication(), R.string.forum_left_toast,
								LENGTH_SHORT).show());
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

}
