package org.briarproject.briar.android.hotspot;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.fragment.BaseFragment.BaseFragmentListener;
import org.briarproject.briar.android.hotspot.HotspotState.HotspotError;
import org.briarproject.briar.android.hotspot.HotspotState.HotspotStarted;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import static org.briarproject.briar.android.util.UiUtils.showFragment;
import static org.briarproject.briar.api.android.AndroidNotificationManager.ACTION_STOP_HOTSPOT;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class HotspotActivity extends BriarActivity
		implements BaseFragmentListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private HotspotViewModel viewModel;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(HotspotViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_fragment_container);

		ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		FragmentManager fm = getSupportFragmentManager();
		viewModel.getState().observe(this, hotspotState -> {
			if (hotspotState instanceof HotspotStarted) {
				HotspotStarted started = (HotspotStarted) hotspotState;
				String tag = HotspotFragment.TAG;
				if (started.wasNotYetConsumed()) {
					started.consume();
					showFragment(fm, new HotspotFragment(), tag);
				}
			} else if (hotspotState instanceof HotspotError) {
				HotspotError error = (HotspotError) hotspotState;
				showErrorFragment(error.getError());
			}
		});

		if (savedInstanceState == null) {
			// If there is no saved instance state, just start with the intro fragment.
			fm.beginTransaction()
					.replace(R.id.fragmentContainer, new HotspotIntroFragment(),
							HotspotIntroFragment.TAG)
					.commit();
		} else if (viewModel.getState().getValue() == null) {
			// If there is saved instance state, then there's either been an
			// configuration change like rotated device or the activity has been
			// destroyed and is now being re-created.
			// In the latter case, the view model will have been destroyed, too.
			// The activity can only have been destroyed if the user navigated
			// away from the HotspotActivity which is nothing we
			// intend to support, so we want to detect that and start from scratch
			// in this case. We need to clean up existing fragments in order not
			// to stack new fragments on top of old ones.

			// If it is a configuration change and we moved past the intro
			// fragment already, then the view model state will be != null,
			// hence we can use this check for null to determine the destroyed
			// activity. It can also be null if the user has not pressed
			// "start sharing" yet, but in that case it won't harm to start from
			// scratch.

			Fragment current = fm.findFragmentById(R.id.fragmentContainer);
			if (current instanceof HotspotIntroFragment) {
				// If the currently displayed fragment is the intro fragment,
				// there's nothing we need to do.
				return;
			}

			// Remove everything from the back stack.
			fm.popBackStackImmediate(null,
					FragmentManager.POP_BACK_STACK_INCLUSIVE);

			// Start fresh with the intro fragment.
			fm.beginTransaction()
					.replace(R.id.fragmentContainer, new HotspotIntroFragment(),
							HotspotIntroFragment.TAG)
					.commit();
		}
	}

	private void showErrorFragment(String error) {
		FragmentManager fm = getSupportFragmentManager();
		String tag = HotspotErrorFragment.TAG;
		Fragment f = HotspotErrorFragment.newInstance(error);
		showFragment(fm, f, tag, false);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (ACTION_STOP_HOTSPOT.equals(intent.getAction())) {
			// also closes hotspot
			supportFinishAfterTransition();
		}
	}

}
