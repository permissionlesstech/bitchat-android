package org.briarproject.briar.android.contact.connect;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;

import org.briarproject.bramble.api.connection.ConnectionManager;
import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.plugin.event.ConnectionOpenedEvent;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.plugin.bluetooth.BluetoothPlugin;
import org.briarproject.briar.R;
import org.briarproject.briar.android.contact.connect.ConnectViaBluetoothState.Connecting;
import org.briarproject.briar.android.contact.connect.ConnectViaBluetoothState.Success;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.plugin.BluetoothConstants.ID;
import static org.briarproject.bramble.api.plugin.BluetoothConstants.PROP_UUID;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.briar.android.util.PermissionUtils.areBluetoothPermissionsGranted;

@UiThread
@NotNullByDefault
class ConnectViaBluetoothViewModel extends DbViewModel implements
		EventListener {

	private final Logger LOG =
			getLogger(ConnectViaBluetoothViewModel.class.getName());

	private final long BT_ACTIVE_TIMEOUT = SECONDS.toMillis(5);

	private final PluginManager pluginManager;
	private final Executor ioExecutor;
	private final ConnectionRegistry connectionRegistry;
	@Nullable
	private final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
	private final EventBus eventBus;
	private final TransportPropertyManager transportPropertyManager;
	private final ConnectionManager connectionManager;

	@Nullable
	private volatile BluetoothPlugin bluetoothPlugin;
	@Nullable
	private ContactId contactId = null;

	private final MutableLiveEvent<ConnectViaBluetoothState> state =
			new MutableLiveEvent<>();

	@Inject
	ConnectViaBluetoothViewModel(
			Application app,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			PluginManager pluginManager,
			@IoExecutor Executor ioExecutor,
			ConnectionRegistry connectionRegistry,
			EventBus eventBus,
			TransportPropertyManager transportPropertyManager,
			ConnectionManager connectionManager) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.pluginManager = pluginManager;
		this.ioExecutor = ioExecutor;
		this.bluetoothPlugin = (BluetoothPlugin) pluginManager.getPlugin(ID);
		this.connectionRegistry = connectionRegistry;
		this.eventBus = eventBus;
		this.transportPropertyManager = transportPropertyManager;
		this.connectionManager = connectionManager;
	}

	@Override
	protected void onCleared() {
		stopConnecting();
	}

	/**
	 * Set this as soon as it becomes available.
	 */
	void setContactId(ContactId contactId) {
		this.contactId = contactId;
	}

	/**
	 * Call this when the using activity or fragment starts.
	 */
	void reset() {
		// When this class is instantiated before we are logged in
		// (like when returning to a killed activity), bluetoothPlugin would be
		// null and we consider bluetooth not supported. So reset here.
		bluetoothPlugin = (BluetoothPlugin) pluginManager.getPlugin(ID);
	}

	@UiThread
	boolean shouldStartFlow() {
		if (isBluetoothNotSupported()) {
			state.setEvent(new ConnectViaBluetoothState.Error(
					R.string.bt_plugin_status_inactive));
			return false;
		} else if (isConnectedViaBluetooth()) {
			state.setEvent(new Success());
			return false;
		} else if (isDiscovering()) {
			state.setEvent(new ConnectViaBluetoothState.Error(
					R.string.connect_via_bluetooth_already_discovering));
			return false;
		}
		return true;
	}

	private boolean isBluetoothNotSupported() {
		return bt == null || bluetoothPlugin == null;
	}

	private boolean isDiscovering() {
		// we should not be calling this if isBluetoothNotSupported() is true
		return requireNonNull(bluetoothPlugin).isDiscovering();
	}

	private boolean isConnectedViaBluetooth() {
		return connectionRegistry.isConnected(requireNonNull(contactId), ID);
	}

	@UiThread
	void onBluetoothDiscoverable() {
		ContactId contactId = requireNonNull(this.contactId);
		BluetoothPlugin bluetoothPlugin = requireNonNull(this.bluetoothPlugin);

		state.setEvent(new Connecting());

		bluetoothPlugin.disablePolling();
		pluginManager.setPluginEnabled(ID, true);
		ioExecutor.execute(() -> {
			try {
				if (!waitForBluetoothActive()) {
					state.postEvent(new ConnectViaBluetoothState.Error(
							R.string.bt_plugin_status_inactive));
					LOG.warning("Bluetooth plugin didn't become active");
					return;
				}
				eventBus.addListener(this);
				try {
					String uuid = null;
					try {
						uuid = transportPropertyManager
								.getRemoteProperties(contactId, ID)
								.get(PROP_UUID);
					} catch (DbException e) {
						logException(LOG, WARNING, e);
					}
					if (isNullOrEmpty(uuid)) {
						LOG.warning("PROP_UUID missing for contact");
						return;
					}
					DuplexTransportConnection conn = bluetoothPlugin
							.discoverAndConnectForSetup(uuid);
					if (conn == null) {
						waitAfterConnectionFailed();
					} else {
						LOG.info("Could connect, handling connection");
						connectionManager
								.manageOutgoingConnection(contactId, ID, conn);
						state.postEvent(new Success());
					}
				} finally {
					eventBus.removeListener(this);
				}
			} finally {
				bluetoothPlugin.enablePolling();
			}
		});
	}

	@UiThread
	@Override
	public void eventOccurred(@NonNull Event e) {
		if (e instanceof ConnectionOpenedEvent) {
			ConnectionOpenedEvent c = (ConnectionOpenedEvent) e;
			if (c.getContactId().equals(contactId) && c.isIncoming() &&
					c.getTransportId() == ID) {
				stopConnecting();
				LOG.info("Contact connected to us");
				state.postEvent(new Success());
			}
		}
	}

	@IoExecutor
	private boolean waitForBluetoothActive() {
		BluetoothPlugin bluetoothPlugin = requireNonNull(this.bluetoothPlugin);
		long left = BT_ACTIVE_TIMEOUT;
		final long sleep = 250;
		try {
			while (left > 0) {
				if (bluetoothPlugin.getState() == ACTIVE) {
					return true;
				}
				Thread.sleep(sleep);
				left -= sleep;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return (bluetoothPlugin.getState() == ACTIVE);
	}

	/**
	 * Wait for an incoming connection before showing an error Toast.
	 */
	@IoExecutor
	private void waitAfterConnectionFailed() {
		long left = BT_ACTIVE_TIMEOUT;
		final long sleep = 250;
		try {
			while (left > 0) {
				if (isConnectedViaBluetooth()) {
					LOG.info("Failed to connect, but contact connected");
					// no success state needed here, as it gets shown when
					// ConnectionOpenedEvent is received
					return;
				}
				Thread.sleep(sleep);
				left -= sleep;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		LOG.warning("Failed to connect");
		state.postEvent(new ConnectViaBluetoothState.Error(
				R.string.connect_via_bluetooth_error));
	}

	private void stopConnecting() {
		BluetoothPlugin bluetoothPlugin = this.bluetoothPlugin;
		if (bluetoothPlugin != null &&
				areBluetoothPermissionsGranted(getApplication())) {
			bluetoothPlugin.stopDiscoverAndConnect();
		}
	}

	LiveEvent<ConnectViaBluetoothState> getState() {
		return state;
	}

}
