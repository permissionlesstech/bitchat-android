package org.briarproject.briar.android.privategroup.creation;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.android.contactselection.ContactSelectorControllerImpl;
import org.briarproject.briar.android.controller.handler.ResultExceptionHandler;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.GroupMessageFactory;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.briarproject.briar.api.sharing.SharingManager.SharingStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import androidx.annotation.Nullable;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@Immutable
@NotNullByDefault
class CreateGroupControllerImpl extends ContactSelectorControllerImpl
		implements CreateGroupController {

	private static final Logger LOG =
			getLogger(CreateGroupControllerImpl.class.getName());

	private final Executor cryptoExecutor;
	private final TransactionManager db;
	private final AutoDeleteManager autoDeleteManager;
	private final ConversationManager conversationManager;
	private final ContactManager contactManager;
	private final IdentityManager identityManager;
	private final PrivateGroupFactory groupFactory;
	private final GroupMessageFactory groupMessageFactory;
	private final PrivateGroupManager groupManager;
	private final GroupInvitationFactory groupInvitationFactory;
	private final GroupInvitationManager groupInvitationManager;
	private final Clock clock;

	@Inject
	CreateGroupControllerImpl(
			@DatabaseExecutor Executor dbExecutor,
			@CryptoExecutor Executor cryptoExecutor,
			TransactionManager db,
			AutoDeleteManager autoDeleteManager,
			ConversationManager conversationManager,
			LifecycleManager lifecycleManager,
			ContactManager contactManager,
			AuthorManager authorManager,
			IdentityManager identityManager,
			PrivateGroupFactory groupFactory,
			GroupMessageFactory groupMessageFactory,
			PrivateGroupManager groupManager,
			GroupInvitationFactory groupInvitationFactory,
			GroupInvitationManager groupInvitationManager,
			Clock clock) {
		super(dbExecutor, lifecycleManager, contactManager, authorManager);
		this.cryptoExecutor = cryptoExecutor;
		this.db = db;
		this.autoDeleteManager = autoDeleteManager;
		this.conversationManager = conversationManager;
		this.contactManager = contactManager;
		this.identityManager = identityManager;
		this.groupFactory = groupFactory;
		this.groupMessageFactory = groupMessageFactory;
		this.groupManager = groupManager;
		this.groupInvitationFactory = groupInvitationFactory;
		this.groupInvitationManager = groupInvitationManager;
		this.clock = clock;
	}

	@Override
	public void createGroup(String name,
			ResultExceptionHandler<GroupId, DbException> handler) {
		runOnDbThread(() -> {
			try {
				LocalAuthor author = identityManager.getLocalAuthor();
				createGroupAndMessages(author, name, handler);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				handler.onException(e);
			}
		});
	}

	private void createGroupAndMessages(LocalAuthor author, String name,
			ResultExceptionHandler<GroupId, DbException> handler) {
		cryptoExecutor.execute(() -> {
			LOG.info("Creating group...");
			PrivateGroup group =
					groupFactory.createPrivateGroup(name, author);
			LOG.info("Creating new join announcement...");
			GroupMessage joinMsg =
					groupMessageFactory.createJoinMessage(group.getId(),
							clock.currentTimeMillis(), author);
			storeGroup(group, joinMsg, handler);
		});
	}

	private void storeGroup(PrivateGroup group, GroupMessage joinMsg,
			ResultExceptionHandler<GroupId, DbException> handler) {
		runOnDbThread(() -> {
			LOG.info("Adding group to database...");
			try {
				groupManager.addPrivateGroup(group, joinMsg, true);
				handler.onResult(group.getId());
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				handler.onException(e);
			}
		});
	}

	@Override
	protected SharingStatus getSharingStatus(GroupId g, Contact c) throws DbException {
		return groupInvitationManager.getSharingStatus(c, g);
	}

	@Override
	public void sendInvitation(GroupId g, Collection<ContactId> contactIds,
			@Nullable String text,
			ResultExceptionHandler<Void, DbException> handler) {
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn -> {
					LocalAuthor localAuthor =
							identityManager.getLocalAuthor(txn);
					List<InvitationContext> contexts =
							createInvitationContexts(txn, contactIds);
					txn.attach(() -> signInvitations(g, localAuthor, contexts,
							text, handler));
				});
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				handler.onException(e);
			}
		});
	}

	private List<InvitationContext> createInvitationContexts(Transaction txn,
			Collection<ContactId> contactIds) throws DbException {
		List<InvitationContext> contexts = new ArrayList<>();
		for (ContactId c : contactIds) {
			try {
				Contact contact = contactManager.getContact(txn, c);
				long timestamp = conversationManager
						.getTimestampForOutgoingMessage(txn, c);
				long timer = autoDeleteManager.getAutoDeleteTimer(txn, c,
						timestamp);
				contexts.add(new InvitationContext(contact, timestamp, timer));
			} catch (NoSuchContactException e) {
				// Continue
			}
		}
		return contexts;
	}

	private void signInvitations(GroupId g, LocalAuthor localAuthor,
			List<InvitationContext> contexts, @Nullable String text,
			ResultExceptionHandler<Void, DbException> handler) {
		cryptoExecutor.execute(() -> {
			for (InvitationContext ctx : contexts) {
				ctx.signature = groupInvitationFactory.signInvitation(
						ctx.contact, g, ctx.timestamp,
						localAuthor.getPrivateKey());
			}
			sendInvitations(g, contexts, text, handler);
		});
	}

	private void sendInvitations(GroupId g,
			Collection<InvitationContext> contexts, @Nullable String text,
			ResultExceptionHandler<Void, DbException> handler) {
		runOnDbThread(() -> {
			try {
				for (InvitationContext ctx : contexts) {
					try {
						groupInvitationManager.sendInvitation(g,
								ctx.contact.getId(), text, ctx.timestamp,
								requireNonNull(ctx.signature),
								ctx.autoDeleteTimer);
					} catch (NoSuchContactException e) {
						// Continue
					}
				}
				handler.onResult(null);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				handler.onException(e);
			}
		});
	}

	private static class InvitationContext {

		private final Contact contact;
		private final long timestamp, autoDeleteTimer;
		@Nullable
		private byte[] signature = null;

		private InvitationContext(Contact contact, long timestamp,
				long autoDeleteTimer) {
			this.contact = contact;
			this.timestamp = timestamp;
			this.autoDeleteTimer = autoDeleteTimer;
		}
	}
}
