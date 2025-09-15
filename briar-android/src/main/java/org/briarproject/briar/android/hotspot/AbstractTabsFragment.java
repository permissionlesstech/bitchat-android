package org.briarproject.briar.android.hotspot;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import static androidx.core.app.ActivityCompat.finishAfterTransition;
import static org.briarproject.briar.android.AppModule.getAndroidComponent;
import static org.briarproject.briar.android.util.UiUtils.showFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class AbstractTabsFragment extends Fragment {

	static String ARG_FOR_WIFI_CONNECT = "forWifiConnect";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	protected HotspotViewModel viewModel;

	protected Button stopButton;
	protected Button connectedButton;
	protected TextView connectedView;

	@Override
	public void onAttach(Context context) {
		super.onAttach(context);
		getAndroidComponent(requireContext()).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(HotspotViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		setHasOptionsMenu(true);
		return inflater
				.inflate(R.layout.fragment_hotspot_tabs, container, false);
	}

	@Override
	@CallSuper
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		TabAdapter tabAdapter = new TabAdapter(this);
		ViewPager2 viewPager = view.findViewById(R.id.pager);
		viewPager.setAdapter(tabAdapter);
		TabLayout tabLayout = view.findViewById(R.id.tabLayout);
		new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
			// tabs are set in XML, but are just dummies that don't get added
			if (position == 0) {
				tab.setText(R.string.hotspot_tab_manual);
				tab.setIcon(R.drawable.forum_item_create_white);
			} else if (position == 1) {
				tab.setText(R.string.qr_code);
				tab.setIcon(R.drawable.ic_qr_code);
			} else throw new AssertionError();
		}).attach();

		stopButton = view.findViewById(R.id.stopButton);
		stopButton.setOnClickListener(v -> {
			// also clears hotspot
			finishAfterTransition(requireActivity());
		});
		connectedButton = view.findViewById(R.id.connectedButton);
		connectedView = view.findViewById(R.id.connectedView);
		viewModel.getPeersConnectedEvent()
				.observe(getViewLifecycleOwner(), this::onPeerConnected);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.hotspot_help_action, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_help) {
			Fragment f = new HotspotHelpFragment();
			String tag = HotspotHelpFragment.TAG;
			showFragment(getParentFragmentManager(), f, tag);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	protected abstract Fragment getFirstFragment();

	protected abstract Fragment getSecondFragment();

	private class TabAdapter extends FragmentStateAdapter {
		private TabAdapter(Fragment fragment) {
			super(fragment);
		}

		@Override
		public Fragment createFragment(int position) {
			if (position == 0) return getFirstFragment();
			if (position == 1) return getSecondFragment();
			throw new AssertionError();
		}

		@Override
		public int getItemCount() {
			return 2;
		}
	}

	private void onPeerConnected(int peers) {
		if (peers == 0) {
			connectedView.setText(R.string.hotspot_no_peers_connected);
		} else {
			connectedView.setText(getResources().getQuantityString(
					R.plurals.hotspot_peers_connected, peers, peers));
		}
	}

}
