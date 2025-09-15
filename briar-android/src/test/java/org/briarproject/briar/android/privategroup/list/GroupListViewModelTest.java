package org.briarproject.briar.android.privategroup.list;

import android.app.Application;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.briarproject.briar.android.AndroidExecutorTestImpl;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.event.GroupDissolvedEvent;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationItem;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getContact;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.briar.android.viewmodel.LiveDataTestUtil.getOrAwaitValue;
import static org.briarproject.briar.api.client.MessageTracker.GroupCount;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager.CLIENT_ID;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager.MAJOR_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GroupListViewModelTest extends BrambleMockTestCase {

	@Rule
	public final InstantTaskExecutorRule testRule =
			new InstantTaskExecutorRule();

	private final LifecycleManager lifecycleManager =
			context.mock(LifecycleManager.class);
	private final TransactionManager db =
			context.mock(TransactionManager.class);
	private final PrivateGroupManager groupManager =
			context.mock(PrivateGroupManager.class);
	private final GroupInvitationManager groupInvitationManager =
			context.mock(GroupInvitationManager.class);
	private final AuthorManager authorManager =
			context.mock(AuthorManager.class);
	private final AndroidNotificationManager notificationManager =
			context.mock(AndroidNotificationManager.class);
	private final EventBus eventBus = context.mock(EventBus.class);

	private final GroupListViewModel viewModel;


	private final Group g1 = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Group g2 = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final PrivateGroup privateGroup1 =
			new PrivateGroup(g1, "foo", getAuthor(), getRandomBytes(2));
	private final PrivateGroup privateGroup2 =
			new PrivateGroup(g2, "bar", getAuthor(), getRandomBytes(2));
	private final AuthorInfo authorInfo1 =
			new AuthorInfo(AuthorInfo.Status.UNVERIFIED);
	private final AuthorInfo authorInfo2 =
			new AuthorInfo(AuthorInfo.Status.VERIFIED);

	private final GroupCount groupCount1 = new GroupCount(2, 1, 23L);
	private final GroupCount groupCount2 = new GroupCount(5, 3, 42L);
	private final GroupItem item1 =
			new GroupItem(privateGroup1, authorInfo1, groupCount1, false);
	private final GroupItem item2 =
			new GroupItem(privateGroup2, authorInfo2, groupCount2, false);

	public GroupListViewModelTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		Application app = context.mock(Application.class);
		context.checking(new Expectations() {{
			oneOf(eventBus).addListener(with(any(EventListener.class)));
		}});
		Executor dbExecutor = new ImmediateExecutor();
		AndroidExecutor androidExecutor =
				new AndroidExecutorTestImpl(dbExecutor);
		viewModel = new GroupListViewModel(app, dbExecutor, lifecycleManager,
				db, androidExecutor, groupManager, groupInvitationManager,
				authorManager, notificationManager, eventBus);
	}

	@Test
	public void testLoadGroupsException() throws Exception {
		DbException dbException = new DbException();

		Transaction txn = new Transaction(null, true);
		context.checking(new DbExpectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(db).transaction(with(true), withDbRunnable(txn));
			oneOf(groupManager).getPrivateGroups(txn);
			will(throwException(dbException));
		}});

		viewModel.loadGroups();

		LiveResult<List<GroupItem>> result =
				getOrAwaitValue(viewModel.getGroupItems());
		assertTrue(result.hasError());
		assertEquals(dbException, result.getException());
		assertNull(result.getResultOrNull());
	}

	@Test
	public void testLoadGroups() throws Exception {
		Transaction txn = new Transaction(null, true);
		context.checking(new DbExpectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(db).transaction(with(true), withDbRunnable(txn));
			oneOf(groupManager).getPrivateGroups(txn);
			will(returnValue(Arrays.asList(privateGroup1, privateGroup2)));
		}});
		expectLoadGroup(txn, privateGroup1, authorInfo1, groupCount1, false);
		expectLoadGroup(txn, privateGroup2, authorInfo2, groupCount2, false);

		viewModel.loadGroups();

		// unpack updated live data
		LiveResult<List<GroupItem>> result =
				getOrAwaitValue(viewModel.getGroupItems());
		assertFalse(result.hasError());
		List<GroupItem> liveList = result.getResultOrNull();
		assertNotNull(liveList);
		// list is sorted by last message timestamp
		assertEquals(Arrays.asList(item2, item1), liveList);

		// group 1 gets dissolved by creator
		Event dissolvedEvent = new GroupDissolvedEvent(privateGroup1.getId());
		viewModel.eventOccurred(dissolvedEvent);
		result = getOrAwaitValue(viewModel.getGroupItems());
		liveList = result.getResultOrNull();
		assertNotNull(liveList);
		assertEquals(2, liveList.size());
		// assert that list update includes dissolved group item
		for (GroupItem item : liveList) {
			if (item.getId().equals(privateGroup1.getId())) {
				assertTrue(item.isDissolved());
			} else if (item.getId().equals(privateGroup2.getId())) {
				assertFalse(item.isDissolved());
			} else fail();
		}
	}

	@Test
	public void testLoadNumInvitations() throws Exception {
		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(groupInvitationManager).getInvitations();
			will(returnValue(emptyList()));
		}});
		viewModel.loadNumInvitations();

		int num = getOrAwaitValue(viewModel.getNumInvitations());
		assertEquals(0, num);

		PrivateGroup pg = context.mock(PrivateGroup.class);
		Contact c = getContact();
		GroupInvitationItem item = new GroupInvitationItem(pg, c);
		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(groupInvitationManager).getInvitations();
			will(returnValue(singletonList(item)));
		}});
		viewModel.loadNumInvitations();

		num = getOrAwaitValue(viewModel.getNumInvitations());
		assertEquals(1, num);
	}

	private void expectLoadGroup(Transaction txn, PrivateGroup privateGroup,
			AuthorInfo authorInfo, GroupCount groupCount, boolean dissolved)
			throws DbException {
		context.checking(new DbExpectations() {{
			oneOf(authorManager)
					.getAuthorInfo(txn, privateGroup.getCreator().getId());
			will(returnValue(authorInfo));
			oneOf(groupManager).getGroupCount(txn, privateGroup.getId());
			will(returnValue(groupCount));
			oneOf(groupManager).isDissolved(txn, privateGroup.getId());
			will(returnValue(dissolved));
		}});
	}

}
