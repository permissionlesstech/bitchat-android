package org.briarproject.briar.android.sharing;

import android.content.Intent;
import android.os.Bundle;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.android.contactselection.ContactSelectorActivity;
import org.briarproject.briar.android.sharing.BaseMessageFragment.MessageFragmentListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class ShareActivity extends ContactSelectorActivity
		implements MessageFragmentListener {

	@Override
	public void onCreate(@Nullable Bundle bundle) {
		super.onCreate(bundle);

		Intent i = getIntent();
		byte[] b = i.getByteArrayExtra(GROUP_ID);
		if (b == null) throw new IllegalStateException("No GroupId");
		groupId = new GroupId(b);
	}

	@UiThread
	@Override
	public void contactsSelected(Collection<ContactId> contacts) {
		super.contactsSelected(contacts);
		showNextFragment(getMessageFragment());
	}

	abstract BaseMessageFragment getMessageFragment();

	@UiThread
	@Override
	public void onButtonClick(@Nullable String text) {
		share(contacts, text);
		setResult(RESULT_OK);
		supportFinishAfterTransition();
	}

	abstract void share(Collection<ContactId> contacts, @Nullable String text);

}
