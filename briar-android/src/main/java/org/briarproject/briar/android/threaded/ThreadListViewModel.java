package org.briarproject.briar.android.threaded;

import android.app.Application;

import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchGroupException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.android.sharing.SharingController;
import org.briarproject.briar.android.sharing.SharingController.SharingInfo;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.MessageTree;
import org.briarproject.briar.client.MessageTreeImpl;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import androidx.annotation.CallSuper;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class ThreadListViewModel<I extends ThreadItem>
		extends DbViewModel implements EventListener {

	private static final Logger LOG =
			getLogger(ThreadListViewModel.class.getName());

	protected final IdentityManager identityManager;
	protected final AndroidNotificationManager notificationManager;
	protected final SharingController sharingController;
	protected final Executor cryptoExecutor;
	protected final Clock clock;
	private final MessageTracker messageTracker;
	private final EventBus eventBus;

	// UIThread
	private final MessageTree<I> messageTree = new MessageTreeImpl<>();
	private final MutableLiveData<LiveResult<List<I>>> items =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> groupRemoved =
			new MutableLiveData<>();
	private final AtomicReference<MessageId> scrollToItem =
			new AtomicReference<>();

	protected volatile GroupId groupId;
	@Nullable
	private MessageId replyId;
	/**
	 * Stored list position. Needs to be loaded and set before the list itself.
	 */
	private final AtomicReference<MessageId> storedMessageId =
			new AtomicReference<>();

	public ThreadListViewModel(Application application,
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
			EventBus eventBus) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.identityManager = identityManager;
		this.notificationManager = notificationManager;
		this.cryptoExecutor = cryptoExecutor;
		this.clock = clock;
		this.sharingController = sharingController;
		this.messageTracker = messageTracker;
		this.eventBus = eventBus;
		this.eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
		sharingController.onCleared();
	}

	/**
	 * Needs to be called right after initialization,
	 * before calling any other methods.
	 */
	public final void setGroupId(GroupId groupId) {
		boolean needsInitialLoad = this.groupId == null;
		this.groupId = groupId;
		if (needsInitialLoad) performInitialLoad();
	}

	@CallSuper
	protected void performInitialLoad() {
		// load stored MessageId (last list position) before the list itself
		loadStoredMessageId();
		loadItems();
		loadSharingContacts();
	}

	protected abstract void clearNotifications();

	void blockAndClearNotifications() {
		notificationManager.blockNotification(groupId);
		clearNotifications();
	}

	void unblockNotifications() {
		notificationManager.unblockNotification(groupId);
	}

	@Override
	@CallSuper
	public void eventOccurred(Event e) {
		if (e instanceof GroupRemovedEvent) {
			GroupRemovedEvent s = (GroupRemovedEvent) e;
			if (s.getGroup().getId().equals(groupId)) {
				LOG.info("Group removed");
				groupRemoved.setValue(true);
			}
		}
	}

	private void loadStoredMessageId() {
		runOnDbThread(() -> {
			try {
				storedMessageId
						.set(messageTracker.loadStoredMessageId(groupId));
				if (LOG.isLoggable(INFO)) {
					LOG.info("Loaded last top visible message id " +
							storedMessageId);
				}
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	public abstract void loadItems();

	public abstract void createAndStoreMessage(String text,
			@Nullable MessageId parentMessageId);

	/**
	 * Loads the ContactIds of all contacts the group is shared with
	 * and adds them to {@link SharingController}.
	 */
	protected abstract void loadSharingContacts();

	@UiThread
	protected void setItems(LiveResult<List<I>> items) {
		if (items.hasError()) {
			this.items.setValue(items);
		} else {
			messageTree.clear();
			// not null, because hasError() is false
			messageTree.add(requireNonNull(items.getResultOrNull()));
			LiveResult<List<I>> result =
					new LiveResult<>(messageTree.depthFirstOrder());
			this.items.setValue(result);
		}
	}

	/**
	 * Add a remote item on the UI thread.
	 *
	 * @param scrollToItem whether the list will scroll to the newly added item
	 */
	@UiThread
	protected void addItem(I item, boolean scrollToItem) {
		// If items haven't loaded, we need to wait until they have.
		// Since this was a R/W DB transaction, the load will pick up this item.
		if (items.getValue() == null) return;

		messageTree.add(item);
		if (scrollToItem) this.scrollToItem.set(item.getId());
		items.setValue(new LiveResult<>(messageTree.depthFirstOrder()));
	}

	@UiThread
	void setReplyId(@Nullable MessageId id) {
		replyId = id;
	}

	@UiThread
	@Nullable
	MessageId getReplyId() {
		return replyId;
	}

	@UiThread
	void storeMessageId(@Nullable MessageId messageId) {
		if (messageId != null) {
			runOnDbThread(() -> {
				try {
					messageTracker.storeMessageId(groupId, messageId);
				} catch (NoSuchGroupException e) {
					// This can happen when the activity is closed
					// after deleting the group. So just ignore this case.
				} catch (DbException e) {
					handleException(e);
				}
			});
		}
	}

	protected abstract void markItemRead(I item);

	/**
	 * Returns the {@link MessageId} of the item that was at the top of the
	 * list last time or null if there has been nothing stored, yet.
	 */
	@Nullable
	MessageId getAndResetRestoredMessageId() {
		return storedMessageId.getAndSet(null);
	}

	LiveData<LiveResult<List<I>>> getItems() {
		return items;
	}

	LiveData<SharingInfo> getSharingInfo() {
		return sharingController.getSharingInfo();
	}

	LiveData<Boolean> getGroupRemoved() {
		return groupRemoved;
	}

	@Nullable
	MessageId getAndResetScrollToItem() {
		return scrollToItem.getAndSet(null);
	}

}
