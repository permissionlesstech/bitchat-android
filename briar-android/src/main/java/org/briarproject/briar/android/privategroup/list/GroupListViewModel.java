package org.briarproject.briar.android.privategroup.list;

import android.app.Application;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.event.GroupAddedEvent;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.privategroup.GroupMessageHeader;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.event.GroupDissolvedEvent;
import org.briarproject.briar.api.privategroup.event.GroupInvitationRequestReceivedEvent;
import org.briarproject.briar.api.privategroup.event.GroupMessageAddedEvent;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.Objects.requireNonNull;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.now;
import static org.briarproject.briar.api.privategroup.PrivateGroupManager.CLIENT_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class GroupListViewModel extends DbViewModel implements EventListener {

	private static final Logger LOG =
			getLogger(GroupListViewModel.class.getName());

	private final PrivateGroupManager groupManager;
	private final GroupInvitationManager groupInvitationManager;
	private final AuthorManager authorManager;
	private final AndroidNotificationManager notificationManager;
	private final EventBus eventBus;

	private final MutableLiveData<LiveResult<List<GroupItem>>> groupItems =
			new MutableLiveData<>();
	private final MutableLiveData<Integer> numInvitations =
			new MutableLiveData<>();

	@Inject
	GroupListViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			PrivateGroupManager groupManager,
			GroupInvitationManager groupInvitationManager,
			AuthorManager authorManager,
			AndroidNotificationManager notificationManager, EventBus eventBus) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.groupManager = groupManager;
		this.groupInvitationManager = groupInvitationManager;
		this.authorManager = authorManager;
		this.notificationManager = notificationManager;
		this.eventBus = eventBus;
		this.eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	void clearAllGroupMessageNotifications() {
		notificationManager.clearAllGroupMessageNotifications();
	}

	void blockAllGroupMessageNotifications() {
		notificationManager.blockAllGroupMessageNotifications();
	}

	void unblockAllGroupMessageNotifications() {
		notificationManager.unblockAllGroupMessageNotifications();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof GroupMessageAddedEvent) {
			GroupMessageAddedEvent g = (GroupMessageAddedEvent) e;
			LOG.info("Private group message added");
			onGroupMessageAdded(g.getHeader());
		} else if (e instanceof GroupInvitationRequestReceivedEvent) {
			LOG.info("Private group invitation received");
			loadNumInvitations();
		} else if (e instanceof GroupAddedEvent) {
			GroupAddedEvent g = (GroupAddedEvent) e;
			ClientId id = g.getGroup().getClientId();
			if (id.equals(CLIENT_ID)) {
				LOG.info("Private group added");
				loadGroups();
			}
		} else if (e instanceof GroupRemovedEvent) {
			GroupRemovedEvent g = (GroupRemovedEvent) e;
			ClientId id = g.getGroup().getClientId();
			if (id.equals(CLIENT_ID)) {
				LOG.info("Private group removed");
				onGroupRemoved(g.getGroup().getId());
			}
		} else if (e instanceof GroupDissolvedEvent) {
			GroupDissolvedEvent g = (GroupDissolvedEvent) e;
			LOG.info("Private group dissolved");
			onGroupDissolved(g.getGroupId());
		}
	}

	void loadGroups() {
		loadFromDb(this::loadGroups, groupItems::setValue);
	}

	@DatabaseExecutor
	private List<GroupItem> loadGroups(Transaction txn) throws DbException {
		long start = now();
		Collection<PrivateGroup> groups = groupManager.getPrivateGroups(txn);
		List<GroupItem> items = new ArrayList<>(groups.size());
		Map<AuthorId, AuthorInfo> authorInfos = new HashMap<>();
		for (PrivateGroup g : groups) {
			GroupId id = g.getId();
			AuthorId authorId = g.getCreator().getId();
			AuthorInfo authorInfo;
			if (authorInfos.containsKey(authorId)) {
				authorInfo = requireNonNull(authorInfos.get(authorId));
			} else {
				authorInfo = authorManager.getAuthorInfo(txn, authorId);
				authorInfos.put(authorId, authorInfo);
			}
			GroupCount count = groupManager.getGroupCount(txn, id);
			boolean dissolved = groupManager.isDissolved(txn, id);
			items.add(new GroupItem(g, authorInfo, count, dissolved));
		}
		Collections.sort(items);
		logDuration(LOG, "Loading groups", start);
		return items;
	}

	@UiThread
	private void onGroupMessageAdded(GroupMessageHeader header) {
		GroupId g = header.getGroupId();
		List<GroupItem> list = updateListItems(getList(groupItems),
				itemToTest -> itemToTest.getId().equals(g),
				itemToUpdate -> new GroupItem(itemToUpdate, header));
		if (list == null) return;
		// re-sort as the order of items may have changed
		Collections.sort(list);
		groupItems.setValue(new LiveResult<>(list));
	}

	@UiThread
	private void onGroupDissolved(GroupId groupId) {
		List<GroupItem> list = updateListItems(getList(groupItems),
				itemToTest -> itemToTest.getId().equals(groupId),
				itemToUpdate -> new GroupItem(itemToUpdate, true));
		if (list == null) return;
		groupItems.setValue(new LiveResult<>(list));
	}

	@UiThread
	private void onGroupRemoved(GroupId groupId) {
		removeAndUpdateListItems(groupItems, i -> i.getId().equals(groupId));
	}

	void removeGroup(GroupId g) {
		runOnDbThread(() -> {
			try {
				long start = now();
				groupManager.removePrivateGroup(g);
				logDuration(LOG, "Removing group", start);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void loadNumInvitations() {
		runOnDbThread(() -> {
			try {
				int i = groupInvitationManager.getInvitations().size();
				numInvitations.postValue(i);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<LiveResult<List<GroupItem>>> getGroupItems() {
		return groupItems;
	}

	LiveData<Integer> getNumInvitations() {
		return numInvitations;
	}
}
