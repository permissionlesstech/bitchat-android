package org.briarproject.briar.android.navdrawer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import org.briarproject.bramble.api.network.NetworkStatus;
import org.briarproject.bramble.api.plugin.BluetoothConstants;
import org.briarproject.bramble.api.plugin.LanTcpConstants;
import org.briarproject.bramble.api.plugin.Plugin.State;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.api.plugin.Plugin.State.DISABLED;
import static org.briarproject.bramble.api.plugin.Plugin.State.ENABLING;
import static org.briarproject.bramble.api.plugin.Plugin.State.STARTING_STOPPING;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_BATTERY;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_COUNTRY_BLOCKED;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_MOBILE_DATA;
import static org.briarproject.briar.android.util.PermissionUtils.areBluetoothPermissionsGranted;
import static org.briarproject.briar.android.util.PermissionUtils.requestBluetoothPermissions;
import static org.briarproject.briar.android.util.PermissionUtils.showDenialDialog;
import static org.briarproject.briar.android.util.PermissionUtils.showRationale;
import static org.briarproject.briar.android.util.PermissionUtils.wasGrantedBluetoothPermissions;
import static org.briarproject.briar.android.util.UiUtils.showOnboardingDialog;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TransportsActivity extends BriarActivity {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private final List<Transport> transports = new ArrayList<>(3);

	private PluginViewModel viewModel;
	private BaseAdapter transportsAdapter;

	@RequiresApi(31)
	private final ActivityResultLauncher<String[]> requestPermissionLauncher =
			registerForActivityResult(new RequestMultiplePermissions(),
					this::handleBtPermissionResult);

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(PluginViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setHomeButtonEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		setContentView(R.layout.activity_transports);

		GridView grid = findViewById(R.id.grid);
		initializeCards();
		grid.setAdapter(transportsAdapter);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_help) {
			String text = getString(R.string.transports_help_text);
			showOnboardingDialog(this, text);
			return true;
		}
		return false;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.help_action, menu);
		return super.onCreateOptionsMenu(menu);
	}

	private void initializeCards() {
		transportsAdapter = new BaseAdapter() {

			@Override
			public int getCount() {
				return transports.size();
			}

			@Override
			public Transport getItem(int position) {
				return transports.get(position);
			}

			@Override
			public long getItemId(int position) {
				return 0;
			}

			@Override
			public View getView(int position, View convertView,
					ViewGroup parent) {
				View view;
				if (convertView != null) {
					view = convertView;
				} else {
					LayoutInflater inflater = getLayoutInflater();
					view = inflater.inflate(R.layout.list_item_transport_card,
							parent, false);
				}

				Transport t = getItem(position);

				ImageView icon = view.findViewById(R.id.icon);
				icon.setImageResource(t.iconDrawable);
				icon.setColorFilter(ContextCompat.getColor(
						TransportsActivity.this, t.iconColor));

				TextView title = view.findViewById(R.id.title);
				title.setText(getString(t.title));

				SwitchCompat switchCompat =
						view.findViewById(R.id.switchCompat);
				switchCompat.setText(getString(t.switchLabel));
				switchCompat.setOnClickListener(v ->
						onClicked(t.id, switchCompat.isChecked()));
				switchCompat.setChecked(t.isSwitchChecked);

				TextView summary = view.findViewById(R.id.summary);
				if (t.summary == 0) {
					summary.setVisibility(GONE);
				} else {
					summary.setText(t.summary);
					summary.setVisibility(VISIBLE);
				}

				TextView deviceStatus = view.findViewById(R.id.deviceStatus);
				deviceStatus.setText(getBulletString(t.deviceStatus));

				TextView pluginStatus = view.findViewById(R.id.appStatus);
				pluginStatus.setText(getBulletString(t.pluginStatus));
				pluginStatus.setVisibility(t.showPluginStatus ? VISIBLE : GONE);

				return view;
			}
		};

		Transport tor = createTransport(TorConstants.ID,
				R.drawable.transport_tor, R.string.transport_tor,
				R.string.tor_enable_title, R.string.tor_enable_summary,
				R.string.tor_device_status_offline,
				R.string.tor_plugin_status_inactive);
		transports.add(tor);

		Transport wifi = createTransport(LanTcpConstants.ID,
				R.drawable.transport_lan, R.string.transport_lan_long,
				R.string.wifi_setting, 0, R.string.lan_device_status_off,
				R.string.lan_plugin_status_inactive);
		transports.add(wifi);

		Transport bt = createTransport(BluetoothConstants.ID,
				R.drawable.transport_bt, R.string.transport_bt,
				R.string.bluetooth_setting, 0, R.string.bt_device_status_off,
				R.string.bt_plugin_status_inactive);
		transports.add(bt);

		viewModel.getNetworkStatus().observe(this, status -> {
			updateTorResources(tor, status);
			updateWifiResources(wifi, status);
			transportsAdapter.notifyDataSetChanged();
		});

		viewModel.getBluetoothTurnedOn().observe(this, on -> {
			updateBtResources(bt, on);
			transportsAdapter.notifyDataSetChanged();
		});
	}

	private void onClicked(TransportId transportId, boolean enable) {
		if (transportId.equals(BluetoothConstants.ID) && enable
				&& SDK_INT >= 31 && !areBluetoothPermissionsGranted(this)) {
			if (shouldShowRequestPermissionRationale(BLUETOOTH_CONNECT)) {
				showRationale(this, R.string.permission_bluetooth_title,
						R.string.permission_bluetooth_body,
						this::requestBtPermissions);
			} else {
				requestBtPermissions();
			}
		} else {
			viewModel.enableTransport(transportId, enable);
		}
	}

	private String getBulletString(@StringRes int resId) {
		return "\u2022 " + getString(resId);
	}

	@ColorRes
	private int getIconColor(State state) {
		if (state == ACTIVE) return R.color.briar_lime_400;
		else if (state == ENABLING) return R.color.briar_orange_500;
		else return android.R.color.tertiary_text_light;
	}

	private void updateTorResources(Transport tor, NetworkStatus status) {
		if (status.isConnected()) {
			if (status.isWifi()) {
				tor.deviceStatus = R.string.tor_device_status_online_wifi;
			} else {
				tor.deviceStatus = R.string.tor_device_status_online_mobile;
			}
			tor.showPluginStatus = true;
		} else {
			tor.deviceStatus = R.string.tor_device_status_offline;
			tor.showPluginStatus = false;
		}
	}

	private void updateWifiResources(Transport wifi, NetworkStatus status) {
		if (status.isWifi()) {
			wifi.deviceStatus = R.string.lan_device_status_on;
			wifi.showPluginStatus = true;
		} else {
			wifi.deviceStatus = R.string.lan_device_status_off;
			wifi.showPluginStatus = false;
		}
	}

	private void updateBtResources(Transport bt, boolean on) {
		if (on) {
			bt.deviceStatus = R.string.bt_device_status_on;
			bt.showPluginStatus = true;
		} else {
			bt.deviceStatus = R.string.bt_device_status_off;
			bt.showPluginStatus = false;
		}
	}

	@StringRes
	private int getPluginStatus(TransportId id, State state) {
		if (id.equals(TorConstants.ID)) {
			return getTorPluginStatus(state);
		} else if (id.equals(LanTcpConstants.ID)) {
			return getWifiPluginStatus(state);
		} else if (id.equals(BluetoothConstants.ID)) {
			return getBtPluginStatus(state);
		} else throw new AssertionError();
	}

	@StringRes
	private int getTorPluginStatus(State state) {
		if (state == ENABLING) {
			return R.string.tor_plugin_status_enabling;
		} else if (state == ACTIVE) {
			return R.string.tor_plugin_status_active;
		} else if (state == DISABLED) {
			int reasons = viewModel.getReasonsTorDisabled();
			if ((reasons & REASON_MOBILE_DATA) != 0) {
				return R.string.tor_plugin_status_disabled_mobile_data;
			} else if ((reasons & REASON_BATTERY) != 0) {
				return R.string.tor_plugin_status_disabled_battery;
			} else if ((reasons & REASON_COUNTRY_BLOCKED) != 0) {
				return R.string.tor_plugin_status_disabled_country_blocked;
			} else {
				return R.string.tor_plugin_status_disabled;
			}
		} else {
			return R.string.tor_plugin_status_inactive;
		}
	}

	@StringRes
	private int getWifiPluginStatus(State state) {
		if (state == ENABLING) return R.string.lan_plugin_status_enabling;
		else if (state == ACTIVE) return R.string.lan_plugin_status_active;
		else if (state == DISABLED) return R.string.lan_plugin_status_disabled;
		else return R.string.lan_plugin_status_inactive;
	}

	@StringRes
	private int getBtPluginStatus(State state) {
		if (state == ENABLING) return R.string.bt_plugin_status_enabling;
		else if (state == ACTIVE) return R.string.bt_plugin_status_active;
		else if (state == DISABLED) return R.string.bt_plugin_status_disabled;
		else return R.string.bt_plugin_status_inactive;
	}

	private Transport createTransport(TransportId id,
			@DrawableRes int iconDrawable, @StringRes int title,
			@StringRes int switchLabel, @StringRes int summary,
			@StringRes int deviceStatus, @StringRes int pluginStatus) {
		int iconColor = getIconColor(STARTING_STOPPING);
		Transport transport = new Transport(id, iconDrawable, iconColor, title,
				switchLabel, false, summary, deviceStatus, pluginStatus, false);
		viewModel.getPluginState(id).observe(this, state -> {
			transport.iconColor = getIconColor(state);
			transport.pluginStatus = getPluginStatus(transport.id, state);
			transportsAdapter.notifyDataSetChanged();
		});
		viewModel.getPluginEnabledSetting(id).observe(this, enabled -> {
			transport.isSwitchChecked = enabled;
			transportsAdapter.notifyDataSetChanged();
		});
		return transport;
	}

	@RequiresApi(31)
	private void requestBtPermissions() {
		requestBluetoothPermissions(requestPermissionLauncher);
	}

	@RequiresApi(31)
	private void handleBtPermissionResult(Map<String, Boolean> grantedMap) {
		if (wasGrantedBluetoothPermissions(this, grantedMap)) {
			viewModel.enableTransport(BluetoothConstants.ID, true);
		} else {
			// update adapter to reflect the "off" toggle state after denying
			transportsAdapter.notifyDataSetChanged();
			showDenialDialog(this,
					R.string.permission_bluetooth_title,
					R.string.permission_bluetooth_denied_body);
		}
	}

	private static class Transport {

		private final TransportId id;

		@DrawableRes
		private final int iconDrawable;
		@StringRes
		private final int title, switchLabel, summary;

		@ColorRes
		private int iconColor;
		@StringRes
		private int deviceStatus, pluginStatus;
		private boolean isSwitchChecked, showPluginStatus;

		private Transport(TransportId id,
				@DrawableRes int iconDrawable,
				@ColorRes int iconColor,
				@StringRes int title,
				@StringRes int switchLabel,
				boolean isSwitchChecked,
				@StringRes int summary,
				@StringRes int deviceStatus,
				@StringRes int pluginStatus,
				boolean showPluginStatus) {
			this.id = id;
			this.iconDrawable = iconDrawable;
			this.iconColor = iconColor;
			this.title = title;
			this.switchLabel = switchLabel;
			this.isSwitchChecked = isSwitchChecked;
			this.summary = summary;
			this.deviceStatus = deviceStatus;
			this.pluginStatus = pluginStatus;
			this.showPluginStatus = showPluginStatus;
		}
	}
}
