package org.briarproject.briar.android.introduction;

import android.app.Application;
import android.widget.Toast;

import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.R;
import org.briarproject.briar.android.contact.ContactItem;
import org.briarproject.briar.android.contact.ContactsViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.introduction.IntroductionManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;

@NotNullByDefault
class IntroductionViewModel extends ContactsViewModel {

	private static final Logger LOG =
			getLogger(IntroductionViewModel.class.getName());

	private final ContactManager contactManager;
	private final AuthorManager authorManager;
	private final IntroductionManager introductionManager;

	@Inject
	IntroductionViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, ContactManager contactManager,
			AuthorManager authorManager,
			ConversationManager conversationManager,
			ConnectionRegistry connectionRegistry, EventBus eventBus,
			IntroductionManager introductionManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor,
				contactManager, authorManager, conversationManager,
				connectionRegistry, eventBus);
		this.contactManager = contactManager;
		this.authorManager = authorManager;
		this.introductionManager = introductionManager;
	}

	/*
	 * This is the contact from whose conversation we started the introduction
	 * using the menu item.
	 */
	@Nullable
	private ContactId firstContactId;
	/*
	 * This is the contact we selected from the list of contacts as a second
	 * contact for the introduction.
	 */
	@Nullable
	private ContactId secondContactId;

	private final MutableLiveEvent<Boolean> secondContactSelected =
			new MutableLiveEvent<>();

	private final MutableLiveData<IntroductionInfo> introductionInfo =
			new MutableLiveData<>();

	void setFirstContactId(ContactId contactId) {
		this.firstContactId = contactId;
		loadContacts();
	}

	@Nullable
	ContactId getSecondContactId() {
		return secondContactId;
	}

	void setSecondContactId(ContactId contactId) {
		secondContactId = contactId;
		// Setting this to null here so that IntroductionMessageFragment can
		// tell whether the correct value has been loaded from the database when
		// selecting a second contact repeatedly.
		introductionInfo.setValue(null);
		loadIntroductionInfo();
	}

	/**
	 * Trigger the event that the second contact has been selected from the
	 * contact list by the user.
	 */
	void triggerContactSelected() {
		secondContactSelected.setEvent(true);
	}

	/**
	 * This event will be triggered once the second contact has been selected
	 * from the list of contacts displayed. It is not fired when the second
	 * contact gets restored from the saved instance state.
	 */
	LiveEvent<Boolean> getSecondContactSelected() {
		return secondContactSelected;
	}

	/**
	 * Holder for the introduction info object with data about both contacts
	 * and whether the introduction is possible. May wrap null if the data
	 * is not available yet. This happens when it is reset by selecting a
	 * contact with the same view model instance more than once.
	 */
	LiveData<IntroductionInfo> getIntroductionInfo() {
		return introductionInfo;
	}

	@Override
	protected boolean displayContact(ContactId contactId) {
		return !requireNonNull(firstContactId).equals(contactId);
	}

	private void loadIntroductionInfo() {
		final ContactId firstContactId = requireNonNull(this.firstContactId);
		final ContactId secondContactId = requireNonNull(this.secondContactId);
		runOnDbThread(() -> {
			try {
				Contact firstContact =
						contactManager.getContact(firstContactId);
				Contact secondContact =
						contactManager.getContact(secondContactId);
				AuthorInfo a1 = authorManager.getAuthorInfo(firstContact);
				AuthorInfo a2 = authorManager.getAuthorInfo(secondContact);
				boolean possible = introductionManager
						.canIntroduce(firstContact, secondContact);
				ContactItem c1 = new ContactItem(firstContact, a1);
				ContactItem c2 = new ContactItem(secondContact, a2);
				introductionInfo.postValue(
						new IntroductionInfo(c1, c2, possible));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void makeIntroduction(@Nullable String text) {
		final IntroductionInfo info =
				requireNonNull(introductionInfo.getValue());
		runOnDbThread(() -> {
			// actually make the introduction
			try {
				introductionManager.makeIntroduction(
						info.getContact1().getContact(),
						info.getContact2().getContact(), text);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				androidExecutor.runOnUiThread(() -> Toast.makeText(
						getApplication(), R.string.introduction_error,
						LENGTH_SHORT).show());
			}
		});
	}

}
