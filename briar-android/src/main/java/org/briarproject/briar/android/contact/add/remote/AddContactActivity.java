package org.briarproject.briar.android.contact.add.remote;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.fragment.BaseFragment.BaseFragmentListener;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.appcompat.app.ActionBar;
import androidx.lifecycle.ViewModelProvider;

import static android.content.Intent.ACTION_SEND;
import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.EXTRA_TEXT;
import static android.widget.Toast.LENGTH_LONG;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AddContactActivity extends BriarActivity implements
		BaseFragmentListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private AddContactViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(AddContactViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_fragment_container);

		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		viewModel.onCreate();
		viewModel.getRemoteLinkEntered().observeEvent(this, entered -> {
			if (entered) {
				NicknameFragment f = new NicknameFragment();
				showNextFragment(f);
			}
		});

		Intent i = getIntent();
		if (state == null) {
			// do not react to the intent again when recreating the activity
			onNewIntent(i);
		}

		if (state == null) {
			showInitialFragment(new LinkExchangeFragment());
		}
	}

	@Override
	protected void onNewIntent(Intent i) {
		super.onNewIntent(i);
		String action = i.getAction();
		if (ACTION_SEND.equals(action) || ACTION_VIEW.equals(action)) {
			String text = i.getStringExtra(EXTRA_TEXT);
			String uri = i.getDataString();
			if (text != null) handleIncomingLink(text);
			else if (uri != null) handleIncomingLink(uri);
		}
	}

	private void handleIncomingLink(String link) {
		if (link.equals(viewModel.getHandshakeLink().getValue())) {
			Toast.makeText(this, R.string.intent_own_link, LENGTH_LONG)
					.show();
		} else if (viewModel.isValidRemoteContactLink(link)) {
			viewModel.setRemoteHandshakeLink(link);
		} else {
			Toast.makeText(this, R.string.invalid_link, LENGTH_LONG)
					.show();
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

}
