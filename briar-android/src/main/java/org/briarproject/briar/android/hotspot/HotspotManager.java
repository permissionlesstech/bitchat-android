package org.briarproject.briar.android.hotspot;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener;
import android.os.Handler;
import android.os.PowerManager;
import android.util.DisplayMetrics;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.R;
import org.briarproject.briar.android.hotspot.HotspotState.NetworkConfig;
import org.briarproject.briar.android.qrcode.QrCodeUtils;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.UiThread;

import static android.content.Context.POWER_SERVICE;
import static android.content.Context.WIFI_P2P_SERVICE;
import static android.content.Context.WIFI_SERVICE;
import static android.net.wifi.WifiManager.WIFI_MODE_FULL;
import static android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
import static android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_2GHZ;
import static android.net.wifi.p2p.WifiP2pManager.BUSY;
import static android.net.wifi.p2p.WifiP2pManager.ERROR;
import static android.net.wifi.p2p.WifiP2pManager.NO_SERVICE_REQUESTS;
import static android.net.wifi.p2p.WifiP2pManager.P2P_UNSUPPORTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.PowerManager.FULL_WAKE_LOCK;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.briar.android.qrcode.QrCodeUtils.HOTSPOT_QRCODE_FACTOR;
import static org.briarproject.briar.android.util.UiUtils.handleException;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class HotspotManager {

	interface HotspotListener {
		@UiThread
		void onStartingHotspot();

		@IoExecutor
		void onHotspotStarted(NetworkConfig networkConfig);

		@UiThread
		void onPeersUpdated(int peers);

		@UiThread
		void onHotspotError(String error);
	}

	private static final Logger LOG = getLogger(HotspotManager.class.getName());

	private static final int MAX_FRAMEWORK_ATTEMPTS = 5;
	private static final int MAX_GROUP_INFO_ATTEMPTS = 5;
	private static final int RETRY_DELAY_MILLIS = 1000;
	private static final String HOTSPOT_NAMESPACE = "hotspot";
	private static final String HOTSPOT_KEY_SSID = "ssid";
	private static final String HOTSPOT_KEY_PASS = "pass";

	private final Context ctx;
	@DatabaseExecutor
	private final Executor dbExecutor;
	@IoExecutor
	private final Executor ioExecutor;
	private final AndroidExecutor androidExecutor;
	private final SettingsManager settingsManager;
	private final SecureRandom random;
	private final WifiManager wifiManager;
	private final WifiP2pManager wifiP2pManager;
	private final PowerManager powerManager;
	private final Handler handler;
	private final String lockTag;

	private HotspotListener listener;
	private WifiManager.WifiLock wifiLock;
	private PowerManager.WakeLock wakeLock;
	private WifiP2pManager.Channel channel;
	@Nullable
	@RequiresApi(29)
	private volatile NetworkConfig savedNetworkConfig = null;

	@Inject
	HotspotManager(Application ctx,
			@DatabaseExecutor Executor dbExecutor,
			@IoExecutor Executor ioExecutor,
			AndroidExecutor androidExecutor,
			SettingsManager settingsManager,
			SecureRandom random) {
		this.ctx = ctx.getApplicationContext();
		this.dbExecutor = dbExecutor;
		this.ioExecutor = ioExecutor;
		this.androidExecutor = androidExecutor;
		this.settingsManager = settingsManager;
		this.random = random;
		wifiManager = (WifiManager) ctx.getApplicationContext()
				.getSystemService(WIFI_SERVICE);
		wifiP2pManager =
				(WifiP2pManager) ctx.getSystemService(WIFI_P2P_SERVICE);
		powerManager = (PowerManager) ctx.getSystemService(POWER_SERVICE);
		handler = new Handler(ctx.getMainLooper());
		lockTag = ctx.getPackageName() + ":app-sharing-hotspot";
	}

	void setHotspotListener(HotspotListener listener) {
		this.listener = listener;
	}

	@UiThread
	void startWifiP2pHotspot() {
		if (wifiP2pManager == null) {
			listener.onHotspotError(
					ctx.getString(R.string.hotspot_error_no_wifi_direct));
			return;
		}
		listener.onStartingHotspot();
		acquireLocks();
		startWifiP2pFramework(1);
	}

	/**
	 * As soon as Wifi is enabled, we try starting the WifiP2p framework.
	 * If Wifi has just been enabled, it is possible that will fail. If that
	 * happens we try again for MAX_FRAMEWORK_ATTEMPTS times after a delay of
	 * RETRY_DELAY_MILLIS after each attempt.
	 * <p>
	 * Rationale: it can take a few milliseconds for WifiP2p to become available
	 * after enabling Wifi. Depending on the API level it is possible to check this
	 * using {@link WifiP2pManager#requestP2pState} or register a BroadcastReceiver
	 * on the WIFI_P2P_STATE_CHANGED_ACTION to get notified when WifiP2p is really
	 * available. Trying to implement a solution that works reliably using these
	 * checks turned out to be a long rabbit-hole with lots of corner cases and
	 * workarounds for specific situations.
	 * Instead we now rely on this trial-and-error approach of just starting
	 * the framework and retrying if it fails.
	 * <p>
	 * We'll realize that the framework is busy when the ActionListener passed
	 * to {@link WifiP2pManager#createGroup} is called with onFailure(BUSY)
	 */
	@UiThread
	private void startWifiP2pFramework(int attempt) {
		if (LOG.isLoggable(INFO)) {
			LOG.info("startWifiP2pFramework attempt: " + attempt);
		}
		/*
		 * It is important that we call WifiP2pManager#initialize again
		 * for every attempt to starting the framework because otherwise,
		 * createGroup() will continue to fail with a BUSY state.
		 */
		channel = wifiP2pManager.initialize(ctx, ctx.getMainLooper(), null);
		if (channel == null) {
			releaseHotspotWithError(
					ctx.getString(R.string.hotspot_error_no_wifi_direct));
			return;
		}

		ActionListener listener = new ActionListener() {
			@Override
			// Callback for wifiP2pManager#createGroup() during startWifiP2pHotspot()
			public void onSuccess() {
				requestGroupInfo(1);
			}

			@Override
			// Callback for wifiP2pManager#createGroup() during startWifiP2pHotspot()
			public void onFailure(int reason) {
				if (LOG.isLoggable(INFO)) {
					LOG.info("onFailure: " + reason);
				}
				if (reason == BUSY) {
					// WifiP2p not ready yet or hotspot already running
					restartWifiP2pFramework(attempt);
				} else if (reason == P2P_UNSUPPORTED) {
					releaseHotspotWithError(ctx.getString(
							R.string.hotspot_error_start_callback_failed,
							"p2p unsupported"));
				} else if (reason == ERROR) {
					releaseHotspotWithError(ctx.getString(
							R.string.hotspot_error_start_callback_failed,
							"p2p error"));
				} else if (reason == NO_SERVICE_REQUESTS) {
					releaseHotspotWithError(ctx.getString(
							R.string.hotspot_error_start_callback_failed,
							"no service requests"));
				} else {
					// all cases covered, in doubt set to error
					releaseHotspotWithError(ctx.getString(
							R.string.hotspot_error_start_callback_failed_unknown,
							reason));
				}
			}
		};

		try {
			if (SDK_INT >= 29) {
				Runnable createGroup = () -> {
					NetworkConfig c = requireNonNull(savedNetworkConfig);
					WifiP2pConfig config = new WifiP2pConfig.Builder()
							.setGroupOperatingBand(GROUP_OWNER_BAND_2GHZ)
							.setNetworkName(c.ssid)
							.setPassphrase(c.password)
							.build();
					wifiP2pManager.createGroup(channel, config, listener);
				};
				if (savedNetworkConfig == null) {
					// load savedNetworkConfig before starting hotspot
					dbExecutor.execute(() -> {
						loadSavedNetworkConfig();
						androidExecutor.runOnUiThread(createGroup);
					});
				} else {
					// savedNetworkConfig was already loaded, create group now
					createGroup.run();
				}
			} else {
				wifiP2pManager.createGroup(channel, listener);
			}
		} catch (SecurityException e) {
			// this should never happen, because we request permissions before
			throw new AssertionError(e);
		}
	}

	@UiThread
	private void restartWifiP2pFramework(int attempt) {
		LOG.info("retrying to start WifiP2p framework");
		if (attempt < MAX_FRAMEWORK_ATTEMPTS) {
			if (SDK_INT >= 27 && channel != null) channel.close();
			channel = null;
			handler.postDelayed(() -> startWifiP2pFramework(attempt + 1),
					RETRY_DELAY_MILLIS);
		} else {
			releaseHotspotWithError(
					ctx.getString(R.string.hotspot_error_framework_busy));
		}
	}

	@UiThread
	void stopWifiP2pHotspot() {
		if (channel == null) return;
		wifiP2pManager.removeGroup(channel, new ActionListener() {
			@Override
			public void onSuccess() {
				closeChannelAndReleaseLocks();
			}

			@Override
			public void onFailure(int reason) {
				// not propagating back error
				if (LOG.isLoggable(WARNING)) {
					LOG.warning("Error removing Wifi P2P group: " + reason);
				}
				closeChannelAndReleaseLocks();
			}
		});
	}

	@SuppressLint("WakelockTimeout")
	private void acquireLocks() {
		// FLAG_KEEP_SCREEN_ON is not respected on some Huawei devices.
		wakeLock = powerManager.newWakeLock(FULL_WAKE_LOCK, lockTag);
		wakeLock.acquire();
		// WIFI_MODE_FULL has no effect on API >= 29
		int lockType =
				SDK_INT >= 29 ? WIFI_MODE_FULL_HIGH_PERF : WIFI_MODE_FULL;
		wifiLock = wifiManager.createWifiLock(lockType, lockTag);
		wifiLock.acquire();
	}

	@UiThread
	private void releaseHotspotWithError(String error) {
		listener.onHotspotError(error);
		closeChannelAndReleaseLocks();
	}

	@UiThread
	private void closeChannelAndReleaseLocks() {
		if (SDK_INT >= 27 && channel != null) channel.close();
		channel = null;
		if (wakeLock.isHeld()) wakeLock.release();
		if (wifiLock.isHeld()) wifiLock.release();
	}

	@UiThread
	private void requestGroupInfo(int attempt) {
		if (LOG.isLoggable(INFO)) {
			LOG.info("requestGroupInfo attempt: " + attempt);
		}
		GroupInfoListener groupListener = group -> {
			boolean valid = isGroupValid(group);
			if (valid) {
				// the group is valid, set the hotspot to started.
				onHotspotStarted(group);
			} else if (attempt < MAX_GROUP_INFO_ATTEMPTS) {
				// we have attempts left, try again
				retryRequestingGroupInfo(attempt);
			} else if (group != null) {
				// no attempts left, but group is not null, try what we got
				onHotspotStarted(group);
			} else {
				// no attempts left and group is null, fail
				releaseHotspotWithError(ctx.getString(
						R.string.hotspot_error_start_callback_no_group_info));
			}
		};
		try {
			if (channel == null) return;
			wifiP2pManager.requestGroupInfo(channel, groupListener);
		} catch (SecurityException e) {
			// this should never happen, because we request permissions before
			throw new AssertionError(e);
		}
	}

	@UiThread
	private void onHotspotStarted(WifiP2pGroup group) {
		DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
		ioExecutor.execute(() -> {
			String content = createWifiLoginString(group.getNetworkName(),
					group.getPassphrase());
			Bitmap qrCode = QrCodeUtils.createQrCode(
					(int) (dm.heightPixels * HOTSPOT_QRCODE_FACTOR), content);
			NetworkConfig config = new NetworkConfig(group.getNetworkName(),
					group.getPassphrase(), qrCode);
			listener.onHotspotStarted(config);
		});
		requestGroupInfoForConnection();
	}

	private boolean isGroupValid(@Nullable WifiP2pGroup group) {
		if (group == null) {
			LOG.info("group is null");
			return false;
		} else if (!group.getNetworkName().startsWith("DIRECT-")) {
			LOG.info("received networkName without prefix 'DIRECT-'");
			return false;
		} else if (SDK_INT >= 29) {
			// if we get here, the savedNetworkConfig must have a value
			String networkName = requireNonNull(savedNetworkConfig).ssid;
			if (!networkName.equals(group.getNetworkName())) {
				LOG.info("expected networkName does not match received one");
				return false;
			}
		}
		return true;
	}

	@UiThread
	private void retryRequestingGroupInfo(int attempt) {
		LOG.info("retrying to request group info");
		// On some devices we need to wait for the group info to become available
		handler.postDelayed(() -> requestGroupInfo(attempt + 1),
				RETRY_DELAY_MILLIS);
	}

	@UiThread
	private void requestGroupInfoForConnection() {
		LOG.info("requestGroupInfo for connection");
		GroupInfoListener groupListener = group -> {
			if (group != null) {
				listener.onPeersUpdated(group.getClientList().size());
			}
			handler.postDelayed(this::requestGroupInfoForConnection,
					RETRY_DELAY_MILLIS);
		};
		try {
			if (channel == null) return;
			wifiP2pManager.requestGroupInfo(channel, groupListener);
		} catch (SecurityException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Store persistent Wi-Fi SSID and passphrase in Settings to improve UX
	 * so that users don't have to change them when attempting to connect.
	 * Works only on API 29 and above.
	 */
	@RequiresApi(29)
	@DatabaseExecutor
	private void loadSavedNetworkConfig() {
		try {
			Settings settings = settingsManager.getSettings(HOTSPOT_NAMESPACE);
			String ssid = settings.get(HOTSPOT_KEY_SSID);
			String pass = settings.get(HOTSPOT_KEY_PASS);
			if (ssid == null || pass == null) {
				ssid = getSsid();
				pass = getPassword();
				settings.put(HOTSPOT_KEY_SSID, ssid);
				settings.put(HOTSPOT_KEY_PASS, pass);
				settingsManager.mergeSettings(settings, HOTSPOT_NAMESPACE);
			}
			savedNetworkConfig = new NetworkConfig(ssid, pass, null);
		} catch (DbException e) {
			handleException(ctx, androidExecutor, LOG, e);
			// probably never happens, but if lets use non-persistent data
			String ssid = getSsid();
			String pass = getPassword();
			savedNetworkConfig = new NetworkConfig(ssid, pass, null);
		}
	}

	@RequiresApi(29)
	private String getSsid() {
		return "DIRECT-" + getRandomString(2) + "-" +
				getRandomString(10);
	}

	@RequiresApi(29)
	private String getPassword() {
		return getRandomString(4) + "-" + getRandomString(4) + "-" +
				getRandomString(4) + "-" + getRandomString(4);
	}

	private static String createWifiLoginString(String ssid, String password) {
		// https://en.wikipedia.org/wiki/QR_code#Joining_a_Wi%E2%80%91Fi_network
		// do not remove the dangling ';', it can cause problems to omit it
		return "WIFI:S:" + ssid + ";T:WPA;P:" + password + ";;";
	}

	// exclude chars that are easy to confuse: 0 (O), 5 (S), 1 l (I)
	private static final String chars = "2346789abcdefghijkmnopqrstuvwxyz";

	private String getRandomString(int length) {
		char[] c = new char[length];
		for (int i = 0; i < length; i++) {
			c[i] = chars.charAt(random.nextInt(chars.length()));
		}
		return new String(c);
	}

}
