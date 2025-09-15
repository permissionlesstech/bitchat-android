package org.briarproject.briar.android.contact.connect;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static android.widget.Toast.LENGTH_LONG;
import static java.util.Objects.requireNonNull;
import static org.briarproject.briar.android.conversation.ConversationActivity.CONTACT_ID;
import static org.briarproject.briar.android.util.UiUtils.showFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConnectViaBluetoothActivity extends BriarActivity {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ConnectViaBluetoothViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);

		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ConnectViaBluetoothViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Intent intent = requireNonNull(getIntent());
		int contactId = intent.getIntExtra(CONTACT_ID, -1);
		if (contactId == -1) throw new IllegalArgumentException("ContactId");
		viewModel.setContactId(new ContactId(contactId));

		setContentView(R.layout.activity_fragment_container);

		viewModel.getState().observeEvent(this, this::onStateChanged);

		if (savedInstanceState == null) {
			Fragment f = new BluetoothIntroFragment();
			String tag = BluetoothIntroFragment.TAG;
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.fragmentContainer, f, tag)
					.commitNow();
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.reset();
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void onStateChanged(ConnectViaBluetoothState state) {
		if (state instanceof ConnectViaBluetoothState.Connecting) {
			Fragment f = new BluetoothProgressFragment();
			String tag = BluetoothProgressFragment.TAG;
			showFragment(getSupportFragmentManager(), f, tag, false);
		} else if (state instanceof ConnectViaBluetoothState.Success) {
			Toast.makeText(this, R.string.connect_via_bluetooth_success,
					LENGTH_LONG).show();
			supportFinishAfterTransition();
		} else if (state instanceof ConnectViaBluetoothState.Error) {
			Toast.makeText(this,
					((ConnectViaBluetoothState.Error) state).errorRes,
					LENGTH_LONG).show();
			supportFinishAfterTransition();
		} else throw new AssertionError();
	}

}
